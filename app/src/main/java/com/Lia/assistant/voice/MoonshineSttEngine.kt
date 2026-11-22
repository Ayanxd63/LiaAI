package com.Lia.assistant.voice

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import kotlin.math.sqrt

/**
 * V17 item 2 — on-device speech recognition (Moonshine tiny-en int8,
 * MIT, ~118 MB), the public-upstream equivalent of Maya's Hunter STT.
 *
 * One engine, two uses:
 * - [transcribe]: decode a captured buffer (used after a listening
 *   round ends, and by anything else that has raw audio).
 * - [listenOnce]: own the mic for one utterance — capture from the
 *   first speech until ~1.2 s of silence (VAD when available, RMS
 *   energy otherwise), then transcribe. The turn boundaries it finds
 *   are reported through [turnListener] so the V16 turn-state machine
 *   stays in sync (item 7).
 *
 * English ONLY — Moonshine tiny-en has no Urdu/Hindi. When it is
 * enabled but the user speaks Roman Urdu, recognition will be poor;
 * the screen says so plainly, and Android's SpeechRecognizer stays
 * the default (and the only option for Urdu/Hindi speech). The
 * engine is opt-in, never the default.
 *
 * The recognizer is created once per listening session and released
 * with [release] — loading ~118 MB of ONNX models per utterance
 * would be far too slow.
 */
class MoonshineSttEngine private constructor(
    private val recognizer: OfflineRecognizer
) {

    companion object {
        private const val TAG = "MoonshineStt"

        /** Longest single utterance captured (seconds). */
        private const val MAX_UTTERANCE_SECONDS = 12

        /** Silence (seconds) that ends an utterance. */
        private const val END_SILENCE_SECONDS = 1.2f

        /** RMS energy above this counts as speech (VAD fallback). */
        private const val ENERGY_SPEECH_THRESHOLD = 0.02f

        /** True when the engine can be created right now. */
        fun isReady(context: Context): Boolean {
            if (!SherpaRuntime.isAvailable()) return false
            return VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_MOONSHINE)
        }

        fun create(context: Context): MoonshineSttEngine? {
            if (!isReady(context)) return null
            return runCatching {
                val model = VoiceModelManager.MODEL_MOONSHINE
                val dir = VoiceModelManager.engineDir(context, model)
                fun path(name: String): String = File(dir, name).absolutePath
                val moonshineConfig = OfflineMoonshineModelConfig.builder()
                    .setPreprocessor(path("preprocess.onnx"))
                    .setEncoder(path("encode.int8.onnx"))
                    .setUncachedDecoder(path("uncached_decode.int8.onnx"))
                    .setCachedDecoder(path("cached_decode.int8.onnx"))
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
                MoonshineSttEngine(OfflineRecognizer(config))
            }.onFailure { e ->
                Log.w(TAG, "Moonshine recognizer failed to start", e)
            }.getOrNull()
        }
    }

    /** Turn boundaries found while listening (item 7 wiring). */
    enum class TurnEvent { SPEECH_START, SPEECH_END }

    var turnListener: ((TurnEvent) -> Unit)? = null

    /** Fires with the raw captured utterance audio (for laugh checks). */
    var audioListener: ((FloatArray) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listening = false

    @Volatile
    private var stopRequested = false

    fun isListening(): Boolean = listening

    /**
     * Decode [samples] (16 kHz mono float) into text. Empty string
     * when nothing was recognised. Runs on the calling thread — call
     * from a background thread.
     */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        return runCatching {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, MicRecorder.SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                runCatching { stream.release() }
            }
        }.onFailure { e ->
            Log.w(TAG, "Moonshine transcribe failed", e)
        }.getOrDefault("")
    }

    /**
     * Own the mic for one utterance. [onResult] fires on the main
     * thread with the recognised text (empty = nothing heard);
     * [onError] with a plain-language reason when the mic or the
     * model fails. Call [stopListening] to cancel mid-utterance.
     */
    fun listenOnce(
        context: Context,
        onResult: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (listening) return
        if (!MicRecorder.hasPermission(context)) {
            onError?.invoke("Microphone permission chahiye — Settings se allow karo.")
            return
        }
        listening = true
        stopRequested = false
        Thread({
            var record: AudioRecord? = null
            var vadGate: SileroVadGate? = null
            var turnDetector: VoiceTurnDetector? = null
            try {
                // VAD when its model is on the phone, energy fallback
                // when it is not — listening must work either way.
                if (SileroVadGate.isReady(context)) {
                    vadGate = SileroVadGate.create(context)
                    if (vadGate != null) {
                        turnDetector = VoiceTurnDetector(
                            vadGate,
                            silenceFramesToEnd = (END_SILENCE_SECONDS * 1000 / 32).toInt(),
                            onSpeechStart = {
                                mainHandler.post {
                                    turnListener?.invoke(TurnEvent.SPEECH_START)
                                }
                            },
                            onSpeechEnd = {
                                mainHandler.post {
                                    turnListener?.invoke(TurnEvent.SPEECH_END)
                                }
                            }
                        )
                    }
                }
                record = MicRecorder.createAudioRecord()
                if (record == null) {
                    mainHandler.post {
                        listening = false
                        onError?.invoke("Microphone start nahi ho saka — dobara try karo.")
                    }
                    return@Thread
                }
                record.startRecording()
                val maxSamples = MAX_UTTERANCE_SECONDS * MicRecorder.SAMPLE_RATE
                val collected = FloatArray(maxSamples)
                var filled = 0
                var heardSpeech = false
                var silentFrames = 0
                val silenceFramesToEnd = (END_SILENCE_SECONDS * 1000 / 32).toInt()
                val chunk = ShortArray(1024)
                val vadFrame = FloatArray(SileroVadGate.WINDOW_SAMPLES)
                var vadFilled = 0
                while (!stopRequested && filled < maxSamples) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) break
                    val floats = MicRecorder.pcm16ToFloat(chunk, read)
                    val take = minOf(floats.size, maxSamples - filled)
                    floats.copyInto(collected, filled, 0, take)
                    filled += take
                    // Speech / silence tracking, frame by frame.
                    for (sample in floats) {
                        vadFrame[vadFilled++] = sample
                        if (vadFilled < SileroVadGate.WINDOW_SAMPLES) continue
                        vadFilled = 0
                        if (turnDetector != null) {
                            val wasSpeaking = turnDetector.isSpeaking
                            turnDetector.feed(vadFrame)
                            if (turnDetector.isSpeaking) heardSpeech = true
                            if (wasSpeaking && !turnDetector.isSpeaking) {
                                silentFrames = silenceFramesToEnd // turn ended
                            }
                        } else {
                            var sum = 0f
                            for (v in vadFrame) sum += v * v
                            val rms = sqrt(sum / vadFrame.size)
                            if (rms > ENERGY_SPEECH_THRESHOLD) {
                                if (!heardSpeech) {
                                    heardSpeech = true
                                    mainHandler.post {
                                        turnListener?.invoke(TurnEvent.SPEECH_START)
                                    }
                                }
                                silentFrames = 0
                            } else if (heardSpeech) {
                                silentFrames++
                            }
                        }
                    }
                    // End the utterance after sustained silence, but
                    // only once something was actually said; a quiet
                    // room ends after 4 s of nothing.
                    if (heardSpeech && silentFrames >= silenceFramesToEnd) break
                    if (!heardSpeech && filled >= MicRecorder.SAMPLE_RATE * 4) break
                }
                val audio = collected.copyOf(filled)
                audioListener?.invoke(audio)
                val text = if (heardSpeech && audio.isNotEmpty()) {
                    transcribe(audio)
                } else {
                    ""
                }
                mainHandler.post {
                    listening = false
                    onResult(text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Moonshine listen failed", e)
                mainHandler.post {
                    listening = false
                    onError?.invoke("On-device sunna nahi ho saka — dobara try karo.")
                }
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { vadGate?.release() }
            }
        }, "moonshine-listen").start()
    }

    /** Cancel an in-flight [listenOnce] round. */
    fun stopListening() {
        stopRequested = true
    }

    fun release() {
        stopRequested = true
        runCatching { recognizer.release() }
    }
}
