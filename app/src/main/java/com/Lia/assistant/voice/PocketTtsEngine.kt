package com.Lia.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.util.function.Consumer

/**
 * V17 item 1 (FLAGSHIP) — on-device TTS: Lia's own lovely voice, no
 * key, no internet.
 *
 * Engine: sherpa-onnx OfflineTts with the Pocket TTS int8 model
 * (Kyutai Pocket TTS export, CC-BY-4.0). The model (~191 MB) is
 * downloaded on first enable from Settings → On-device voice, never
 * bundled in the APK. The voice is set by a short reference recording
 * (bria.wav = "Lia", loona.wav = "Loona") — public samples from the
 * model repo, NOT Maya's voice models or recordings.
 *
 * Audio streams chunk-by-chunk into an AudioTrack as sherpa
 * generates it, so Lia starts speaking before the whole reply is
 * synthesized. System TTS stays the fallback everywhere this engine
 * is not ready (see [VoiceSessionManager.speakNoKey]).
 *
 * Honest limits (same as the V17 plan): the Kyutai model has NO Hindi
 * — it speaks English (+ fr/de/pt/it/es) and reads Roman Urdu with
 * English pronunciation. A native Hindi/Hinglish on-device voice
 * needs the Indus finetune exported to ONNX (V18). Generation is
 * CPU-bound: a short reply takes a few seconds on the Infinix HOT
 * 40i, and long replies cost battery — the engine unloads when the
 * voice session stops.
 */
class PocketTtsEngine private constructor(
    private val tts: OfflineTts,
    private val referenceAudio: FloatArray,
    private val referenceSampleRate: Int
) {

    companion object {
        private const val TAG = "PocketTts"

        /** Minimum free RAM to load the TTS model (~200 MB + runtime). */
        private const val MIN_FREE_RAM_BYTES = 800L * 1024L * 1024L

        /** True when the engine can be created right now. */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            return VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_POCKET_TTS)
        }

        /** Plain-language reason when [isReady] is false, else null. */
        fun blockedReason(context: Context): String? {
            SherpaRuntime.unavailableReason()?.let { return it }
            if (!VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_POCKET_TTS)) {
                return "Lia voice abhi download nahi hui — Settings → On-device voice se download karo (Wi-Fi, ~191 MB)."
            }
            val free = OfflineModelManager.freeRamBytes(context)
            if (free != 0L && free < MIN_FREE_RAM_BYTES) {
                return "Phone ki memory kam hai — Lia voice load nahi ho sakti. Kuch apps band kar ke dobara try karo."
            }
            return null
        }

        /**
         * Build the engine for [voiceId] ("lia" or "loona"). Returns
         * null when the model is not ready or the native library
         * failed to load. Expensive (loads ~200 MB of ONNX models) —
         * call once per voice session, not per reply.
         */
        fun create(context: Context, voiceId: String): PocketTtsEngine? {
            if (!isReady(context)) return null
            return runCatching {
                val model = VoiceModelManager.MODEL_POCKET_TTS
                val dir = VoiceModelManager.engineDir(context, model)
                fun path(name: String): String =
                    File(dir, name).absolutePath
                val pocketConfig = OfflineTtsPocketModelConfig.builder()
                    .setLmFlow(path("lm_flow.int8.onnx"))
                    .setLmMain(path("lm_main.int8.onnx"))
                    .setEncoder(path("encoder.onnx"))
                    .setDecoder(path("decoder.int8.onnx"))
                    .setTextConditioner(path("text_conditioner.onnx"))
                    .setVocabJson(path("vocab.json"))
                    .setTokenScoresJson(path("token_scores.json"))
                    .setVoiceEmbeddingCacheCapacity(50)
                    .build()
                val modelConfig = OfflineTtsModelConfig.builder()
                    .setPocket(pocketConfig)
                    .setNumThreads(4)
                    .setProvider("cpu")
                    .build()
                val config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setMaxNumSentences(10)
                    .build()
                val tts = OfflineTts(config)
                val wavFile = if (voiceId == "loona") {
                    File(dir, "voice_loona.wav")
                } else {
                    File(dir, "voice_lia.wav")
                }
                val (samples, sampleRate) = readWavMonoFloat16(wavFile)
                PocketTtsEngine(tts, samples, sampleRate)
            }.onFailure { e ->
                Log.w(TAG, "Pocket TTS engine failed to start", e)
            }.getOrNull()
        }

        /**
         * Read a 16-bit PCM WAV file into mono float samples in
         * [-1, 1] plus its sample rate. Stereo takes the left channel.
         * Throws on anything that is not 16-bit PCM.
         */
        fun readWavMonoFloat16(file: File): Pair<FloatArray, Int> {
            val bytes = file.readBytes()
            require(bytes.size > 44) { "WAV file too small: ${file.name}" }
            fun tag(offset: Int): String =
                String(bytes, offset, 4, Charsets.US_ASCII)
            require(tag(0) == "RIFF" && tag(8) == "WAVE") {
                "Not a WAV file: ${file.name}"
            }
            var offset = 12
            var sampleRate = 0
            var channels = 1
            var bitsPerSample = 16
            var dataStart = -1
            var dataBytes = 0
            while (offset + 8 <= bytes.size) {
                val chunkId = tag(offset)
                val chunkSize = ((bytes[offset + 4].toInt() and 0xFF)) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
                val chunkData = offset + 8
                when (chunkId) {
                    "fmt " -> {
                        val audioFormat = (bytes[chunkData].toInt() and 0xFF) or
                            ((bytes[chunkData + 1].toInt() and 0xFF) shl 8)
                        require(audioFormat == 1) { "WAV is not PCM: ${file.name}" }
                        channels = (bytes[chunkData + 2].toInt() and 0xFF) or
                            ((bytes[chunkData + 3].toInt() and 0xFF) shl 8)
                        sampleRate = (bytes[chunkData + 4].toInt() and 0xFF) or
                            ((bytes[chunkData + 5].toInt() and 0xFF) shl 8) or
                            ((bytes[chunkData + 6].toInt() and 0xFF) shl 16) or
                            ((bytes[chunkData + 7].toInt() and 0xFF) shl 24)
                        bitsPerSample = (bytes[chunkData + 14].toInt() and 0xFF) or
                            ((bytes[chunkData + 15].toInt() and 0xFF) shl 8)
                        require(bitsPerSample == 16) {
                            "WAV is not 16-bit: ${file.name}"
                        }
                    }
                    "data" -> {
                        dataStart = chunkData
                        dataBytes = chunkSize
                    }
                }
                offset = chunkData + chunkSize + (chunkSize % 2)
            }
            require(sampleRate > 0 && dataStart >= 0) {
                "WAV header incomplete: ${file.name}"
            }
            val frameCount = dataBytes / 2 / channels
            val samples = FloatArray(frameCount)
            for (i in 0 until frameCount) {
                val pos = dataStart + i * 2 * channels
                val raw = (bytes[pos].toInt() and 0xFF) or
                    (bytes[pos + 1].toInt() shl 8)
                samples[i] = (raw.toShort().toFloat() / 32768f)
                    .coerceIn(-1f, 1f)
            }
            return samples to sampleRate
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cancelled = false

    @Volatile
    private var speaking = false

    /** True while a [speak] call is generating/playing. */
    fun isSpeaking(): Boolean = speaking

    /**
     * Speak [text] with the on-device voice. Generation runs on a
     * background thread and streams audio into the speaker as it is
     * made; [onDone] fires on the main thread when playback finishes,
     * [onError] (main thread) when generation fails. A second [speak]
     * while one is running is ignored — call [stop] first.
     */
    fun speak(
        text: String,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (speaking) return
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        speaking = true
        cancelled = false
        Thread({
            var track: AudioTrack? = null
            try {
                val sampleRate = tts.sampleRate
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val genConfig = GenerationConfig().apply {
                    setReferenceAudio(referenceAudio)
                    setReferenceSampleRate(referenceSampleRate)
                    setNumSteps(5)
                    setSpeed(1.0f)
                    // No reference text: the sample recordings ship
                    // without a transcript, and the field is optional.
                }
                tts.generateWithConfigAndCallback(
                    text,
                    genConfig,
                    Consumer<FloatArray> { samples ->
                        if (!cancelled && samples.isNotEmpty()) {
                            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                )
                if (!cancelled) {
                    // Let the buffered tail finish playing.
                    track.stop()
                }
                mainHandler.post {
                    speaking = false
                    if (cancelled) return@post
                    onDone?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pocket TTS speak failed", e)
                runCatching { track?.stop() }
                mainHandler.post {
                    speaking = false
                    if (!cancelled) onError?.invoke("Lia voice bol nahi saki — dobara try karo.")
                }
            } finally {
                runCatching { track?.release() }
            }
        }, "pocket-tts-speak").start()
    }

    /** Stop playback at once; the in-flight generation is dropped. */
    fun stop() {
        cancelled = true
    }

    /** Free the model + the audio track. Call when the session ends. */
    fun release() {
        cancelled = true
        runCatching { tts.release() }
    }
}
