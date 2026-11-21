package com.Lia.assistant.voice

import android.content.Context
import android.util.Log
import com.Lia.assistant.action.ActionExecutor
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
        else -> null
    }

    private fun wasHandledLocallyRecently(toolName: String, args: JSONObject): Boolean {
        val signature = lastLocalActionSignature ?: return false
        if (System.currentTimeMillis() - lastLocalActionAtMs > 20_000L) return false
        return when (toolName) {
            "open_app" -> signature == "open_app:${args.optString("app_name", "").lowercase()}"
            "call_contact" -> signature == "call_contact:${args.optString("contact_name", "").lowercase()}"
            else -> false
        }
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
                // away instead of waiting on Gemini over a slow network.
                // Gemini hears the same words and replies conversationally;
                // the guard in onToolCall stops its own open_app/call_contact
                // from running the same action a second time.
                DeviceCommandParser.parse(text)?.let { command ->
                    scope.launch {
                        try {
                            val reply = DeviceActions.execute(currentContext, command)
                            localActionSignature(command)?.let { signature ->
                                lastLocalActionSignature = signature
                                lastLocalActionAtMs = System.currentTimeMillis()
                            }
                            Log.d(TAG, "Local device action from voice: $command -> $reply")
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
    }

    fun stop() {
        _isActive.value = false
        sessionEpoch.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null

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
        _vadActive.value = false
        _muted.value = false
    }
}
