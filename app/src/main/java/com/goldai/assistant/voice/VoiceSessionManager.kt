package com.goldai.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.goldai.assistant.action.ActionExecutor
import com.goldai.assistant.action.AppContext
import com.goldai.assistant.action.DeviceActions
import com.goldai.assistant.action.DeviceCommand
import com.goldai.assistant.action.DeviceCommandParser
import com.goldai.assistant.action.InstalledAppLabelCache
import com.goldai.assistant.data.ApiKeyStore
import com.goldai.assistant.data.ChatHistoryMessage
import com.goldai.assistant.data.ChatHistoryStore
import com.goldai.assistant.data.ChatSession
import com.goldai.assistant.data.DeviceTtsVoicePreferences
import com.goldai.assistant.data.EnginePreferences
import com.goldai.assistant.data.FactMemoryStore
import com.goldai.assistant.data.LanguagePreference
import com.goldai.assistant.data.MemoryCapture
import com.goldai.assistant.data.MemoryFact
import com.goldai.assistant.data.NovaPreferences
import com.goldai.assistant.data.VoiceOutputEngine
import com.goldai.assistant.data.VoiceOutputPreferences
import com.goldai.assistant.data.Personality
import com.goldai.assistant.data.PersonalityRepository
import com.goldai.assistant.data.PocketTtsVoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object VoiceSessionManager {
    private const val TAG = "VoiceSessionManager"

    // V15 (voice-bug fix): SupervisorJob — one failed child must never
    // cancel the whole scope again. Before V15 a plain Job meant a
    // single uncaught exception in ANY voice-turn coroutine silently
    // killed every later launch, and the overlay wedged on
    // "Soch rahi hun…" forever with no error and no recovery.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val sessionEpoch = AtomicLong(0)

    /**
     * V15 (voice-bug fix) — the thinking watchdog. If Lia stays on
     * "Soch rahi hun…" for 25s, something in the reply chain died
     * (recognition result lost, scope dead, request hung). The watchdog
     * is Handler-based on purpose: it does NOT depend on the coroutine
     * scope, so it fires even when the scope itself is the problem.
     * It is armed when listening ends (onEndOfSpeech) and disarmed the
     * moment a reply starts speaking, a new listening round starts, or
     * the session stops.
     *
     * V19: the watchdog no longer recovers silently — it SPEAKS a
     * short error (system TTS) and restarts listening, so the user
     * always hears that something went wrong instead of watching a
     * stuck "Soch rahi hun…".
     */
    private const val THINKING_WATCHDOG_MS = 25_000L

    /**
     * V19 — hard deadlines for one voice round-trip (task B). Every
     * step has a real wall-clock bound now:
     * - STT (listening): 15s — a recognizer that never calls back is
     *   wedged; the listening watchdog recovers it (see below).
     * - Chat: 30s — real since V19 because PollinationsClient's calls
     *   are cancellable (enqueue + call.cancel()); the V15 20s
     *   `withTimeoutOrNull` around a BLOCKING OkHttp execute() could
     *   never fire mid-request (worst case was ~9 minutes).
     * - Online TTS synthesis: 20s (enforced inside OnlineTtsEngine).
     * - Pocket TTS speak: 30s (watchdog in speakPocketNoKey).
     * - Hindi TTS speak: 30s (watchdog in speakHindiNoKey, V21).
     * - Live connect: 30s (connect watchdog below).
     * V21: the Shruti on-device recognizer caps its own utterance
     * at 12s (see ShrutiSttEngine), so it needs no watchdog.
     */
    private const val VOICE_REPLY_TIMEOUT_MS = 30_000L
    private const val STT_LISTEN_TIMEOUT_MS = 15_000L
    private const val POCKET_SPEAK_TIMEOUT_MS = 30_000L
    private const val HINDI_SPEAK_TIMEOUT_MS = 30_000L
    private const val LIVE_CONNECT_TIMEOUT_MS = 30_000L

    /** V19: give up listening after this many consecutive STT failures. */
    private const val MAX_STT_FAILURES = 3

    /** V15: short retry waits for voice (text chat uses 2s/5s/10s). */
    private val VOICE_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)

    private val thinkingWatchdogRunnable = Runnable {
        if (!_isActive.value || !noKeyMode) return@Runnable
        if (_state.value != NovaOrbState.THINKING) return@Runnable
        // Still thinking after 25s — the turn died somewhere. SPEAK
        // the failure (never silent) and listen again.
        Log.w(TAG, "Thinking watchdog fired — voice turn never completed")
        disarmThinkingWatchdog()
        speakErrorAloud("Sorry jan, thoda time lag raha hai — dobara bolo.") {
            startNoKeyListening()
        }
    }

    private fun armThinkingWatchdog() {
        mainHandler.removeCallbacks(thinkingWatchdogRunnable)
        mainHandler.postDelayed(thinkingWatchdogRunnable, THINKING_WATCHDOG_MS)
    }

    private fun disarmThinkingWatchdog() {
        mainHandler.removeCallbacks(thinkingWatchdogRunnable)
    }

    /**
     * V19 (S2 fix) — the LISTENING watchdog. The recognizer can wedge
     * after startListening with NO callback at all (known on some
     * ROMs, Infinix XOS included): no onEndOfSpeech, no onResults, no
     * onError — the overlay sat on "Sun rahi hun…" forever. This
     * Handler-based watchdog is armed when a listening round starts
     * and disarmed the moment speech is detected, a result arrives,
     * an error fires, or the session stops. On fire it counts as an
     * STT failure (see onSttFailure): the recognizer is destroyed and
     * a fresh round starts, with a spoken error; three consecutive
     * failures end the session with a spoken explanation instead of
     * looping forever.
     */
    private val listeningWatchdogRunnable = Runnable {
        if (!_isActive.value || !noKeyMode) return@Runnable
        if (!noKeyListening || _state.value != NovaOrbState.LISTENING) return@Runnable
        Log.w(TAG, "Listening watchdog fired — recognizer never called back")
        disarmListeningWatchdog()
        onSttFailure()
    }

    private fun armListeningWatchdog() {
        mainHandler.removeCallbacks(listeningWatchdogRunnable)
        mainHandler.postDelayed(listeningWatchdogRunnable, STT_LISTEN_TIMEOUT_MS)
    }

    private fun disarmListeningWatchdog() {
        mainHandler.removeCallbacks(listeningWatchdogRunnable)
    }

    /**
     * V19 (S6 fix) — the LIVE connect watchdog. connectSession can sit
     * on "Connecting..." forever when onSetupComplete never fires and
     * no error arrives. Armed on connect, disarmed on setup complete,
     * error, close, or stop. On fire it disconnects, shows the
     * slow-network error, SPEAKS it, and lets the normal reconnect
     * policy decide what happens next.
     */
    private val connectWatchdogRunnable = Runnable {
        if (!_isActive.value || noKeyMode) return@Runnable
        if (_state.value != NovaOrbState.CONNECTING) return@Runnable
        Log.w(TAG, "Live connect watchdog fired — setup never completed")
        disarmConnectWatchdog()
        client?.disconnect()
        _state.value = NovaOrbState.ERROR
        _statusText.value = "Net bohat slow hai, voice connect nahi ho rahi. " +
            "Text chat mein likh lo — wahan se bhi baat ho jayegi."
        _lastError.value = "Live connect timed out"
        speakErrorAloud(_statusText.value) {
            scheduleReconnect()
        }
    }

    private fun armConnectWatchdog() {
        mainHandler.removeCallbacks(connectWatchdogRunnable)
        mainHandler.postDelayed(connectWatchdogRunnable, LIVE_CONNECT_TIMEOUT_MS)
    }

    private fun disarmConnectWatchdog() {
        mainHandler.removeCallbacks(connectWatchdogRunnable)
    }

    /** V19: consecutive STT failures (watchdog / network / start errors). */
    private var sttFailureStrikes = 0

    /**
     * V19 — one STT failure (listening watchdog, recognizer network /
     * server error, or startListening throwing). Never silent: the
     * user hears a short error and listening restarts. After
     * [MAX_STT_FAILURES] consecutive failures the session stops
     * trying — the overlay goes to ERROR with a spoken explanation
     * and a "Dobara try karo" button (see VoiceScreen), instead of
     * looping spoken errors forever. Quiet-room errors (no match /
     * speech timeout) are NOT failures and never come here.
     */
    private fun onSttFailure() {
        if (!_isActive.value || !noKeyMode) return
        sttFailureStrikes++
        stopNoKeyListening()
        if (sttFailureStrikes >= MAX_STT_FAILURES) {
            disarmListeningWatchdog()
            disarmThinkingWatchdog()
            _state.value = NovaOrbState.ERROR
            setTurnState(VoiceTurnState.ERROR)
            _statusText.value = "Microphone se awaz nahi aa rahi — voice band kar rahi hun. " +
                "Dobara try karo, ya text chat use karo."
            _lastError.value = "Speech recognition failed repeatedly"
            speakErrorAloud(_statusText.value) {
                _state.value = NovaOrbState.ERROR
                setTurnState(VoiceTurnState.ERROR)
            }
            return
        }
        speakErrorAloud("Sorry jan, awaz nahi aa rahi, dobara bolo.") {
            startNoKeyListening()
        }
    }

    // V15: a fact the auto-rules proposed in voice, waiting for a
    // spoken "Haan" / "Nahi". Same rules as text chat — never silent.
    @Volatile
    private var pendingMemoryFact: MemoryFact? = null

    private val _state = MutableStateFlow(NovaOrbState.IDLE)
    val state: StateFlow<NovaOrbState> = _state.asStateFlow()

    // V16 (APK-delta addition): the explicit turn state machine —
    // see VoiceTurnState. It mirrors the orb state but tracks the
    // conversation TURN (listening -> speech -> end-of-speech ->
    // thinking -> speaking), so the overlay and the debugger can show
    // exactly where a turn is stuck instead of just "thinking".
    private val _turnState = MutableStateFlow(VoiceTurnState.IDLE)
    val turnState: StateFlow<VoiceTurnState> = _turnState.asStateFlow()

    private fun setTurnState(next: VoiceTurnState) {
        if (_turnState.value != next) {
            Log.d(TAG, "Turn: ${_turnState.value} -> $next")
            _turnState.value = next
        }
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _statusText = MutableStateFlow("Ready to talk")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _currentPersonality = MutableStateFlow(Personality.NORMAL)
    val currentPersonality: StateFlow<Personality> = _currentPersonality.asStateFlow()

    private val _currentLanguage = MutableStateFlow(LanguagePreference.AUTO)
    val currentLanguage: StateFlow<LanguagePreference> = _currentLanguage.asStateFlow()

    private val _detectedLanguage = MutableStateFlow("UNKNOWN")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()

    private val _vadActive = MutableStateFlow(false)
    val vadActive: StateFlow<Boolean> = _vadActive.asStateFlow()

    private var client: GeminiLiveClient? = null
    private var audioIO: LiveAudioIO? = null
    private var appContext: Context? = null

    // V12 no-key voice mode: Android SpeechRecognizer for input,
    // Pollinations chat for the brain, and the V21 voice chain
    // (HINDI → POCKET → ONLINE → SYSTEM) for output. No key, no
    // network service for the voice itself. V21: the recognizer is
    // the on-device Shruti Urdu model when downloaded (it hears his
    // Urdu as Urdu); the system recognizer stays the fallback. The
    // chat engine is the Settings "AI Engine" choice — Pollinations
    // (free, no key, default) or Gemini (his own free key, faster
    // and better at Roman Urdu) — see handleNoKeyTranscript.
    private var noKeyMode = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var noKeyTts: TextToSpeech? = null
    private var noKeyTtsReady = false

    private var noKeyListening = false
    private var noKeySpeaking = false
    private var noKeyRestartJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // V19: the cached on-device voice (Pocket TTS). Created once per
    // session (loading ~191 MB of ONNX models is expensive) and
    // released in stop(); recreated when the user changes voice in
    // Settings. Null when the model is not downloaded.
    private var pocketTts: PocketTtsEngine? = null
    private var pocketTtsVoiceId: String? = null

    // V21: the cached Hindi voice (Supertonic 3, ~139 MB) and the
    // cached on-device Urdu/Hindi recognizer (Shruti, ~51 MB).
    // Same lifecycle as Pocket: created once per session, released
    // in stop(). Null when their models are not downloaded.
    private var hindiTts: HindiTtsEngine? = null
    private var shrutiStt: ShrutiSttEngine? = null

    // V19: streaming reply state — sentences spoken as they arrive.
    // The pump speaks queued sentences one at a time through the
    // normal voice chain; `streamDone` is set when the chat stream
    // ends (or times out) and the queue drains.
    private val streamQueue = ArrayDeque<String>()
    private var streamSpeaking = false
    private var streamDone = false
    private var streamSpokenAny = false
    private var streamEnqueuedSentences = 0
    private var streamFullText = ""

    private fun resetStreamState() {
        streamQueue.clear()
        streamSpeaking = false
        streamDone = false
        streamSpokenAny = false
        streamEnqueuedSentences = 0
        streamFullText = ""
    }

    private var reconnectJob: Job? = null
    private var silenceWatchdogJob: Job? = null
    private var isTurnActive = false

    /**
     * Last device command executed locally from a voice transcript, with the
     * time it ran. Gemini hears the same words and may also try its own
     * open_app/call_contact tool for them — this guard stops the same action
     * running twice.
     */
    private var lastLocalActionSignature: String? = null
    private var lastLocalActionAtMs: Long = 0L

    private fun localActionSignature(command: DeviceCommand): String? = when (command) {
        is DeviceCommand.OpenApp -> "open_app:${command.appName.lowercase()}"
        is DeviceCommand.Dial -> "call_contact:${command.target.lowercase()}"
        // V14: a local direct call is the same call Gemini's call_contact
        // would make — same namespace, so Gemini never dials twice.
        is DeviceCommand.DirectCall -> "call_contact:${command.target.lowercase()}"
        is DeviceCommand.WhatsAppMessage -> "message_contact:${command.target.lowercase()}"
        // V14: toggles must never run twice — DND twice undoes itself.
        is DeviceCommand.Brightness -> "brightness"
        is DeviceCommand.DoNotDisturb -> "dnd"
        // V14: a notification reply sent twice is two messages.
        is DeviceCommand.ReplyNotification ->
            "reply_notification:${command.appHint?.lowercase() ?: "latest"}"
        else -> null
    }

    private fun wasHandledLocallyRecently(toolName: String, args: JSONObject): Boolean {
        val signature = lastLocalActionSignature ?: return false
        if (System.currentTimeMillis() - lastLocalActionAtMs > 20_000L) return false
        return when (toolName) {
            "open_app" -> signature == "open_app:${args.optString("app_name", "").lowercase()}"
            "call_contact", "direct_call" ->
                signature == "call_contact:${args.optString("contact_name", "").lowercase()}"
            "message_contact" -> signature == "message_contact:${args.optString("contact_name", "").lowercase()}"
            // V14: Gemini's set_brightness / set_dnd / reply_notification
            // must not re-run a command the local parser already did.
            "set_brightness" -> signature == "brightness"
            "set_dnd" -> signature == "dnd"
            "reply_notification" -> signature.startsWith("reply_notification:")
            else -> false
        }
    }

    /**
     * Makes Lia SPEAK a confirmation out loud after a local device action,
     * in her own voice, through the live session. Without this she went
     * completely silent after opening another app: the reply string was
     * only logged, and MainActivity.onStop killed the session before
     * Gemini could answer. The injected turn tells the model what was just
     * done so it answers warmly and asks what's next.
     */
    private fun speakActionConfirmation(reply: String) {
        val liveClient = client ?: return
        if (!liveClient.isLive()) return
        liveClient.sendText(
            "System note — do not read this out literally: I just did this on " +
                "the user's phone: $reply Tell him out loud in Roman Urdu, in one " +
                "short warm sentence, what I did, and ask what he wants next."
        )
    }

    /**
     * V19 (S9 fix) — guarantee a working system TTS, creating (or
     * re-creating) it when needed. The old code created noKeyTts once
     * in startNoKeyMode and, if its init failed, every later reply
     * silently showed as text with no retry and no notice. Now any
     * path that needs to SPEAK an error (or a reply) goes through
     * here: a dead instance is dropped and a fresh one is built, with
     * a 3s init watchdog — [onReady] fires exactly once, with the TTS
     * or null. In Live mode (noKeyTts is null there) this lazily
     * creates the TTS the spoken error needs; stop() shuts it down.
     */
    private fun ensureSystemTts(onReady: (TextToSpeech?) -> Unit) {
        val existing = noKeyTts
        if (existing != null && noKeyTtsReady) {
            onReady(existing)
            return
        }
        if (existing != null) {
            runCatching { existing.shutdown() }
            noKeyTts = null
            noKeyTtsReady = false
        }
        val ctx = appContext ?: run { onReady(null); return }
        var done = false
        val finish = { tts: TextToSpeech? ->
            if (!done) {
                done = true
                onReady(tts)
            }
        }
        val timeout = Runnable { finish(null) }
        mainHandler.postDelayed(timeout, 3_000L)
        noKeyTts = TextToSpeech(ctx) { status ->
            mainHandler.removeCallbacks(timeout)
            if (status == TextToSpeech.SUCCESS) {
                noKeyTtsReady = true
                noKeyTts?.let {
                    CuteVoice.applyCuteVoice(
                        it,
                        DeviceTtsVoicePreferences.getVoiceName(ctx)
                    )
                }
                finish(noKeyTts)
            } else {
                noKeyTtsReady = false
                finish(null)
            }
        }
    }

    /**
     * V19 — SPEAK a short error out loud, on ANY failure, via system
     * TTS directly. This deliberately bypasses the voice chain (the
     * chain is what failed) so an error can never die inside the
     * engine it is reporting. [then] runs exactly once: after the
     * error finishes speaking, or at once when no TTS can be had
     * (the error is still shown as status text). A 10s watchdog
     * guarantees `then` even if the TTS callbacks never fire. While
     * the error speaks the recognizer stays off, exactly like a
     * normal reply.
     */
    private fun speakErrorAloud(text: String, then: (() -> Unit)? = null) {
        if (!_isActive.value) {
            then?.invoke()
            return
        }
        disarmThinkingWatchdog()
        disarmListeningWatchdog()
        _statusText.value = text
        _state.value = NovaOrbState.SPEAKING
        setTurnState(VoiceTurnState.SPEAKING)
        noKeySpeaking = true
        var done = false
        val finish = {
            if (!done) {
                done = true
                noKeySpeaking = false
                _amplitude.value = 0f
                then?.invoke()
            }
        }
        val watchdog = Runnable { finish() }
        mainHandler.postDelayed(watchdog, 10_000L)
        ensureSystemTts { tts ->
            if (tts == null) {
                // No voice at all: the text is already on screen.
                mainHandler.removeCallbacks(watchdog)
                finish()
                return@ensureSystemTts
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.removeCallbacks(watchdog)
                    finish()
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.removeCallbacks(watchdog)
                    finish()
                }
            })
            if (tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "lia-error-${System.currentTimeMillis()}"
                ) == TextToSpeech.ERROR
            ) {
                mainHandler.removeCallbacks(watchdog)
                finish()
            }
        }
    }

    /**
     * V19 (S8 fix) — retry after an ERROR state (the VoiceScreen
     * "Dobara try karo" button). Resets the failure count and starts
     * a fresh listening round; the session itself never stopped, so
     * no re-connect is needed.
     */
    fun retryListening() {
        if (!_isActive.value || !noKeyMode) return
        sttFailureStrikes = 0
        _lastError.value = null
        startNoKeyListening()
    }

    fun start(context: Context) {
        if (_isActive.value) return
        appContext = context.applicationContext

        _isActive.value = true
        _lastError.value = null
        _statusText.value = "Connecting..."
        _state.value = NovaOrbState.CONNECTING

        ConversationMemory.clear()
        VoiceLatencyTracker.reset()
        InstalledAppLabelCache.prewarm(context)

        // V12: no Gemini key? Free no-key voice — on-device speech
        // recognition + Pollinations chat + system TTS. The user is never
        // asked for a key; the app just works when opened.
        if (ApiKeyStore.getKey(context).isEmpty()) {
            startNoKeyMode(context.applicationContext)
            return
        }

        audioIO = LiveAudioIO(
            onMicChunk = { bytes ->
                if (_isActive.value && !_muted.value && !EchoGuard.isMicMuted()) {
                    client?.sendAudio(bytes)
                }
            },
            onMicAmplitude = { amp ->
                if (!_muted.value && !EchoGuard.isMicMuted()) {
                    _amplitude.value = amp
                    _vadActive.value = amp > 0.05f
                }
            },
            context = context.applicationContext
        )

        scope.launch {
            sessionMutex.withLock {
                connectSession(isResuming = false)
            }
        }
    }

    // ------------------------------------------------------------------
    // V12 no-key voice mode.
    //
    // How it works: the recognizer listens for one utterance, the
    // transcript goes to Pollinations chat (same GF instruction as text
    // chat, streamed — the first sentence speaks as it arrives), and
    // the reply is spoken through the V19 voice chain (Pocket on-device
    // voice first when downloaded, then the online Kokoro voice, then
    // system TTS). Then listening restarts automatically — tap-to-talk,
    // no wake word.
    // Device commands from the transcript still run locally, exactly
    // like the Live path (same parser, same 20s double-run guard).
    //
    // Mic guarantee (same as the Live path): stop() cancels + destroys
    // the recognizer and stops + shuts down TTS, so nothing keeps the
    // mic after the session ends and WhatsApp voice notes work again.
    // While TTS is speaking, the recognizer is NOT listening, so Lia
    // never hears her own voice as input.
    // ------------------------------------------------------------------

    private fun startNoKeyMode(context: Context) {
        noKeyMode = true
        _state.value = NovaOrbState.LISTENING
        _statusText.value = "Boliye, main sun rahi hun…"

        scope.launch {
            _currentPersonality.value = runCatching {
                PersonalityRepository.getPersonality(context)
            }.getOrDefault(Personality.GF_MODE)
            _currentLanguage.value = runCatching {
                PersonalityRepository.getLanguage(context)
            }.getOrDefault(LanguagePreference.AUTO)
        }

        // System TTS, cute voice (female voice, pitch 1.2, rate 0.95).
        // No key, no network — works offline on every phone.
        // V16 (P0-5): the user's picked device voice wins when set.
        noKeyTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                noKeyTtsReady = true
                noKeyTts?.let {
                    CuteVoice.applyCuteVoice(
                        it,
                        DeviceTtsVoicePreferences.getVoiceName(context)
                    )
                }
            }
        }

        startNoKeyListening()
    }

    /** Speech language for the recognizer: Urdu for Roman Urdu speech. */
    private fun noKeyRecognitionLanguage(): String {
        return if (_currentLanguage.value == LanguagePreference.ENGLISH) "en-US" else "ur-PK"
    }

    /** Start one listening round on the main thread (recognizer rule). */
    private fun startNoKeyListening() {
        // A new listening round means the previous turn ended one way
        // or another — the watchdog's job is done.
        disarmThinkingWatchdog()
        disarmListeningWatchdog()
        if (!_isActive.value || !noKeyMode || _muted.value || noKeySpeaking) return
        mainHandler.post {
            if (!_isActive.value || !noKeyMode || _muted.value || noKeySpeaking) return@post
            val ctx = appContext ?: return@post
            // V21: on-device Urdu STT first when its model is
            // downloaded — Shruti hears his Urdu as Urdu, where the
            // system recognizer (English-tuned) mis-hears it. The
            // system recognizer below stays the fallback when no
            // Shruti model is downloaded.
            if (ShrutiSttEngine.isReady(ctx)) {
                startShrutiListening(ctx)
                return@post
            }
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    _state.value = NovaOrbState.ERROR
                    _statusText.value = "Voice recognition is not available on this phone. " +
                        "Text chat se baat kar lo."
                    _lastError.value = "SpeechRecognizer unavailable"
                    return@post
                }
                val recognizer = speechRecognizer
                    ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
                        it.setRecognitionListener(noKeyRecognitionListener)
                        speechRecognizer = it
                    }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, noKeyRecognitionLanguage())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                noKeyListening = true
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Boliye, main sun rahi hun…"
                recognizer.startListening(intent)
                // V19 (S2): a recognizer that never calls back wedges
                // the overlay — the watchdog catches it at 15s.
                armListeningWatchdog()
            } catch (e: Exception) {
                // V19 (S4 fix): the old code swallowed this silently
                // (noKeyListening=false, no retry, no error). A
                // startListening throw is now an STT failure: spoken
                // error + fresh round, three strikes and it stops.
                Log.w(TAG, "No-key listening failed", e)
                noKeyListening = false
                onSttFailure()
            }
        }
    }

    private fun stopNoKeyListening() {
        noKeyRestartJob?.cancel()
        noKeyRestartJob = null
        disarmListeningWatchdog()
        // V21: stop an in-flight Shruti utterance too (its listenOnce
        // checks the flag and returns null).
        try {
            shrutiStt?.stopListening()
        } catch (_: Exception) {
        }
        if (!noKeyListening) return
        noKeyListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    /**
     * V21 — one listening round with the on-device Shruti recognizer
     * (Urdu pack first, Hindi fallback). listenOnce blocks up to
     * 12s on a background thread and returns the full utterance
     * text (the sherpa-onnx Moonshine pack has no word-by-word
     * streaming). A blank result is a quiet room — listen again
     * after a short pause, exactly like the system recognizer's
     * ERROR_NO_MATCH path. A stopped session / mute drops the
     * result instead of starting a turn.
     */
    private fun startShrutiListening(ctx: Context) {
        noKeyListening = true
        _state.value = NovaOrbState.LISTENING
        _statusText.value = "Boliye, main sun rahi hun…"
        setTurnState(VoiceTurnState.LISTENING)
        scope.launch {
            val engine = getOrCreateShrutiStt(ctx)
            val text = engine?.listenOnce(ctx)
            mainHandler.post {
                noKeyListening = false
                if (!_isActive.value || !noKeyMode || _muted.value) return@post
                if (text.isNullOrBlank()) {
                    scheduleNoKeyRestart(1_500L)
                } else {
                    sttFailureStrikes = 0
                    handleNoKeyTranscript(text)
                }
            }
        }
    }

    /** The cached Shruti recognizer (created once per session). */
    private fun getOrCreateShrutiStt(ctx: Context): ShrutiSttEngine? {
        shrutiStt?.let { return it }
        val engine = ShrutiSttEngine.create(ctx) ?: return null
        shrutiStt = engine
        return engine
    }

    private fun releaseShrutiStt() {
        runCatching { shrutiStt?.release() }
        shrutiStt = null
    }

    private fun scheduleNoKeyRestart(delayMs: Long) {
        if (!_isActive.value || !noKeyMode || _muted.value) return
        noKeyRestartJob?.cancel()
        noKeyRestartJob = scope.launch {
            delay(delayMs)
            if (_isActive.value && noKeyMode && !_muted.value && !noKeySpeaking) {
                startNoKeyListening()
            }
        }
    }

    private val noKeyRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = NovaOrbState.LISTENING
            setTurnState(VoiceTurnState.LISTENING)
        }

        override fun onBeginningOfSpeech() {
            // Speech arrived — the recognizer is alive: the listening
            // watchdog's job is done, and the failure count resets.
            disarmListeningWatchdog()
            sttFailureStrikes = 0
            _state.value = NovaOrbState.LISTENING
            _statusText.value = "Sun rahi hun…"
            setTurnState(VoiceTurnState.SPEECH_DETECTED)
        }

        override fun onRmsChanged(rmsdB: Float) {
            _amplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            disarmListeningWatchdog()
            noKeyListening = false
            _state.value = NovaOrbState.THINKING
            _statusText.value = "Soch rahi hun…"
            // V16: the turn boundary — the user stopped speaking.
            // The watchdog is armed HERE, from end-of-speech, so a
            // lost result is caught at the turn boundary, not from a
            // timer started when listening began.
            setTurnState(VoiceTurnState.END_OF_SPEECH)
            armThinkingWatchdog()
        }

        override fun onError(error: Int) {
            disarmListeningWatchdog()
            noKeyListening = false
            _amplitude.value = 0f
            if (!_isActive.value || !noKeyMode || _muted.value) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _state.value = NovaOrbState.ERROR
                    setTurnState(VoiceTurnState.ERROR)
                    _statusText.value = "Microphone permission chahiye — Settings se allow karo."
                    _lastError.value = "Microphone permission missing"
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Quiet room — listen again after a short pause.
                    // Not a failure: no error is spoken for silence.
                    setTurnState(VoiceTurnState.IDLE)
                    scheduleNoKeyRestart(1_500L)
                }
                else -> {
                    // V19 (S5 fix): network / server / busy errors used
                    // to restart listening silently every 800ms, looping
                    // forever when the recognizer server was unreachable.
                    // Now they are STT failures: spoken error + restart,
                    // three strikes and the session stops with a retry
                    // button instead of looping.
                    setTurnState(VoiceTurnState.IDLE)
                    onSttFailure()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            disarmListeningWatchdog()
            sttFailureStrikes = 0
            noKeyListening = false
            _amplitude.value = 0f
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                scheduleNoKeyRestart(500L)
                return
            }
            handleNoKeyTranscript(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * V19 — streaming reply: speak the reply sentence by sentence as
     * the chat stream delivers it, instead of waiting for the whole
     * answer. This is the same pattern as Maya's speed (first audio
     * as soon as the first sentence exists — no separate
     * STT→text→TTS round trips to wait out). The first sentence
     * typically starts speaking a few seconds into the stream, while
     * the rest of the reply is still arriving. Sentences queue in
     * arrival order and speak one at a time through the normal voice
     * chain (so Pocket/Online/System fallback still applies per
     * sentence). All stream mutations run on the main thread
     * (onToken arrives on IO, so it posts here) — the queue is not
     * thread-safe and the speak callbacks already run on main.
     */
    private fun onStreamToken(accumulated: String) {
        mainHandler.post {
            if (!_isActive.value || !noKeyMode) return@post
            // A retry restarts accumulation from zero — drop the stale
            // queue; sentences already spoken stay spoken (rare, and
            // never a wedge).
            if (accumulated.length < streamFullText.length ||
                !accumulated.startsWith(streamFullText)
            ) {
                streamFullText = ""
                streamEnqueuedSentences = 0
                streamQueue.clear()
            }
            streamFullText = accumulated
            val sentences = splitSentences(accumulated)
            // Every sentence except a trailing incomplete one is
            // speakable now; the tail waits for more text (or the end).
            val completeCount = if (sentences.isNotEmpty() &&
                isSentenceComplete(sentences.last())
            ) sentences.size else (sentences.size - 1).coerceAtLeast(0)
            for (i in streamEnqueuedSentences until completeCount) {
                val sentence = sentences[i].trim()
                if (sentence.isNotBlank()) streamQueue.addLast(sentence)
            }
            streamEnqueuedSentences = completeCount
            pumpStream()
        }
    }

    private fun isSentenceComplete(sentence: String): Boolean {
        val t = sentence.trim()
        return t.isNotEmpty() && t.last() in ".!?۔\n"
    }

    /** Split after . ! ? Urdu full stop ۔ or a newline, keeping the terminator. */
    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?۔\\n])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Speak queued sentences one at a time; finish when the stream ends. */
    private fun pumpStream() {
        if (!_isActive.value || !noKeyMode) return
        if (streamSpeaking || streamQueue.isEmpty()) {
            if (streamDone && streamQueue.isEmpty() && !streamSpeaking) {
                resetStreamState()
                startNoKeyListening()
            }
            return
        }
        val sentence = streamQueue.removeFirst()
        streamSpeaking = true
        streamSpokenAny = true
        speakNoKey(sentence) {
            streamSpeaking = false
            pumpStream()
        }
    }

    /** Enqueue the trailing sentences of the final text (incl. a partial tail). */
    private fun enqueueStreamRemainder(fullText: String) {
        val sentences = splitSentences(fullText)
        for (i in streamEnqueuedSentences until sentences.size) {
            val sentence = sentences[i].trim()
            if (sentence.isNotBlank()) streamQueue.addLast(sentence)
        }
        streamEnqueuedSentences = sentences.size
    }

    /** Roman Urdu spoken error for a failed chat turn (never English). */
    private fun voiceErrorText(error: ChatReplyResult.Error): String {
        return when {
            error.isRateLimited ->
                "Bohat sare log ek saath use kar rahe hain — thoda wait karo, phir try karo."
            error.isNetworkError ->
                "Internet nahi lag raha — connection check kar ke dobara bolo."
            else -> "Kuch garbar ho gayi — dobara try karo."
        }
    }

    /**
     * The chat stream ended (success / error / 30s timeout). If part
     * of the reply was already spoken, its tail is spoken too and the
     * turn ends normally — a half reply beats an error. If nothing
     * was spoken yet, the failure is SPOKEN in Roman Urdu (rate-limit
     * wording for a 429) and listening restarts. Never silent, never
     * stuck on "Soch rahi hun…".
     */
    private fun finishChatTurn(result: ChatReplyResult?) {
        when (result) {
            is ChatReplyResult.Success -> {
                ConversationMemory.recordTurn(MemoryRole.ASSISTANT, result.text)
                mainHandler.post {
                    if (!_isActive.value || !noKeyMode) return@post
                    enqueueStreamRemainder(result.text)
                    streamDone = true
                    pumpStream()
                }
            }
            is ChatReplyResult.Error -> {
                if (streamSpokenAny) {
                    mainHandler.post {
                        if (!_isActive.value || !noKeyMode) return@post
                        enqueueStreamRemainder(streamFullText)
                        streamDone = true
                        pumpStream()
                    }
                } else {
                    val spoken = voiceErrorText(result)
                    ConversationMemory.recordTurn(MemoryRole.ASSISTANT, spoken)
                    speakNoKey(spoken) { startNoKeyListening() }
                }
            }
            null -> {
                // The 30s deadline hit — real now (the call is cancelled).
                if (streamSpokenAny) {
                    ConversationMemory.recordTurn(MemoryRole.ASSISTANT, streamFullText)
                    mainHandler.post {
                        if (!_isActive.value || !noKeyMode) return@post
                        enqueueStreamRemainder(streamFullText)
                        streamDone = true
                        pumpStream()
                    }
                } else {
                    speakNoKey("Free AI abhi busy hai — dobara try karo.") {
                        startNoKeyListening()
                    }
                }
            }
        }
    }

    /**
     * V21 — the Gemini leg of a no-key voice turn, shaped like
     * [PollinationsClient.chatStream] so the streaming UI works the
     * same either way. GeminiTextClient.reply is NOT a stream (it
     * returns the whole reply at once), so on success the full text
     * is fed to [onToken] in one go — the sentence pump still speaks
     * it sentence by sentence, and the first sentence still speaks
     * as soon as the reply arrives. No key stored = a plain error
     * (the voice path never asks for a key mid-conversation; the
     * user picks Pollinations in Settings instead).
     */
    private suspend fun chatStreamGemini(
        ctx: Context,
        systemInstruction: String,
        history: List<ChatTurn>,
        onToken: (String) -> Unit,
        onRetrying: ((Int) -> Unit)? = null
    ): ChatReplyResult {
        val apiKey = ApiKeyStore.getKey(ctx)
        if (apiKey.isEmpty()) {
            return ChatReplyResult.Error(
                message = "Gemini key nahi hai — Settings → Gemini API Key se key daalo, ya AI Engine wapas Pollinations par rakho.",
                isNetworkError = false,
                isRateLimited = false
            )
        }
        return when (val reply = GeminiTextClient.reply(apiKey, systemInstruction, history, onRetrying)) {
            is ChatReplyResult.Success -> {
                onToken(reply.text)
                reply
            }
            is ChatReplyResult.Error -> reply
        }
    }

    /** One recognised utterance: device command or an AI reply (Pollinations or Gemini). */
    private fun handleNoKeyTranscript(text: String) {
        if (!_isActive.value || !noKeyMode) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            startNoKeyListening()
            return
        }
        ConversationMemory.recordTurn(MemoryRole.USER, trimmed)
        val detected = LanguageDetector.detect(trimmed)
        if (detected != "UNKNOWN") {
            _detectedLanguage.value = detected
        }
        _state.value = NovaOrbState.THINKING
        _statusText.value = "Soch rahi hun…"
        // V16: the transcript arrived — the turn moves from
        // END_OF_SPEECH to THINKING.
        setTurnState(VoiceTurnState.THINKING)
        armThinkingWatchdog()

        // V15: long-term memory — same rules as text chat, spoken.
        val ctx0 = appContext
        if (ctx0 != null && FactMemoryStore.isEnabled(ctx0)) {
            val pending = pendingMemoryFact
            if (pending != null) {
                if (MemoryCapture.isYes(trimmed)) {
                    FactMemoryStore.saveFact(ctx0, pending)
                    pendingMemoryFact = null
                    ConversationMemory.recordTurn(
                        MemoryRole.ASSISTANT,
                        "Yaad rakh liya: ${pending.text}"
                    )
                    speakNoKey("Yaad rakh liya, jan! ${pending.text}") { startNoKeyListening() }
                    return
                }
                if (MemoryCapture.isNo(trimmed)) {
                    pendingMemoryFact = null
                    speakNoKey("Theek hai, yaad nahi rakhungi.") { startNoKeyListening() }
                    return
                }
                pendingMemoryFact = null
            }
            when (val capture = MemoryCapture.analyze(trimmed)) {
                is MemoryCapture.Capture.Save -> {
                    FactMemoryStore.saveFact(ctx0, capture.fact)
                    ConversationMemory.recordTurn(
                        MemoryRole.ASSISTANT,
                        "Yaad rakh liya: ${capture.fact.text}"
                    )
                    speakNoKey("Yaad rakh liya, jan! ${capture.fact.text}") { startNoKeyListening() }
                    return
                }
                is MemoryCapture.Capture.Confirm -> {
                    pendingMemoryFact = capture.fact
                    speakNoKey("Yaad rakhun? ${capture.fact.text}") { startNoKeyListening() }
                    return
                }
                is MemoryCapture.Capture.None -> {
                    // Normal message — falls through to the engine below.
                }
            }
        }

        // Device command spoken in voice mode? Run it LOCALLY right away
        // (same as the Live path), then SPEAK the confirmation.
        val command = DeviceCommandParser.parse(trimmed, AppContext.current())
        if (command != null) {
            scope.launch {
                val ctx = appContext ?: return@launch
                val reply = try {
                    DeviceActions.execute(ctx, command)
                } catch (_: Exception) {
                    "Ye kaam nahi ho saka — dobara try karo."
                }
                localActionSignature(command)?.let { signature ->
                    lastLocalActionSignature = signature
                    lastLocalActionAtMs = System.currentTimeMillis()
                }
                ConversationMemory.recordTurn(MemoryRole.ASSISTANT, reply)
                speakNoKey(reply) { startNoKeyListening() }
            }
            return
        }

        scope.launch {
            try {
                val ctx = appContext ?: return@launch
                val personality = PersonalityRepository.getPersonality(ctx)
                val language = PersonalityRepository.getLanguage(ctx)
                val assistantName = PersonalityRepository.getAssistantName(ctx)
                _currentPersonality.value = personality
                _currentLanguage.value = language
                val systemInstruction = PersonalityPromptBuilder.build(
                    personality = personality,
                    language = language,
                    detectedLanguage = _detectedLanguage.value,
                    recap = ConversationMemory.recap(),
                    assistantName = assistantName,
                    // V18: embeddings were removed with the diet — the
                    // recency block is the only memory search now.
                    memoryBlock = FactMemoryStore.memoryBlockForQuery(ctx, trimmed),
                    // V16 (P1-7): Lia's own voice beats.
                    beats = LiaVoiceBeats.section(
                        personality,
                        NovaPreferences.getString(
                            ctx,
                            NovaPreferences.Keys.USER_NAME,
                            "Friend"
                        )
                    )
                )
                val history = ConversationMemory.snapshot().map { turn ->
                    ChatTurn(isUser = turn.role == MemoryRole.USER, text = turn.text)
                }
                // V19: stream the reply and speak the FIRST sentence as
                // soon as it arrives (see onStreamToken) — never wait
                // for the whole answer. The 30s deadline is REAL now:
                // PollinationsClient's calls are cancellable (enqueue +
                // call.cancel()), so a hung request dies at 30s instead
                // of the V15 worst case (~9 minutes behind a blocking
                // OkHttp execute the timeout could never interrupt).
                // V21: the chat engine is the Settings "AI Engine"
                // choice — Pollinations (free, no key, default) or
                // Gemini (his own free key: faster, understands Roman
                // Urdu better). Gemini's reply() is not a stream, so
                // chatStreamGemini feeds the whole reply to onToken
                // at once — the first sentence still speaks as soon
                // as the reply arrives.
                resetStreamState()
                val result: ChatReplyResult? = withTimeoutOrNull(VOICE_REPLY_TIMEOUT_MS) {
                    if (EnginePreferences.useGemini(ctx)) {
                        chatStreamGemini(
                            ctx, systemInstruction, history,
                            onToken = { acc -> onStreamToken(acc) },
                            onRetrying = { attempt ->
                                _statusText.value = "Thoda wait — dobara try kar rahi hun… (koshish $attempt/3)"
                            }
                        )
                    } else {
                        PollinationsClient.chatStream(
                            systemInstruction, history,
                            onToken = { acc -> onStreamToken(acc) },
                            onRetrying = { attempt ->
                                _statusText.value = "Thoda wait — dobara try kar rahi hun… (koshish $attempt/3)"
                            },
                            retryDelaysMs = VOICE_RETRY_DELAYS_MS
                        )
                    }
                }
                finishChatTurn(result)
            } catch (e: Exception) {
                // V14 bug #1: an uncaught exception here used to kill the
                // whole scope. Now the turn fails loudly and listening
                // resumes — the user always gets an answer or an error.
                Log.w(TAG, "No-key voice turn failed", e)
                resetStreamState()
                speakNoKey("Kuch garbar ho gayi — dobara try karo.") { startNoKeyListening() }
            }
        }
    }

    /**
     * Speak [text] with Lia's voice, then run [onDone]. The voice
     * chain (see [speakVoiceChain]) tries the user's chosen voice
     * first and falls back through the other engines, so one dead
     * engine can never wedge the turn on "speaking" or swallow the
     * reply. The recognizer stays off while speaking so Lia never
     * hears herself.
     */
    private fun speakNoKey(text: String, onDone: (() -> Unit)? = null) {
        // A reply arrived — the turn is over, watchdog off.
        disarmThinkingWatchdog()
        val ctx = appContext
        if (ctx == null) {
            // No app context (should not happen mid-session): show the
            // reply as text so it is never lost, then continue.
            _statusText.value = text.take(200)
            _state.value = NovaOrbState.LISTENING
            setTurnState(VoiceTurnState.LISTENING)
            onDone?.invoke()
            return
        }
        speakVoiceChain(ctx, VoiceOutputPreferences.resolveOrder(ctx), 0, text, onDone)
    }

    /**
     * Walk the voice chain for one reply. [index] is the engine to
     * try now; on failure the next engine is tried, and when the
     * chain is exhausted the reply shows as text. [onDone] fires
     * exactly once, at the end of whichever path speaks (or shows).
     */
    private fun speakVoiceChain(
        ctx: Context,
        order: List<VoiceOutputEngine>,
        index: Int,
        text: String,
        onDone: (() -> Unit)?
    ) {
        if (index >= order.size) {
            _statusText.value = text.take(200)
            _state.value = NovaOrbState.LISTENING
            setTurnState(VoiceTurnState.LISTENING)
            onDone?.invoke()
            return
        }
        when (order[index]) {
            VoiceOutputEngine.HINDI -> speakHindiNoKey(ctx, text, onDone) {
                speakVoiceChain(ctx, order, index + 1, text, onDone)
            }
            VoiceOutputEngine.POCKET -> speakPocketNoKey(ctx, text, onDone) {
                speakVoiceChain(ctx, order, index + 1, text, onDone)
            }
            VoiceOutputEngine.ONLINE -> speakOnlineNoKey(ctx, text, onDone) {
                speakVoiceChain(ctx, order, index + 1, text, onDone)
            }
            VoiceOutputEngine.SYSTEM -> speakSystemNoKey(text, onDone)
        }
    }

    /**
     * V21 — speak one reply with the on-device Hindi voice
     * (Supertonic 3). Same shape as [speakPocketNoKey]: the engine
     * is created once per session and cached (loading ~139 MB of
     * ONNX models is expensive); a failed speak drops the cached
     * engine so the next attempt rebuilds it. A 30s watchdog bounds
     * native generation: on timeout the engine is stopped and the
     * chain moves on. When the model is not downloaded, [onFallback]
     * runs at once — the chain never waits on a missing engine.
     * Roman Urdu is transliterated to Devanagari inside the engine
     * (see HindiTtsEngine) — the Hindi voice needs Devanagari text.
     */
    private fun speakHindiNoKey(
        ctx: Context,
        text: String,
        onDone: (() -> Unit)? = null,
        onFallback: () -> Unit
    ) {
        if (!HindiTtsEngine.isReady(ctx)) {
            onFallback()
            return
        }
        noKeySpeaking = true
        _state.value = NovaOrbState.SPEAKING
        setTurnState(VoiceTurnState.SPEAKING)
        _statusText.value = "Lia bol rahi hai…"
        _amplitude.value = 0.4f
        scope.launch {
            val engine = getOrCreateHindiTts(ctx)
            if (engine == null) {
                mainHandler.post {
                    noKeySpeaking = false
                    _amplitude.value = 0f
                    onFallback()
                }
                return@launch
            }
            var done = false
            val finishOk = {
                if (!done) {
                    done = true
                    noKeySpeaking = false
                    _amplitude.value = 0f
                    _state.value = NovaOrbState.LISTENING
                    setTurnState(VoiceTurnState.LISTENING)
                    onDone?.invoke()
                }
            }
            val finishFail = {
                if (!done) {
                    done = true
                    Log.w(TAG, "Hindi TTS failed — falling back")
                    releaseHindiTts()
                    noKeySpeaking = false
                    _amplitude.value = 0f
                    onFallback()
                }
            }
            val watchdog = Runnable { finishFail() }
            mainHandler.postDelayed(watchdog, HINDI_SPEAK_TIMEOUT_MS)
            engine.speak(
                text,
                onDone = {
                    mainHandler.removeCallbacks(watchdog)
                    finishOk()
                },
                onError = {
                    mainHandler.removeCallbacks(watchdog)
                    finishFail()
                }
            )
        }
    }

    /** The cached Hindi TTS engine (created once per session). */
    private fun getOrCreateHindiTts(ctx: Context): HindiTtsEngine? {
        hindiTts?.let { return it }
        val engine = HindiTtsEngine.create(ctx) ?: return null
        hindiTts = engine
        return engine
    }

    private fun releaseHindiTts() {
        runCatching { hindiTts?.release() }
        hindiTts = null
    }

    /**
     * V19 — speak one reply with the on-device Lia voice (Pocket TTS).
     * The engine is created once per session and cached (loading ~191
     * MB of ONNX models is expensive); a failed speak drops the cached
     * engine so the next attempt rebuilds it. A 30s watchdog bounds
     * native generation: on timeout the engine is stopped and the
     * chain moves on. When the model is not downloaded, [onFallback]
     * runs at once — the chain never waits on a missing engine.
     */
    private fun speakPocketNoKey(
        ctx: Context,
        text: String,
        onDone: (() -> Unit)? = null,
        onFallback: () -> Unit
    ) {
        if (!PocketTtsEngine.isReady(ctx)) {
            onFallback()
            return
        }
        noKeySpeaking = true
        _state.value = NovaOrbState.SPEAKING
        setTurnState(VoiceTurnState.SPEAKING)
        _statusText.value = "Lia bol rahi hai…"
        _amplitude.value = 0.4f
        scope.launch {
            val engine = getOrCreatePocketTts(ctx)
            if (engine == null) {
                mainHandler.post {
                    noKeySpeaking = false
                    _amplitude.value = 0f
                    onFallback()
                }
                return@launch
            }
            var done = false
            val finishOk = {
                if (!done) {
                    done = true
                    noKeySpeaking = false
                    _amplitude.value = 0f
                    _state.value = NovaOrbState.LISTENING
                    setTurnState(VoiceTurnState.LISTENING)
                    onDone?.invoke()
                }
            }
            val finishFail = {
                if (!done) {
                    done = true
                    Log.w(TAG, "Pocket TTS failed — falling back")
                    releasePocketTts()
                    noKeySpeaking = false
                    _amplitude.value = 0f
                    onFallback()
                }
            }
            val watchdog = Runnable { finishFail() }
            mainHandler.postDelayed(watchdog, POCKET_SPEAK_TIMEOUT_MS)
            engine.speak(
                text,
                onDone = {
                    mainHandler.removeCallbacks(watchdog)
                    finishOk()
                },
                onError = {
                    mainHandler.removeCallbacks(watchdog)
                    finishFail()
                }
            )
        }
    }

    /** The cached Pocket engine for the user's picked voice (lia/loona). */
    private fun getOrCreatePocketTts(ctx: Context): PocketTtsEngine? {
        val voiceId = PocketTtsVoicePreferences.getVoiceId(ctx)
        val cached = pocketTts
        if (cached != null && pocketTtsVoiceId == voiceId) return cached
        releasePocketTts()
        val engine = PocketTtsEngine.create(ctx, voiceId) ?: return null
        pocketTts = engine
        pocketTtsVoiceId = voiceId
        return engine
    }

    private fun releasePocketTts() {
        runCatching { pocketTts?.release() }
        pocketTts = null
        pocketTtsVoiceId = null
    }

    /**
     * V17 (online voice) — speak one reply with the internet voice
     * (Kokoro via mesmer.tools, no key). On any failure (no
     * internet, 20/hour quota, service down) [onFallback] runs so
     * the next engine in the chain speaks instead.
     */
    private fun speakOnlineNoKey(
        ctx: Context,
        text: String,
        onDone: (() -> Unit)? = null,
        onFallback: () -> Unit
    ) {
        noKeySpeaking = true
        _state.value = NovaOrbState.SPEAKING
        setTurnState(VoiceTurnState.SPEAKING)
        _statusText.value = "Lia bol rahi hai…"
        _amplitude.value = 0.4f
        OnlineTtsEngine.speak(
            ctx,
            scope,
            text,
            onDone = {
                noKeySpeaking = false
                _amplitude.value = 0f
                _state.value = NovaOrbState.LISTENING
                setTurnState(VoiceTurnState.LISTENING)
                onDone?.invoke()
            },
            onError = { error ->
                Log.w(TAG, "Online voice failed (${error.kind}) — falling back: ${error.message}")
                noKeySpeaking = false
                _amplitude.value = 0f
                onFallback()
            }
        )
    }

    /**
     * Speak [text] with the cute system TTS voice, then run [onDone].
     * The recognizer stays off while speaking so Lia never hears herself.
     *
     * V15 (voice-bug fix #3): the old code ignored `speak()`'s return
     * value — on ERROR no utterance callback ever fired, `noKeySpeaking`
     * stayed true forever, and listening never restarted. Now an ERROR
     * return shows the reply as status text so the user can READ it,
     * resets the speaking flag, and goes straight to [onDone].
     *
     * V19 (S9 fix): the old code also used the one TTS instance from
     * session start and, if its init had failed, silently showed every
     * later reply as text with no retry and no notice. Now the TTS is
     * (re-)created on demand via [ensureSystemTts] — a dead init is
     * retried once — and only a TTS that cannot be created at all
     * falls back to text.
     */
    private fun speakSystemNoKey(text: String, onDone: (() -> Unit)? = null) {
        ensureSystemTts { tts ->
            if (tts == null) {
                // TTS cannot speak: show the reply so it is not lost.
                _statusText.value = text.take(200)
                _state.value = NovaOrbState.LISTENING
                setTurnState(VoiceTurnState.LISTENING)
                onDone?.invoke()
                return@ensureSystemTts
            }
            speakWithSystemTts(tts, text, onDone)
        }
    }

    private fun speakWithSystemTts(
        tts: TextToSpeech,
        text: String,
        onDone: (() -> Unit)? = null
    ) {
        noKeySpeaking = true
        _state.value = NovaOrbState.SPEAKING
        setTurnState(VoiceTurnState.SPEAKING)
        _statusText.value = "Lia bol rahi hai…"
        _amplitude.value = 0.4f
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                noKeySpeaking = false
                _amplitude.value = 0f
                _state.value = NovaOrbState.LISTENING
                setTurnState(VoiceTurnState.LISTENING)
                onDone?.invoke()
            }

            override fun onError(utteranceId: String?) {
                noKeySpeaking = false
                _amplitude.value = 0f
                setTurnState(VoiceTurnState.LISTENING)
                onDone?.invoke()
            }
        })
        val speakResult = tts.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lia-nokey-${System.currentTimeMillis()}"
        )
        if (speakResult == TextToSpeech.ERROR) {
            // The engine refused the utterance: no callback will ever
            // fire, so recover here instead of wedging on "speaking".
            Log.w(TAG, "No-key TTS speak() returned ERROR — showing reply as text")
            noKeySpeaking = false
            _amplitude.value = 0f
            _statusText.value = text.take(200)
            _state.value = NovaOrbState.LISTENING
            setTurnState(VoiceTurnState.LISTENING)
            onDone?.invoke()
        }
    }

    private suspend fun connectSession(isResuming: Boolean) {
        val currentContext = appContext ?: return
        val currentEpoch = sessionEpoch.incrementAndGet()

        client?.disconnect()
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = if (isResuming) "Reconnecting..." else "Connecting..."

        val apiKey = ApiKeyStore.getKey(currentContext)
        if (apiKey.isEmpty()) {
            _state.value = NovaOrbState.ERROR
            _statusText.value = "API key missing. Enter key in Settings."
            _lastError.value = "Missing Gemini API Key"
            return
        }

        val personality = PersonalityRepository.getPersonality(currentContext)
        val language = PersonalityRepository.getLanguage(currentContext)
        val assistantName = PersonalityRepository.getAssistantName(currentContext)
        // A personality may bring its own voice (e.g. Girlfriend uses Sulafat,
        // the warmest Live voice); otherwise use the user's voice setting.
        val voiceName = personality.voiceName
            ?: PersonalityRepository.getVoiceName(currentContext)

        _currentPersonality.value = personality
        _currentLanguage.value = language

        val recap = if (isResuming) ConversationMemory.recap() else null
        val prompt = PersonalityPromptBuilder.build(
            personality = personality,
            language = language,
            detectedLanguage = _detectedLanguage.value,
            recap = recap,
            assistantName = assistantName,
            // V15: long-term memory, same block as the no-key path.
            memoryBlock = FactMemoryStore.memoryBlock(currentContext),
            // V16 (P1-7): Lia's own voice beats.
            beats = LiaVoiceBeats.section(
                personality,
                NovaPreferences.getString(
                    currentContext,
                    NovaPreferences.Keys.USER_NAME,
                    "Friend"
                )
            )
        )

        val liveListener = object : GeminiLiveClient.Listener {
            override fun onSetupComplete() {
                if (sessionEpoch.get() != currentEpoch) return
                disarmConnectWatchdog()
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                VoiceLatencyTracker.resetReconnect()
                audioIO?.startRecording()
            }

            override fun onAudioChunk(bytes: ByteArray) {
                if (sessionEpoch.get() != currentEpoch) return
                VoiceLatencyTracker.markFirstAudioChunk()

                if (!isTurnActive) {
                    isTurnActive = true
                    EchoGuard.notifyPlaybackStarted()
                }

                _state.value = NovaOrbState.SPEAKING
                setTurnState(VoiceTurnState.SPEAKING)
                _statusText.value = "$assistantName is speaking..."
                val amp = audioIO?.playChunk(bytes) ?: 0f
                _amplitude.value = amp

                // Re-arm 300 ms silence watchdog
                silenceWatchdogJob?.cancel()
                silenceWatchdogJob = scope.launch {
                    delay(300)
                    if (sessionEpoch.get() == currentEpoch && isTurnActive) {
                        isTurnActive = false
                        EchoGuard.notifyPlaybackStopped()
                        _state.value = NovaOrbState.LISTENING
                        setTurnState(VoiceTurnState.LISTENING)
                        _statusText.value = "Listening..."
                        _amplitude.value = 0f
                        SpeechLevelTracker.reset()
                    }
                }
            }

            override fun onInputTranscript(text: String) {
                if (sessionEpoch.get() != currentEpoch) return
                VoiceLatencyTracker.markUserTurnStart()
                ConversationMemory.recordTurn(MemoryRole.USER, text)
                val detected = LanguageDetector.detect(text)
                if (detected != "UNKNOWN") {
                    _detectedLanguage.value = detected
                }

                // Device command spoken in voice mode? Run it LOCALLY right
                // away instead of waiting on Gemini over a slow network,
                // then make Lia SPEAK the confirmation out loud — otherwise
                // she goes silent after opening another app. Gemini hears
                // the same words and replies conversationally; the guard in
                // onToolCall stops its own open_app/call_contact/
                // message_contact from running the same action a second time.
                DeviceCommandParser.parse(text, AppContext.current())?.let { command ->
                    scope.launch {
                        try {
                            val reply = DeviceActions.execute(currentContext, command)
                            localActionSignature(command)?.let { signature ->
                                lastLocalActionSignature = signature
                                lastLocalActionAtMs = System.currentTimeMillis()
                            }
                            Log.d(TAG, "Local device action from voice: $command -> $reply")
                            speakActionConfirmation(reply)
                        } catch (e: Exception) {
                            Log.w(TAG, "Local device action failed: $command", e)
                        }
                    }
                }
            }

            override fun onOutputTranscript(text: String) {
                if (sessionEpoch.get() != currentEpoch) return
                ConversationMemory.recordTurn(MemoryRole.ASSISTANT, text)
            }

            override fun onToolCall(id: String, name: String, args: JSONObject) {
                if (sessionEpoch.get() != currentEpoch) return
                _state.value = NovaOrbState.THINKING
                _statusText.value = "Working on it..."

                // Already executed locally from the voice transcript (see
                // onInputTranscript)? Tell Gemini it is done, do not run twice.
                if (wasHandledLocallyRecently(name, args)) {
                    val resp = JSONObject().apply {
                        put("result", "already_done_locally")
                        put("status", "success")
                    }
                    client?.sendToolResponse(id, name, resp)
                    _state.value = NovaOrbState.LISTENING
                    _statusText.value = "Listening..."
                    return
                }

                scope.launch {
                    val result = ActionExecutor.execute(currentContext, name, args)
                    if (sessionEpoch.get() == currentEpoch) {
                        client?.sendToolResponse(id, name, result)
                        _state.value = NovaOrbState.LISTENING
                        _statusText.value = "Listening..."
                    }
                }
            }

            override fun onTurnComplete() {
                if (sessionEpoch.get() != currentEpoch) return
                silenceWatchdogJob?.cancel()
                if (isTurnActive) {
                    isTurnActive = false
                    EchoGuard.notifyPlaybackStopped()
                }
                audioIO?.flushPlayback()
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                _amplitude.value = 0f
                SpeechLevelTracker.reset()
                VoiceLatencyTracker.markTurnComplete()
            }

            override fun onInterrupted() {
                if (sessionEpoch.get() != currentEpoch) return
                silenceWatchdogJob?.cancel()
                if (isTurnActive) {
                    isTurnActive = false
                    EchoGuard.notifyPlaybackStopped()
                }
                audioIO?.flushPlayback()
                _state.value = NovaOrbState.LISTENING
                _statusText.value = "Listening..."
                _amplitude.value = 0f
                SpeechLevelTracker.reset()
            }

            override fun onError(message: String) {
                if (sessionEpoch.get() != currentEpoch) return
                disarmConnectWatchdog()
                _lastError.value = message
                if (_isActive.value) {
                    scheduleReconnect()
                }
            }

            override fun onClosed() {
                if (sessionEpoch.get() != currentEpoch) return
                disarmConnectWatchdog()
                if (_isActive.value) {
                    scheduleReconnect()
                }
            }
        }

        client = GeminiLiveClient(liveListener)
        client?.connect(apiKey, prompt, voiceName)
        // V19 (S6): a connect that never completes must not sit on
        // "Connecting..." forever — the watchdog speaks + reconnects.
        armConnectWatchdog()
    }

    /**
     * Auto-reconnect delays between attempts: 2s, 5s, 10s, 20s, 30s (5 attempts).
     * The user's network is often only 3-20 K/s, so the Live voice session
     * drops a lot. The old 3-attempt schedule gave up after ~17s; five
     * attempts with growing delays cover ~65s and give a slow connection
     * time to recover before we ask the user to use text chat instead.
     */
    private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

    private fun scheduleReconnect() {
        if (!_isActive.value) return
        if (reconnectJob?.isActive == true) return

        val attempt = VoiceLatencyTracker.reconnectCount.value
        if (attempt >= RECONNECT_DELAYS_MS.size) {
            _state.value = NovaOrbState.ERROR
            _statusText.value = "Net bohat slow hai, voice connect nahi ho rahi. " +
                "Text chat mein likh lo — wahan se bhi baat ho jayegi."
            _lastError.value = "Connection lost after ${RECONNECT_DELAYS_MS.size} attempts"
            // V19 (S6): the final failure is SPOKEN too, not just text.
            speakErrorAloud(_statusText.value) {
                _state.value = NovaOrbState.ERROR
                setTurnState(VoiceTurnState.ERROR)
            }
            return
        }

        VoiceLatencyTracker.incrementReconnect()
        _state.value = NovaOrbState.CONNECTING
        _statusText.value = "Net slow hai, dobara connect kar rahi hun… " +
            "(koshish ${attempt + 1}/${RECONNECT_DELAYS_MS.size})"

        reconnectJob = scope.launch {
            delay(RECONNECT_DELAYS_MS[attempt])
            sessionMutex.withLock {
                if (_isActive.value) {
                    connectSession(isResuming = true)
                }
            }
        }
    }

    fun refreshInstructions(context: Context) {
        if (!_isActive.value) return
        // V12: no-key turns read the personality fresh every time, so
        // there is nothing to reconnect — the next reply just uses it.
        if (noKeyMode) return
        appContext = context.applicationContext
        scope.launch {
            sessionMutex.withLock {
                if (_isActive.value) {
                    connectSession(isResuming = true)
                }
            }
        }
    }

    fun toggleMute() {
        val next = !_muted.value
        _muted.value = next
        _statusText.value = if (next) "Microphone muted" else "Listening..."
        if (noKeyMode) {
            if (next) {
                stopNoKeyListening()
                try {
                    noKeyTts?.stop()
                } catch (_: Exception) {
                }
                // V19 (S10): mute stops every voice, not just system
                // TTS — the online voice and Pocket kept playing.
                OnlineTtsEngine.stop()
                try {
                    pocketTts?.stop()
                } catch (_: Exception) {
                }
                // V21: mute stops the Hindi voice too.
                try {
                    hindiTts?.stop()
                } catch (_: Exception) {
                }
            } else if (_isActive.value) {
                startNoKeyListening()
            }
        }
    }

    fun stop() {
        _isActive.value = false
        sessionEpoch.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        // V15: the thinking watchdog belongs to this session only.
        disarmThinkingWatchdog()
        disarmListeningWatchdog()
        disarmConnectWatchdog()
        resetStreamState()
        sttFailureStrikes = 0
        pendingMemoryFact = null
        // V12 no-key cleanup: cancel + destroy the recognizer and stop +
        // shut down TTS, so nothing keeps the mic after the session ends
        // — the same guarantee as the Live path below.
        stopNoKeyListening()
        // V19 (S10): stop EVERY voice engine, not just system TTS — the
        // old code never stopped the online voice, so a Kokoro MP3 kept
        // playing after the session ended. Pocket's model is released.
        // V21: the Hindi voice and the Shruti recognizer release too.
        OnlineTtsEngine.stop()
        releasePocketTts()
        releaseHindiTts()
        releaseShrutiStt()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        try {
            noKeyTts?.stop()
            noKeyTts?.shutdown()
        } catch (_: Exception) {
        }
        noKeyTts = null
        noKeyTtsReady = false
        noKeyMode = false
        noKeySpeaking = false

        client?.disconnect()
        client = null

        // Full mic release: AudioRecord stopped, audio focus abandoned.
        // Nothing keeps capturing in the background, so other apps
        // (e.g. WhatsApp voice notes) get a working mic again.
        audioIO?.release()
        audioIO = null
        Log.d(TAG, "Voice stopped: mic released, audio focus abandoned")

        // V13: save this voice conversation to History before the
        // in-memory turns are cleared below, so it survives restarts.
        saveVoiceSessionToHistory()

        EchoGuard.reset()
        ConversationMemory.clear()

        isTurnActive = false
        _state.value = NovaOrbState.IDLE
        setTurnState(VoiceTurnState.IDLE)
        _statusText.value = "Ready to talk"
        _amplitude.value = 0f
        SpeechLevelTracker.reset()
        _vadActive.value = false
        _muted.value = false
    }

    /**
     * V13: persist the voice conversation that just ended, so it shows
     * up in History like a text chat. Only saves when the user actually
     * said something — a session that never got a word saves nothing.
     * SharedPreferences apply() is async, so this never delays the mic
     * release above.
     */
    private fun saveVoiceSessionToHistory() {
        val ctx = appContext ?: return
        val turns = ConversationMemory.snapshot()
        if (turns.none { it.role == MemoryRole.USER }) return
        val firstUser = turns.firstOrNull { it.role == MemoryRole.USER }?.text ?: return
        val lastText = turns.lastOrNull()?.text.orEmpty()
        ChatHistoryStore.saveSession(
            ctx,
            ChatSession(
                title = firstUser.take(40),
                preview = lastText.take(80),
                personality = _currentPersonality.value.displayName,
                messages = turns.map {
                    ChatHistoryMessage(
                        role = if (it.role == MemoryRole.USER) "user" else "assistant",
                        text = it.text
                    )
                }
            )
        )
    }
}
