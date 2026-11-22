package com.Lia.assistant.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * V17 item 3 — speaker verification (guardian pattern, public upstream).
 *
 * Model: ERes2Net speaker embedding (3D-Speaker, Apache-2.0, ~38 MB),
 * the public equivalent of Maya's Silero VAD + ReDimNet/ECAPA stack.
 * Silero VAD (item 6) is the speech gate; this model turns a voice
 * sample into an embedding vector, and enrolment compares vectors by
 * cosine similarity.
 *
 * What it is: a soft "is this the enrolled voice?" score shown on the
 * enrolment screen and used to gate the "Hey Lia" wake word when the
 * user enables it. What it is NOT: biometric verification. A 0.5B-
 * class embedding on a phone mic cannot prove identity — the screen
 * says so in plain language, exactly like V16's disclosure. A
 * stranger saying "Hey Lia" can still wake Lia; this only makes the
 * enrolled voice score higher.
 *
 * Privacy: the enrolment profile (one float vector + timestamp) lives
 * in the app's private files dir, is NEVER backed up (see
 * backup_rules.xml), is never sent anywhere, and is deleted with the
 * Reset button on the enrolment screen. Voice SAMPLES are never
 * stored — only the embedding vector.
 */
class SpeakerVerifier private constructor(
    private val extractor: SpeakerEmbeddingExtractor
) {

    companion object {
        private const val TAG = "SpeakerVerify"

        /** Minimum sample length for a usable embedding (seconds). */
        const val MIN_SAMPLE_SECONDS = 2

        /** Cosine similarity at/above this counts as "same voice". */
        const val MATCH_THRESHOLD = 0.55f

        private const val PROFILE_FILE_NAME = "speaker_profile.json"

        /** True when the engine can be created right now. */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            return VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_SPEAKER)
        }

        fun create(context: Context): SpeakerVerifier? {
            if (!isReady(context)) return null
            return runCatching {
                val modelFile = File(
                    VoiceModelManager.engineDir(context, VoiceModelManager.MODEL_SPEAKER),
                    "eres2net.onnx"
                )
                val config = SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(modelFile.absolutePath)
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build()
                SpeakerVerifier(SpeakerEmbeddingExtractor(config))
            }.onFailure { e ->
                Log.w(TAG, "Speaker verifier failed to start", e)
            }.getOrNull()
        }

        /** Cosine similarity of two embeddings, -1 when unusable. */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return -1f
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            if (denom == 0f) return -1f
            return (dot / denom).coerceIn(-1f, 1f)
        }

        /** The enrolled profile, or null when nobody enrolled yet. */
        fun loadProfile(context: Context): FloatArray? {
            val file = File(context.filesDir, PROFILE_FILE_NAME)
            if (!file.exists()) return null
            return runCatching {
                val obj = JSONObject(file.readText())
                val array = obj.getJSONArray("embedding")
                FloatArray(array.length()) { i -> array.getDouble(i).toFloat() }
            }.onFailure { e ->
                Log.w(TAG, "Speaker profile unreadable", e)
            }.getOrNull()
        }

        fun isEnrolled(context: Context): Boolean = loadProfile(context) != null

        /** Delete the enrolled profile (Reset on the enrolment screen). */
        fun clearProfile(context: Context) {
            runCatching { File(context.filesDir, PROFILE_FILE_NAME).delete() }
        }
    }

    /**
     * Turn [samples] (16 kHz mono float, at least ~2 s of speech)
     * into an embedding vector, or null when there was not enough
     * speech. Runs on the calling thread — call from a background
     * thread.
     */
    fun embed(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_SAMPLE_SECONDS * MicRecorder.SAMPLE_RATE) return null
        return runCatching {
            val stream = extractor.createStream()
            try {
                stream.acceptWaveform(samples, MicRecorder.SAMPLE_RATE)
                stream.inputFinished()
                // The extractor needs a minimum window before it is
                // ready; poll briefly instead of failing at once.
                var ready = extractor.isReady(stream)
                var waitedMs = 0
                while (!ready && waitedMs < 2000) {
                    Thread.sleep(50)
                    waitedMs += 50
                    ready = extractor.isReady(stream)
                }
                if (!ready) return@runCatching null
                extractor.compute(stream)
            } finally {
                runCatching { stream.release() }
            }
        }.onFailure { e ->
            Log.w(TAG, "Speaker embedding failed", e)
        }.getOrNull()
    }

    /** Cosine similarity of [samples] against the enrolled profile. */
    fun verify(samples: FloatArray, context: Context): Float {
        val profile = loadProfile(context) ?: return -1f
        val embedding = embed(samples) ?: return -1f
        return cosineSimilarity(profile, embedding)
    }

    /** Store [embedding] as the enrolled profile (on-device only). */
    fun saveProfile(context: Context, embedding: FloatArray) {
        runCatching {
            val array = JSONArray()
            for (value in embedding) array.put(value.toDouble())
            val obj = JSONObject().apply {
                put("embedding", array)
                put("enrolledAt", System.currentTimeMillis())
                put("dim", embedding.size)
            }
            File(context.filesDir, PROFILE_FILE_NAME).writeText(obj.toString())
        }.onFailure { e ->
            Log.w(TAG, "Speaker profile save failed", e)
        }
    }

    fun release() {
        runCatching { extractor.release() }
    }
}
