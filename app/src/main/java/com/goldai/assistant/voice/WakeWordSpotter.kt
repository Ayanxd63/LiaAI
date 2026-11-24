package com.goldai.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V14 — on-device "Hey Lia" wake-word spotter (Vosk, Apache-2.0).
 *
 * How it works: the small English model listens on the mic through
 * [SpeechService]; the recognizer runs in GRAMMAR mode with only the
 * wake phrase (plus alternates and [unk]), so it is a keyword spotter,
 * not a transcriber — ambient speech is recognised as [unk] and ignored.
 * Audio never leaves the phone: Vosk runs fully offline, no key, no
 * network, no user limit.
 *
 * Lifecycle: [start] loads the model on IO (slow, ~1-2s) and then
 * starts listening. [onWakeWord] fires ONCE per start — the caller
 * (VoiceForegroundService) stops the spotter and starts a voice session.
 * [stop] halts capture and frees the mic so the voice session (Live or
 * no-key SpeechRecognizer) owns it exclusively — the spotter and the
 * session never listen at the same time.
 *
 * Threading: Vosk callbacks arrive on its own audio thread; [onWakeWord]
 * and [onError] are delivered on the caller's thread as-is — the
 * service posts them to the main handler before touching UI or the
 * session manager.
 */
class WakeWordSpotter(
    private val context: Context,
    private val onWakeWord: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "WakeWordSpotter"
        private const val SAMPLE_RATE = 16000f

        /**
         * Grammar-mode phrase list. "[unk]" is Vosk's unknown-word token:
         * anything that is not the wake phrase is recognised as [unk],
         * which is exactly what a keyword spotter wants. The alternates
         * cover the ways the small model hears "Lia" (Leah / Liya).
         */
        private const val WAKE_GRAMMAR =
            "[\"hey lia\", \"hey leah\", \"hey liya\", \"[unk]\"]"

        /** Normalised phrases that count as the wake word. */
        private val WAKE_PHRASES = listOf("hey lia", "hey leah", "hey liya")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fired = AtomicBoolean(false)

    @Volatile
    var isRunning: Boolean = false
        private set

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            checkHypothesis(hypothesis, partial = true)
        }

        override fun onResult(hypothesis: String) {
            checkHypothesis(hypothesis, partial = false)
        }

        override fun onFinalResult(hypothesis: String) {
            checkHypothesis(hypothesis, partial = false)
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Wake-word recognition error", e)
            stop()
            onError(e.localizedMessage ?: "Wake-word error")
        }

        override fun onTimeout() {
            // No timeout is set on startListening — this should not fire.
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        fired.set(false)
        scope.launch {
            try {
                val dir = WakeWordModelManager.modelDir(context)
                if (!WakeWordModelManager.isModelReady(context)) {
                    throw IllegalStateException("Wake-word model is not downloaded.")
                }
                val loaded = Model(dir.absolutePath)
                // The service may have stopped us while the model was
                // loading (e.g. a session started in the meantime) — if
                // so, drop the loaded model instead of starting to listen
                // with no owner. Without this check the spotter would
                // leak and fight the session for the mic.
                if (!isRunning) {
                    runCatching { loaded.close() }
                    return@launch
                }
                model = loaded
                val rec = Recognizer(loaded, SAMPLE_RATE, WAKE_GRAMMAR)
                recognizer = rec
                val service = SpeechService(rec, SAMPLE_RATE)
                speechService = service
                if (!service.startListening(listener)) {
                    throw IllegalStateException("Could not start listening.")
                }
                Log.d(TAG, "Wake-word spotter running (grammar: Hey Lia)")
            } catch (e: Throwable) {
                // V21 fix: UnsatisfiedLinkError is an Error, not an
                // Exception — a missing native lib (e.g. JNA's
                // libjnidispatch.so) used to escape this catch and kill
                // the app. Degrade to onError + stop() instead. Never
                // swallow coroutine cancellation.
                if (e is CancellationException) throw e
                Log.w(TAG, "Wake-word spotter failed to start", e)
                stop()
                onError(e.localizedMessage ?: "Wake-word could not start")
            }
        }
    }

    fun stop() {
        if (!isRunning && speechService == null) return
        isRunning = false
        try {
            speechService?.stop()
        } catch (_: Exception) {
        }
        try {
            speechService?.shutdown()
        } catch (_: Exception) {
        }
        speechService = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
    }

    /** Free the coroutine scope when the spotter is discarded for good. */
    fun release() {
        stop()
        scope.cancel()
    }

    private fun checkHypothesis(hypothesis: String, partial: Boolean) {
        if (fired.get()) return
        val text = try {
            val json = JSONObject(hypothesis)
            // Partial results carry "partial", final ones carry "text".
            json.optString(if (partial) "partial" else "text", "")
        } catch (_: Exception) {
            return
        }
        val normalized = text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (WAKE_PHRASES.any { normalized.contains(it) }) {
            if (!fired.compareAndSet(false, true)) return
            Log.d(TAG, "Wake word heard: \"$normalized\"")
            stop()
            onWakeWord()
        }
    }
}
