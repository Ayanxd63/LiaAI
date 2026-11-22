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
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.PersonalityRepository
import com.Lia.assistant.forge.ForgeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object VoiceSessionManager {
    private const val TAG = "VoiceSessionManager"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val sessionEpoch = AtomicLong(0)

    private val _state = MutableStateFlow(NovaOrbState.IDLE)
    val state: StateFlow<NovaOrbState> = _state.asStateFlow()

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
        is DeviceCommand.WhatsAppMessage -> "message_contact:${command.target.lowercase()}"
        else -> null
    }

    private fun wasHandledLocallyRecently(toolName: String, args: JSONObject): Boolean {
        val signature = lastLocalActionSignature ?: return false
        if (System.currentTimeMillis() - lastLocalActionAtMs > 20_000L) return false
        return when (toolName) {
            "open_app" -> signature == "open_app:${args.optString("app_name", "").lowercase()}"
            "call_contact" -> signature == "call_contact:${args.optString("contact_name", "").lowercase()}"
            "message_contact" -> signature == "message_contact:${args.optString("contact_name", "").lowercase()}"
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
        noKeyTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                noKeyTtsReady = true
                noKeyTts?.let { CuteVoice.applyCuteVoice(it) }
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
        }

        override fun onBeginningOfSpeech() {
            _state.value = NovaOrbState.LISTENING
            _statusText.value = "Sun rahi hun…"
        }

        override fun onRmsChanged(rmsdB: Float) {
            _amplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            noKeyListening = false
            _state.value = NovaOrbState.THINKING
            _statusText.value = "Soch rahi hun…"
        }

        override fun onError(error: Int) {
            noKeyListening = false
            _amplitude.value = 0f
            if (!_isActive.value || !noKeyMode || _muted.value) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _state.value = NovaOrbState.ERROR
                    _statusText.value = "Microphone permission chahiye — Settings se allow karo."
                    _lastError.value = "Microphone permission missing"
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Quiet room — listen again after a short pause.
                    scheduleNoKeyRestart(1_500L)
                }
                else -> scheduleNoKeyRestart(800L)
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
                assistantName = assistantName
            )
            val history = ConversationMemory.snapshot().map { turn ->
                ChatTurn(isUser = turn.role == MemoryRole.USER, text = turn.text)
            }
            val result = PollinationsClient.chat(systemInstruction, history) { attempt ->
                _statusText.value = "Thoda wait — dobara try kar rahi hun… (koshish $attempt/3)"
            }
            when (result) {
                is ChatReplyResult.Success -> {
                    ConversationMemory.recordTurn(MemoryRole.ASSISTANT, result.text)
                    speakNoKey(result.text) { startNoKeyListening() }
                }
                is ChatReplyResult.Error -> {
                    ConversationMemory.recordTurn(MemoryRole.ASSISTANT, result.message)
                    speakNoKey(result.message) { startNoKeyListening() }
                }
            }
        }
    }

    /**
     * Speak [text] with the cute system TTS voice, then run [onDone].
     * The recognizer stays off while speaking so Lia never hears herself.
     * If TTS is not ready yet, skip speaking and go straight to [onDone]
     * — the reply is still in memory, and listening resumes.
     */
    private fun speakNoKey(text: String, onDone: (() -> Unit)? = null) {
        val tts = noKeyTts
        if (tts == null || !noKeyTtsReady) {
            onDone?.invoke()
            return
        }
        noKeySpeaking = true
        _state.value = NovaOrbState.SPEAKING
        _statusText.value = "Lia bol rahi hai…"
        _amplitude.value = 0.4f
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                noKeySpeaking = false
                _amplitude.value = 0f
                _state.value = NovaOrbState.LISTENING
                onDone?.invoke()
            }

            override fun onError(utteranceId: String?) {
                noKeySpeaking = false
                _amplitude.value = 0f
                onDone?.invoke()
            }
        })
        tts.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "lia-nokey-${System.currentTimeMillis()}"
        )
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
            assistantName = assistantName
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

        EchoGuard.reset()
        ConversationMemory.clear()

        isTurnActive = false
        _state.value = NovaOrbState.IDLE
        _statusText.value = "Ready to talk"
        _amplitude.value = 0f
        SpeechLevelTracker.reset()
        _vadActive.value = false
        _muted.value = false
    }
}
