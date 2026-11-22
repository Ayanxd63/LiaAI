package com.Lia.assistant.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * V17 item 6 — Silero VAD (v5, MIT) as a speech gate, and item 7 —
 * the VAD-based turn detector.
 *
 * The VAD is a tiny (~2.3 MB) ONNX model that answers one question
 * per 512-sample frame (32 ms at 16 kHz): is someone speaking right
 * now? It gates the Vosk wake-word spotter in
 * [VoiceForegroundService]: Vosk only runs while speech is present,
 * instead of decoding every frame of silence all day. It also ends
 * Moonshine listening rounds (speech → silence = the turn is over).
 *
 * The canonical Silero ONNX path (`files/silero_vad.onnx`) 404s — the
 * file moved to `src/silero_vad/data/` in v5, and that is the URL in
 * [VoiceModelManager]. The model is downloaded on first enable
 * (Wi-Fi only), never bundled.
 *
 * Item 7 verdict: no separate end-of-speech model is needed. The VAD
 * plus a silence-duration rule IS the turn detector — it feeds the
 * V16 [VoiceTurnState] machine (LISTENING → SPEECH_DETECTED →
 * END_OF_SPEECH) from real speech/silence transitions instead of the
 * recognizer's callbacks. The dedicated-model route was evaluated
 * and dropped: Maya's turn model is proprietary, and VAD + silence
 * logic is sufficient for a single-speaker phone assistant.
 */
class SileroVadGate private constructor(private val vad: Vad) {

    companion object {
        private const val TAG = "SileroVad"

        /** Frames per VAD window (512 samples = 32 ms at 16 kHz). */
        const val WINDOW_SAMPLES = 512

        /** True when the VAD model is downloaded and the runtime loads. */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            return VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_VAD)
        }

        fun create(context: Context): SileroVadGate? {
            if (!isReady(context)) return null
            return runCatching {
                val modelFile = File(
                    VoiceModelManager.engineDir(context, VoiceModelManager.MODEL_VAD),
                    "silero_vad.onnx"
                )
                val sileroConfig = SileroVadModelConfig.builder()
                    .setModel(modelFile.absolutePath)
                    .setThreshold(0.5f)
                    .setMinSilenceDuration(0.25f)
                    .setMinSpeechDuration(0.5f)
                    .setWindowSize(WINDOW_SAMPLES)
                    .setMaxSpeechDuration(5.0f)
                    .build()
                val config = VadModelConfig.builder()
                    .setSileroVadModelConfig(sileroConfig)
                    .setSampleRate(MicRecorder.SAMPLE_RATE)
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build()
                SileroVadGate(Vad(config))
            }.onFailure { e ->
                Log.w(TAG, "Silero VAD failed to start", e)
            }.getOrNull()
        }
    }

    /**
     * Feed one 512-sample frame. Returns true while speech is
     * currently detected. Shorter/longer chunks are silently ignored
     * (the VAD window is fixed).
     */
    fun acceptFrame(samples: FloatArray): Boolean {
        if (samples.size != WINDOW_SAMPLES) return isSpeechDetected()
        return runCatching {
            vad.acceptWaveform(samples)
            vad.isSpeechDetected()
        }.getOrDefault(false)
    }

    fun isSpeechDetected(): Boolean {
        return runCatching { vad.isSpeechDetected() }.getOrDefault(false)
    }

    fun reset() {
        runCatching { vad.reset() }
    }

    fun release() {
        runCatching { vad.release() }
    }
}

/**
 * Item 7 — turn detection on top of the VAD gate.
 *
 * Feed 512-sample frames with [feed]; [onSpeechStart] fires on the
 * speech → silence flip into speech, [onSpeechEnd] after
 * [silenceFramesToEnd] consecutive silent frames (~1.5 s by
 * default). Callers mirror these into the V16 VoiceTurnState machine.
 * No model beyond the VAD is involved.
 */
class VoiceTurnDetector(
    private val vadGate: SileroVadGate,
    private val silenceFramesToEnd: Int = 48, // 48 × 32 ms ≈ 1.5 s
    private val onSpeechStart: (() -> Unit)? = null,
    private val onSpeechEnd: (() -> Unit)? = null
) {
    private var speaking = false
    private var silentFrames = 0

    val isSpeaking: Boolean get() = speaking

    fun feed(samples: FloatArray) {
        val speech = vadGate.acceptFrame(samples)
        if (speech) {
            silentFrames = 0
            if (!speaking) {
                speaking = true
                onSpeechStart?.invoke()
            }
        } else if (speaking) {
            silentFrames++
            if (silentFrames >= silenceFramesToEnd) {
                speaking = false
                silentFrames = 0
                onSpeechEnd?.invoke()
            }
        }
    }

    fun reset() {
        speaking = false
        silentFrames = 0
        vadGate.reset()
    }
}
