package com.Lia.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.Lia.assistant.data.DeviceTtsVoicePreferences
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.PocketTtsVoicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * V19 — spoken first-run welcome.
 *
 * On first launch (after onboarding), Lia SPEAKS a short welcome out
 * loud, automatically, once — Lia's own words (the V13 GF welcome
 * text), never Maya's ("Welcome boss…" is Maya's line; Lia speaks as
 * Lia).
 *
 * Voice (Maya-study correction, ~/workspace/lia-v19-maya-study-2.md):
 * Maya's welcome plays BEFORE any key exists — on the key screen
 * itself, while that screen says without a key she can neither hear
 * nor answer. So the welcome CANNOT be Gemini Live; it must be the
 * on-device voice. Lia's welcome therefore prefers HINDI TTS
 * (Supertonic 3, V21 — the native Hindi voice) when its model is
 * downloaded, then POCKET TTS, and falls back through the chain
 * (ONLINE Kokoro → SYSTEM) only when neither is downloaded yet. No
 * Gemini key is ever required for the welcome.
 *
 * Never-stuck rules, same as the voice session: every engine has a
 * hard timeout (Hindi 30s, Pocket 30s, Online 20s synthesis + 60s overall
 * backstop, system TTS 3s init + 15s speak); [onDone] fires exactly
 * once whether the welcome spoke or every engine failed (the text
 * stays visible on the card meanwhile); [stop] ends all speech at
 * once. Speaking never blocks the UI — the card's Skip button works
 * immediately, and Home's hero/Ask buttons dismiss the welcome first
 * so it never overlaps a voice session.
 */
object WelcomeSpeaker {

    private const val TAG = "WelcomeSpeaker"

    private const val HINDI_SPEAK_TIMEOUT_MS = 30_000L
    private const val POCKET_SPEAK_TIMEOUT_MS = 30_000L
    private const val ONLINE_OVERALL_TIMEOUT_MS = 60_000L
    private const val SYSTEM_TTS_INIT_TIMEOUT_MS = 3_000L
    private const val SYSTEM_SPEAK_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speakJob: Job? = null
    private var hindiTts: HindiTtsEngine? = null
    private var pocketTts: PocketTtsEngine? = null
    private var systemTts: TextToSpeech? = null

    @Volatile
    private var stopped = false

    /** Lia's own first-run greeting, spoken form (no emoji — TTS misreads it). */
    fun welcomeText(personality: Personality, assistantName: String): String {
        return if (personality == Personality.GF_MODE) {
            "Hey jan! Main tumhari Lia hun — tumhari apni girlfriend. " +
                "Aaj kaisa feel kar rahe ho? Batao na, main sun rahi hun."
        } else {
            "Hello! I am $assistantName. How can I assist you today?"
        }
    }

    /** The same greeting as shown on the card (with the heart, like Chat). */
    fun welcomeDisplayText(personality: Personality, assistantName: String): String {
        return if (personality == Personality.GF_MODE) {
            "Hey jan! 💖 Main tumhari Lia hun — tumhari apni girlfriend. " +
                "Aaj kaisa feel kar rahe ho? Batao na, main sun rahi hun."
        } else {
            welcomeText(personality, assistantName)
        }
    }

    /** True once the welcome has been spoken or skipped — it plays once, ever. */
    fun hasSpoken(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context, NovaPreferences.Keys.WELCOME_SPOKEN, false
        )
    }

    fun markSpoken(context: Context) {
        NovaPreferences.putBoolean(
            context, NovaPreferences.Keys.WELCOME_SPOKEN, true
        )
    }

    /**
     * Speak [text] once, Hindi → Pocket → Online → System, then [onDone].
     * [onDone] fires exactly once on the main thread, whether the
     * welcome spoke or every engine failed. A previous welcome is
     * stopped first.
     */
    fun speak(context: Context, text: String, onDone: () -> Unit) {
        stop()
        stopped = false
        speakJob = scope.launch {
            val ctx = context.applicationContext
            var ok = false
            // 1. Hindi TTS first when downloaded (V21 — native Hindi
            // voice; Roman Urdu is transliterated inside the engine).
            if (HindiTtsEngine.isReady(ctx)) {
                ok = tryHindi(ctx, text)
            }
            // 2. Pocket TTS when Hindi is missing or failed.
            if (!ok && !stopped && PocketTtsEngine.isReady(ctx)) {
                ok = tryPocket(ctx, text)
            }
            // 3. Online Kokoro when both on-device voices are missing
            // or failed.
            if (!ok && !stopped) {
                ok = withTimeoutOrNull(ONLINE_OVERALL_TIMEOUT_MS) {
                    tryOnline(ctx, text)
                } ?: false
            }
            // 4. System TTS — always available, last resort.
            if (!ok && !stopped) {
                ok = trySystem(ctx, text)
            }
            if (!stopped) {
                mainHandler.post { onDone() }
            }
        }
    }

    /** Stop any in-flight welcome speech at once. [onDone] will not fire. */
    fun stop() {
        stopped = true
        speakJob?.cancel()
        speakJob = null
        runCatching { hindiTts?.stop() }
        runCatching { hindiTts?.release() }
        hindiTts = null
        runCatching { pocketTts?.stop() }
        runCatching { pocketTts?.release() }
        pocketTts = null
        OnlineTtsEngine.stop()
        runCatching { systemTts?.stop() }
        runCatching { systemTts?.shutdown() }
        systemTts = null
    }

    /**
     * Hindi TTS, one shot: create the engine (model load), speak,
     * release. Returns false when the engine cannot start or the
     * speak fails / times out at 30s. Cancellation (stop) propagates.
     */
    private suspend fun tryHindi(ctx: Context, text: String): Boolean {
        val engine = HindiTtsEngine.create(ctx) ?: return false
        hindiTts = engine
        try {
            return withTimeoutOrNull(HINDI_SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { engine.stop() }
                    engine.speak(
                        text,
                        onDone = { if (cont.isActive) cont.resume(true) },
                        onError = { if (cont.isActive) cont.resume(false) }
                    )
                }
            } ?: false
        } finally {
            runCatching { engine.release() }
            if (hindiTts === engine) hindiTts = null
        }
    }

    /**
     * Pocket TTS, one shot: create the engine (model load), speak,
     * release. Returns false when the engine cannot start or the
     * speak fails / times out at 30s. Cancellation (stop) propagates.
     */
    private suspend fun tryPocket(ctx: Context, text: String): Boolean {
        val voiceId = PocketTtsVoicePreferences.getVoiceId(ctx)
        val engine = PocketTtsEngine.create(ctx, voiceId) ?: return false
        pocketTts = engine
        try {
            return withTimeoutOrNull(POCKET_SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { engine.stop() }
                    engine.speak(
                        text,
                        onDone = { if (cont.isActive) cont.resume(true) },
                        onError = { if (cont.isActive) cont.resume(false) }
                    )
                }
            } ?: false
        } finally {
            runCatching { engine.release() }
            if (pocketTts === engine) pocketTts = null
        }
    }

    /** Online Kokoro, one shot. The 20s synthesis timeout lives inside OnlineTtsEngine. */
    private suspend fun tryOnline(ctx: Context, text: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { OnlineTtsEngine.stop() }
            OnlineTtsEngine.speak(
                ctx, scope, text,
                onDone = { if (cont.isActive) cont.resume(true) },
                onError = { if (cont.isActive) cont.resume(false) }
            )
        }
    }

    /**
     * System TTS, one shot, on its own fresh instance (never the
     * voice session's — the welcome must not touch session state).
     * 3s init watchdog, 15s speak watchdog. Cancellation propagates.
     */
    private suspend fun trySystem(ctx: Context, text: String): Boolean {
        try {
            return withTimeoutOrNull(SYSTEM_SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    val finish = { ok: Boolean ->
                        if (cont.isActive) cont.resume(ok)
                    }
                    val initTimeout = Runnable { finish(false) }
                    mainHandler.postDelayed(initTimeout, SYSTEM_TTS_INIT_TIMEOUT_MS)
                    cont.invokeOnCancellation {
                        mainHandler.removeCallbacks(initTimeout)
                        runCatching { tts?.stop() }
                        runCatching { tts?.shutdown() }
                    }
                    tts = TextToSpeech(ctx) { status ->
                        mainHandler.removeCallbacks(initTimeout)
                        val ready = tts
                        if (status != TextToSpeech.SUCCESS || ready == null) {
                            finish(false)
                            return@TextToSpeech
                        }
                        systemTts = ready
                        CuteVoice.applyCuteVoice(
                            ready,
                            DeviceTtsVoicePreferences.getVoiceName(ctx)
                        )
                        ready.setOnUtteranceProgressListener(
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    finish(true)
                                }

                                override fun onError(utteranceId: String?) {
                                    finish(false)
                                }
                            }
                        )
                        if (ready.speak(
                                text,
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "lia-welcome"
                            ) == TextToSpeech.ERROR
                        ) {
                            finish(false)
                        }
                    }
                }
            } ?: false
        } finally {
            runCatching { systemTts?.shutdown() }
            systemTts = null
        }
    }
}
