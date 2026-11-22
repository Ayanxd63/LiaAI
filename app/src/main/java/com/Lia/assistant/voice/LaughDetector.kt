package com.Lia.assistant.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.AudioEvent
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerAudioTaggingModelConfig
import java.io.File

/**
 * V17 item 4 — laugh detection as a feeling signal.
 *
 * Roadmap asked for YAMNet; the implementation uses sherpa-onnx
 * AudioTagging (Zipformer, Apache-2.0, ~65 MB) instead, for one
 * reason: YAMNet would need a second runtime (TFLite) plus an
 * unverified model host plus a hand-written log-mel front end, while
 * AudioTagging shares the runtime V17 already ships and does the
 * same job. The AudioSet label set (528 labels, verified in the
 * downloaded CSV) contains "Laughter" (16), "Baby laughter" (17),
 * "Giggle" (18) and "Belly laugh" (20) — the detector watches those
 * four.
 *
 * Detection only: when a laugh is heard, [LaughSignal] records the
 * moment, and the next voice reply gets one warm beat in its prompt
 * ("he just laughed — notice it warmly once, then carry on"). The
 * emotion stays in the reply TEXT — never adaptive TTS pitch, never
 * a forced joke, never a laugh back.
 *
 * Wiring: the no-key voice path runs this on each captured utterance
 * (see [MoonshineSttEngine.listenOnce]) when the toggle is on. The
 * Live (Gemini) path is NOT wired in V17 — its mic pipeline is
 * key-only and fragile, and bolting a 65 MB tagger onto it was not
 * worth the risk before the detector proves itself on-device. The
 * detector class is complete and reusable for that hook later.
 *
 * Opt-in (default OFF): the model is 65 MB and only runs on mic
 * audio during voice sessions. Nothing is recorded or stored — the
 * audio is classified in memory and dropped.
 */
class LaughDetector private constructor(
    private val tagging: AudioTagging
) {

    companion object {
        private const val TAG = "LaughDetector"

        /** AudioSet labels that count as laughter (verified in the CSV). */
        val LAUGH_LABELS: Set<String> = setOf(
            "Laughter",
            "Baby laughter",
            "Giggle",
            "Belly laugh"
        )

        /** Minimum event probability to count as a laugh. */
        const val LAUGH_PROB_THRESHOLD = 0.30f

        /** True when the engine can be created right now. */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            return VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_AUDIO_TAGGING)
        }

        fun create(context: Context): LaughDetector? {
            if (!isReady(context)) return null
            return runCatching {
                val dir = VoiceModelManager.engineDir(
                    context,
                    VoiceModelManager.MODEL_AUDIO_TAGGING
                )
                val zipformer = OfflineZipformerAudioTaggingModelConfig.builder()
                    .setModel(File(dir, "model.int8.onnx").absolutePath)
                    .build()
                val modelConfig = AudioTaggingModelConfig.builder()
                    .setZipformer(zipformer)
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build()
                val config = AudioTaggingConfig.builder()
                    .setModel(modelConfig)
                    .setLabels(File(dir, "class_labels_indices.csv").absolutePath)
                    .setTopK(5)
                    .build()
                LaughDetector(AudioTagging(config))
            }.onFailure { e ->
                Log.w(TAG, "Laugh detector failed to start", e)
            }.getOrNull()
        }

        /** True when any top event is a laugh label above threshold. */
        fun isLaugh(events: Array<AudioEvent>): Boolean {
            return events.any { event ->
                event.name in LAUGH_LABELS && event.prob >= LAUGH_PROB_THRESHOLD
            }
        }
    }

    /**
     * Classify [samples] (16 kHz mono float, ~2 s is a good window)
     * and return the top-5 AudioSet events. Empty when nothing was
     * detected. Runs on the calling thread — call from a background
     * thread.
     */
    fun detect(samples: FloatArray): Array<AudioEvent> {
        if (samples.isEmpty()) return emptyArray()
        return runCatching {
            val stream = tagging.createStream()
            try {
                stream.acceptWaveform(samples, MicRecorder.SAMPLE_RATE)
                tagging.compute(stream, 5)
            } finally {
                runCatching { stream.release() }
            }
        }.onFailure { e ->
            Log.w(TAG, "Laugh detection failed", e)
        }.getOrDefault(emptyArray())
    }

    fun release() {
        runCatching { tagging.release() }
    }
}

/**
 * The laugh flag the dialogue layer reads. [noteLaugh] is called by
 * whoever ran the detector; the next prompt build consumes it with
 * [consumeRecentLaugh] (one-shot — a laugh is noticed once, then it
 * is gone). No audio, no text, no identity is stored — just a
 * timestamp in memory.
 */
object LaughSignal {

    @Volatile
    private var lastLaughAtMs: Long = 0L

    fun noteLaugh() {
        lastLaughAtMs = System.currentTimeMillis()
    }

    /**
     * True when a laugh was noted within the last [withinMs]. Consumes
     * the signal either way, so one laugh produces at most one beat.
     */
    fun consumeRecentLaugh(withinMs: Long = 30_000L): Boolean {
        val at = lastLaughAtMs
        lastLaughAtMs = 0L
        if (at == 0L) return false
        return System.currentTimeMillis() - at <= withinMs
    }
}
