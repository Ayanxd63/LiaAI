package com.goldai.assistant.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V21 — on-device Urdu/Hindi speech recognition (Shruti, via
 * sherpa-onnx Moonshine). This is the LISTENING side of the voice
 * fix: Lia's Android system recognizer speaks the user's Urdu
 * through an English-tuned model, so she mis-hears him ("samajh
 * nahi aarahi"). Shruti is a small Moonshine model trained for
 * South Asian languages — the URDU pack first (his spoken language),
 * Hindi as fallback — so she hears his Urdu as Urdu.
 *
 * Models (UDGAM Labs, Apache-2.0, download on first enable from
 * Settings → On-device voice, Wi-Fi only, never bundled):
 * - Urdu: udgamlabs/Shruti-ONNX, path `ur/sherpa-onnx/`, ~51.4 MiB
 *   (cached_decode 31.3 MB, encode 18.7 MB, preprocess 0.9 MB,
 *   uncached_decode 0.4 MB, tokens 0.1 MB).
 * - Hindi: same repo, path `hi/sherpa-onnx/`, ~50.9 MiB.
 *
 * Config follows the repo's own README: `OfflineRecognizer.from_moonshine`
 * (preprocessor, encoder, uncached decoder, cached decoder, tokens),
 * greedy search, 2 threads, CPU. One behaviour note from that
 * README that shapes this class: the sherpa-onnx pack has NO
 * word-by-word streaming — the transcript arrives when the search
 * ends, so [listenOnce] returns the full utterance text at once,
 * and audio longer than ~30s is split with a VAD first (here: the
 * caller caps an utterance at 12s, so a VAD is unnecessary).
 *
 * Urdu/Hindi recognition is the default when the Urdu model is
 * downloaded; the system recognizer stays the fallback (and the
 * only path when no model is downloaded). Nothing here removes
 * the English path — [listenOnce] is only used when a Shruti
 * model is ready, and the caller falls back on any failure.
 *
 * Honest limits: the model auto-detects language (sherpa-onnx
 * always does); Indian-accented English is often taken for the
 * model's own language; a noisy room degrades accuracy like any
 * on-device recognizer. Whether she understands ayan better can
 * only be judged on his phone.
 */
class ShrutiSttEngine private constructor(
    private val recognizer: OfflineRecognizer
) {

    companion object {
        private const val TAG = "ShrutiStt"

        /** Max utterance length (seconds). Keeps decode fast + under the 30s VAD rule. */
        private const val MAX_UTTERANCE_SECONDS = 12

        /** Silence (seconds) that ends the utterance. */
        private const val SILENCE_END_SECONDS = 1.5f

        /** Minimum free RAM to load the recognizer (~54 MB + runtime). */
        private const val MIN_FREE_RAM_BYTES = 600L * 1024L * 1024L

        /**
         * True when a Shruti model (Urdu first, Hindi fallback) is
         * downloaded and the native runtime is available.
         */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            return preferredModel(context) != null
        }

        /** The model to use: Urdu first, Hindi only if Urdu is missing. */
        fun preferredModel(context: Context): VoiceModel? {
            val urdu = VoiceModelManager.MODEL_SHRUTI_URDU
            if (VoiceModelManager.isModelReadyCached(context, urdu)) return urdu
            val hindi = VoiceModelManager.MODEL_SHRUTI_HINDI
            if (VoiceModelManager.isModelReadyCached(context, hindi)) return hindi
            return null
        }

        /** Plain-language reason when [isReady] is false, else null. */
        fun blockedReason(context: Context): String? {
            SherpaRuntime.unavailableReason()?.let { return it }
            if (preferredModel(context) == null) {
                return "Urdu sunne wala model abhi download nahi hua — Settings → On-device voice se download karo (Wi-Fi, ~51 MB)."
            }
            val free = VoiceModelManager.freeRamBytes(context)
            if (free != 0L && free < MIN_FREE_RAM_BYTES) {
                return "Phone ki memory kam hai — Urdu sunne wala model load nahi ho sakta. Kuch apps band kar ke dobara try karo."
            }
            return null
        }

        /** Build the engine for the preferred (Urdu-first) model. */
        fun create(context: Context): ShrutiSttEngine? {
            val model = preferredModel(context) ?: return null
            return runCatching {
                val dir = VoiceModelManager.engineDir(context, model)
                fun path(name: String): String =
                    File(dir, name).absolutePath
                val moonshineConfig = OfflineMoonshineModelConfig.builder()
                    .setPreprocessor(path("preprocess.onnx"))
                    .setEncoder(path("encode.onnx"))
                    .setUncachedDecoder(path("uncached_decode.onnx"))
                    .setCachedDecoder(path("cached_decode.onnx"))
                    .build()
                val modelConfig = OfflineModelConfig.builder()
                    .setMoonshine(moonshineConfig)
                    .setTokens(path("tokens.txt"))
                    .setNumThreads(2)
                    .setProvider("cpu")
                    .build()
                val config = OfflineRecognizerConfig.builder()
                    .setOfflineModelConfig(modelConfig)
                    .setDecodingMethod("greedy_search")
                    .setMaxActivePaths(4)
                    .build()
                ShrutiSttEngine(OfflineRecognizer(config))
            }.onFailure { e ->
                Log.w(TAG, "Shruti STT engine failed to start", e)
            }.getOrNull()
        }
    }

    private val stopped = AtomicBoolean(false)

    /** True while [listenOnce] is capturing audio. */
    fun isListening(): Boolean = !stopped.get()

    /**
     * Listen once (up to [MAX_UTTERANCE_SECONDS]s) and return the
     * recognised text, or null when nothing was heard / the mic
     * failed / [stopListening] was called. Runs on the caller's
     * thread — call from a background thread, never Main. Silence
     * ends the utterance; a quiet room ends it early after ~4s.
     */
    fun listenOnce(context: Context): String? {
        stopped.set(false)
        val record = MicRecorder.createAudioRecord() ?: return null
        val chunkSamples = MicRecorder.SAMPLE_RATE / 10 // 100 ms
        val chunk = ShortArray(chunkSamples)
        val samples = ArrayList<Float>(MicRecorder.SAMPLE_RATE * MAX_UTTERANCE_SECONDS)
        var quietFrames = 0
        var speechFrames = 0
        val silenceFrames = (SILENCE_END_SECONDS * 10).toInt()
        try {
            record.startRecording()
            val deadline = System.currentTimeMillis() + MAX_UTTERANCE_SECONDS * 1000L
            while (!stopped.get() && System.currentTimeMillis() < deadline) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break
                var energy = 0f
                for (i in 0 until read) {
                    val f = chunk[i].toFloat() / 32768f
                    samples.add(f)
                    energy += f * f
                }
                val rms = kotlin.math.sqrt(energy / read)
                if (rms > 0.02f) {
                    speechFrames++
                    quietFrames = 0
                } else {
                    quietFrames++
                    // 4s of quiet before any speech = nobody talking.
                    if (speechFrames == 0 && quietFrames > 40) break
                    // 1.5s of quiet after speech = utterance over.
                    if (speechFrames > 0 && quietFrames >= silenceFrames) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shruti listen failed", e)
            return null
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        if (stopped.get()) return null
        if (samples.isEmpty() || speechFrames == 0) return null
        return runCatching {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples.toFloatArray(), MicRecorder.SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim().ifEmpty { null }
            } finally {
                runCatching { stream.release() }
            }
        }.onFailure { e ->
            Log.w(TAG, "Shruti decode failed", e)
        }.getOrNull()
    }

    /** Stop a [listenOnce] call in progress (from any thread). */
    fun stopListening() {
        stopped.set(true)
    }

    /** Free the recognizer. Call when the session ends. */
    fun release() {
        runCatching { recognizer.release() }
    }
}
