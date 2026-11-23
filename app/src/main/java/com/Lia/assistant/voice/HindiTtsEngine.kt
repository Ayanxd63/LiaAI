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
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.function.Consumer

/**
 * V21 — Hindi TTS (Supertonic 3 int8, sherpa-onnx native). This is
 * the voice fix: Lia's Pocket voices are English (Bria/Loona) and
 * read Roman Urdu with English pronunciation, which is why ayan
 * hears a foreign accent even with the model downloaded. Supertonic
 * 3 speaks Hindi (`hi`, one of its 31 languages) with a native
 * Hindi voice — the closest on-device equivalent of what Maya's
 * Hindi Pocket TTS does.
 *
 * Engine: sherpa-onnx OfflineTts with the Supertonic 3 int8 model
 * (Supertone Inc., MIT). The model (~139 MB) downloads on first
 * enable from Settings → On-device voice, never bundled in the APK.
 * Single voice style (`voice.bin` from the model repo); sid 0.
 *
 * Latin-script test (V21, verified from the model itself — no
 * device run needed for the DESIGN decision): Supertonic 3's text
 * encoder has `n_langs: 0` in `tts.json` — there is NO language
 * switch. Pronunciation comes straight off the characters, so
 * Latin-script Roman Urdu would come out English-sounding, which
 * is the bug being fixed. [speak] therefore ALWAYS transliterates
 * Roman Urdu → Devanagari first ([RomanUrduTransliterator]) and
 * strips emoji/symbols. If a future on-device test ever shows Latin
 * input working, one flag in [speak] can skip the transliteration.
 *
 * Audio streams chunk-by-chunk into an AudioTrack as sherpa
 * generates it (same pattern as PocketTtsEngine), so Lia starts
 * speaking before the whole reply is synthesized. Generation is
 * CPU-bound; the engine unloads when the voice session stops.
 *
 * Honest limits: the voice is Hindi, not Urdu — Urdu-specific
 * sounds (ق/غ/خ with nukta) are approximated with the closest
 * Devanagari letters, and the transliteration is best-effort (see
 * RomanUrduTransliterator's known approximations). Accent and
 * understanding can only be judged on ayan's phone, never from a
 * Robo test (no audio there).
 */
class HindiTtsEngine private constructor(
    private val tts: OfflineTts
) {

    companion object {
        private const val TAG = "HindiTts"

        /** Minimum free RAM to load the TTS model (~140 MB + runtime). */
        private const val MIN_FREE_RAM_BYTES = 800L * 1024L * 1024L

        /** True when the engine can be created right now. */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            // Cached (see VoiceModelManager): the full SHA-256 check
            // over ~139 MB is far too slow to run on every voice reply.
            return VoiceModelManager.isModelReadyCached(context, VoiceModelManager.MODEL_SUPERTONIC_TTS)
        }

        /** Plain-language reason when [isReady] is false, else null. */
        fun blockedReason(context: Context): String? {
            SherpaRuntime.unavailableReason()?.let { return it }
            if (!VoiceModelManager.isModelReadyCached(context, VoiceModelManager.MODEL_SUPERTONIC_TTS)) {
                return "Hindi voice abhi download nahi hui — Settings → On-device voice se download karo (Wi-Fi, ~139 MB)."
            }
            val free = VoiceModelManager.freeRamBytes(context)
            if (free != 0L && free < MIN_FREE_RAM_BYTES) {
                return "Phone ki memory kam hai — Hindi voice load nahi ho sakti. Kuch apps band kar ke dobara try karo."
            }
            return null
        }

        /**
         * Build the engine. Returns null when the model is not ready
         * or the native library failed to load. Expensive (loads
         * ~140 MB of ONNX models) — call once per voice session, not
         * per reply.
         */
        fun create(context: Context): HindiTtsEngine? {
            if (!isReady(context)) return null
            return runCatching {
                val model = VoiceModelManager.MODEL_SUPERTONIC_TTS
                val dir = VoiceModelManager.engineDir(context, model)
                fun path(name: String): String =
                    File(dir, name).absolutePath
                val supertonicConfig = OfflineTtsSupertonicModelConfig.builder()
                    .setDurationPredictor(path("duration_predictor.int8.onnx"))
                    .setTextEncoder(path("text_encoder.int8.onnx"))
                    .setVectorEstimator(path("vector_estimator.int8.onnx"))
                    .setVocoder(path("vocoder.int8.onnx"))
                    .setTtsJson(path("tts.json"))
                    .setUnicodeIndexer(path("unicode_indexer.bin"))
                    // voice.bin — the voice style file (see the C++
                    // header: "Path to voice.bin").
                    .setVoiceStyle(path("voice.bin"))
                    .build()
                val modelConfig = OfflineTtsModelConfig.builder()
                    .setSupertonic(supertonicConfig)
                    .setNumThreads(4)
                    .setProvider("cpu")
                    .build()
                val config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setMaxNumSentences(10)
                    .build()
                HindiTtsEngine(OfflineTts(config))
            }.onFailure { e ->
                Log.w(TAG, "Hindi TTS engine failed to start", e)
            }.getOrNull()
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
     * Speak [text] with the Hindi on-device voice. Roman Urdu is
     * transliterated to Devanagari first (see the class KDoc — the
     * model has no language switch, so Latin input would sound
     * English). Generation runs on a background thread and streams
     * audio into the speaker as it is made; [onDone] fires on the
     * main thread when playback finishes, [onError] (main thread)
     * when generation fails. A second [speak] while one is running
     * is ignored — call [stop] first.
     */
    fun speak(
        text: String,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (speaking) return
        // Transliterate FIRST: the Hindi voice needs Devanagari, and
        // emoji/symbols must never reach the text encoder.
        val devanagari = RomanUrduTransliterator.transliterate(
            RomanUrduTransliterator.sanitizeForTts(text)
        )
        if (devanagari.isBlank()) {
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
                    setSid(0)
                    setSpeed(1.0f)
                }
                tts.generateWithConfigAndCallback(
                    devanagari,
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
                Log.w(TAG, "Hindi TTS speak failed", e)
                runCatching { track?.stop() }
                mainHandler.post {
                    speaking = false
                    if (!cancelled) onError?.invoke("Hindi voice bol nahi saki — dobara try karo.")
                }
            } finally {
                runCatching { track?.release() }
            }
        }, "hindi-tts-speak").start()
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
