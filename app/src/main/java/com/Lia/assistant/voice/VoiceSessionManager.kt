package com.Lia.assistant.voice

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
import com.Lia.assistant.action.ActionExecutor
import com.Lia.assistant.action.AppContext
import com.Lia.assistant.action.DeviceActions
import com.Lia.assistant.action.DeviceCommand
import com.Lia.assistant.action.DeviceCommandParser
import com.Lia.assistant.action.InstalledAppLabelCache
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.ChatHistoryMessage
import com.Lia.assistant.data.ChatHistoryStore
import com.Lia.assistant.data.ChatSession
import com.Lia.assistant.data.DeviceTtsVoicePreferences
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.MemoryCapture
import com.Lia.assistant.data.MemoryFact
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.VoiceOutputEngine
import com.Lia.assistant.data.VoiceOutputPreferences
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.PersonalityRepository
import com.Lia.assistant.forge.ForgeController
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
     */
    private const val THINKING_WATCHDOG_MS = 25_000L

    /** V15: deadline for one voice round-trip (see handleNoKeyTranscript). */
    private const val VOICE_REPLY_TIMEOUT_MS = 20_000L

    /** V15: short retry waits for voice (text chat uses 2s/5s/10s). */
    private val VOICE_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)

    private val thinkingWatchdogRunnable = Runnable {
        if (!_isActive.value || !noKeyMode) return@Runnable
        if (_state.value != NovaOrbState.THINKING) return@Runnable
        // Still thinking after 25s — the turn died somewhere. Say so
        // plainly and go back to listening instead of hanging forever.
        Log.w(TAG, "Thinking watchdog fired — voice turn never completed")
        _statusText.value = "Zyada der ho gayi — dobara bolo."
        _state.value = NovaOrbState.LISTENING
        startNoKeyListening()
    }

    private fun armThinkingWatchdog() {
        mainHandler.removeCallbacks(thinkingWatchdogRunnable)
        mainHandler.postDelayed(thinkingWatchdogRunnable, THINKING_WATCHDOG_MS)
    }

    private fun disarmThinkingWatchdog() {
        mainHandler.removeCallbacks(thinkingWatchdogRunnable)
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
    // Pollinations chat for the brain, system TextToSpeech (cute voice)
    // for output. No key, no network service for the voice itself.
    private var noKeyMode = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var noKeyTts: TextToSpeech? = null
    private var noKeyTtsReady = false

    private var noKeyListening = false
    private var noKeySpeaking = false
    private var noKeyRestartJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
    // chat), and the reply is spoken with the system TTS cute voice.
    // Then listening restarts automatically — tap-to-talk, no wake word.
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
        if (!_isActive.value || !noKeyMode || _muted.value || noKeySpeaking) return
        mainHandler.post {
            if (!_isActive.value || !noKeyMode || _muted.value || noKeySpeaking) return@post
            val ctx = appContext ?: return@post
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
            } catch (e: Exception) {
                Log.w(TAG, "No-key listening failed", e)
                noKeyListening = false
            }
        }
    }

    private fun stopNoKeyListening() {
        noKeyRestartJob?.cancel()
        noKeyRestartJob = null
        if (!noKeyListening) return
        noKeyListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
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
            _state.value = NovaOrbState.LISTENING
            _statusText.value = "Sun rahi hun…"
            setTurnState(VoiceTurnState.SPEECH_DETECTED)
        }

        override fun onRmsChanged(rmsdB: Float) {
            _amplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
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
                    setTurnState(VoiceTurnState.IDLE)
                    scheduleNoKeyRestart(1_500L)
                }
                else -> {
                    setTurnState(VoiceTurnState.IDLE)
                    scheduleNoKeyRestart(800L)
                }
            }
        }

        override fun onResults(results: Bundle?) {
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

    /** One recognised utterance: device command or a Pollinations reply. */
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
                // V18: online only — the offline model was removed in
                // the diet. Pollinations answers every voice turn.
                // 20s deadline for the whole round-trip: a hung request
                // must never wedge the overlay (V14 bug #2).
                val result: ChatReplyResult? = withTimeoutOrNull(VOICE_REPLY_TIMEOUT_MS) {
                    PollinationsClient.chat(
                        systemInstruction, history,
                        onRetrying = { attempt ->
                            _statusText.value = "Thoda wait — dobara try kar rahi hun… (koshish $attempt/3)"
                        },
                        retryDelaysMs = VOICE_RETRY_DELAYS_MS
                    )
                }

                when (val finalResult = result) {
                    is ChatReplyResult.Success -> {
                        ConversationMemory.recordTurn(MemoryRole.ASSISTANT, finalResult.text)
                        speakNoKey(finalResult.text) { startNoKeyListening() }
                    }
                    is ChatReplyResult.Error -> {
                        ConversationMemory.recordTurn(MemoryRole.ASSISTANT, finalResult.message)
                        speakNoKey(finalResult.message) { startNoKeyListening() }
                    }
                    null -> {
                        // The 20s deadline hit — say so and listen again.
                        speakNoKey("Free AI abhi busy hai — dobara try karo.") { startNoKeyListening() }
                    }
                }
            } catch (e: Exception) {
                // V14 bug #1: an uncaught exception here used to kill the
                // whole scope. Now the turn fails loudly and listening
                // resumes — the user always gets an answer or an error.
                Log.w(TAG, "No-key voice turn failed", e)
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
            VoiceOutputEngine.ONLINE -> speakOnlineNoKey(ctx, text, onDone) {
                speakVoiceChain(ctx, order, index + 1, text, onDone)
            }
            VoiceOutputEngine.SYSTEM -> speakSystemNoKey(text, onDone)
        }
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
     * return (or a TTS that is not ready yet) shows the reply as status
     * text so the user can READ it, resets the speaking flag, and goes
     * straight to [onDone]. A reply is never silently swallowed again —
     * including one that arrives before TTS init finishes.
     */
    private fun speakSystemNoKey(text: String, onDone: (() -> Unit)? = null) {
        val tts = noKeyTts
        if (tts == null || !noKeyTtsReady) {
            // TTS cannot speak: show the reply so it is not lost.
            _statusText.value = text.take(200)
            _state.value = NovaOrbState.LISTENING
            setTurnState(VoiceTurnState.LISTENING)
            onDone?.invoke()
            return
        }
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

                if (name == "build_website") {
                    val forgePrompt = args.optString("prompt", "")
                    ForgeController.start(currentContext, forgePrompt)
                    val resp = JSONObject().apply { put("result", "forge_started") }
                    client?.sendToolResponse(id, name, resp)
                    _state.value = NovaOrbState.LISTENING
                    return
                }

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
                _lastError.value = message
                if (_isActive.value) {
                    scheduleReconnect()
                }
            }

            override fun onClosed() {
                if (sessionEpoch.get() != currentEpoch) return
                if (_isActive.value) {
                    scheduleReconnect()
                }
            }
        }

        client = GeminiLiveClient(liveListener)
        client?.connect(apiKey, prompt, voiceName)
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
        pendingMemoryFact = null
        // V12 no-key cleanup: cancel + destroy the recognizer and stop +
        // shut down TTS, so nothing keeps the mic after the session ends
        // — the same guarantee as the Live path below.
        stopNoKeyListening()
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
