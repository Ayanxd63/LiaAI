package com.Lia.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * V17 — on-device voice model catalogue + downloader.
 *
 * Same rules as the V15 offline LLM ([OfflineModelManager]): models
 * are NEVER bundled in the APK. Each engine's files download once,
 * on Wi-Fi only, into the app's external files dir
 * (`voice_models/<engine>/`), with resume support (HTTP Range) and
 * SHA-256 verification before first use. A corrupt finish is deleted
 * and reported; a partial download resumes on the next try.
 *
 * Every URL + size + SHA-256 below was verified live against the
 * Hugging Face API on 2026-11-22 (LFS oids are the file SHA-256s;
 * small non-LFS files were downloaded and hashed on the spot).
 * Licences were read from each repo's LICENSE file the same day:
 *
 * - Pocket TTS int8 (sherpa export of Kyutai Pocket TTS): CC-BY-4.0
 *   — attribution required, shown in the On-device voice screen.
 * - Moonshine tiny-en int8: MIT (Useful Sensors, 2024).
 * - Silero VAD v5: MIT.
 * - ERes2Net speaker embedding (3D-Speaker): Apache-2.0.
 * - Zipformer audio tagging: Apache-2.0.
 * - MiniLM-L6-v2 TFLite: Apache-2.0.
 *
 * Total if the user enables everything: ~455 MB of downloads, but
 * each engine downloads ONLY when its feature is turned on, and each
 * engine's files are deleted independently from Settings.
 *
 * Hindi/Hinglish gap (honest): the Kyutai Pocket TTS model has NO
 * Hindi — it speaks English (+ fr/de/pt/it/es) and reads Roman Urdu
 * with English pronunciation. The Indus Hindi Pocket TTS finetune
 * (CC-BY-4.0) has no published ONNX export sherpa-onnx can load, so
 * a native Hindi on-device voice is a V18 export job, not V17.
 * System TTS stays available for Hindi/Urdu text.
 */
data class VoiceModelFile(
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String
)

data class VoiceModel(
    val id: String,
    val displayName: String,
    val dirName: String,
    val licence: String,
    val files: List<VoiceModelFile>
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

object VoiceModelManager {

    private const val TAG = "VoiceModels"
    const val MODEL_DIR_NAME = "voice_models"

    private const val HF = "https://huggingface.co"

    /** Item 1 — on-device TTS (FLAGSHIP). ~191 MB. CC-BY-4.0. */
    val MODEL_POCKET_TTS = VoiceModel(
        id = "pocket-tts",
        displayName = "Lia voice (on-device)",
        dirName = "pocket-tts",
        licence = "CC-BY-4.0 (Kyutai Pocket TTS, via sherpa-onnx export)",
        files = listOf(
            VoiceModelFile(
                "decoder.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/decoder.int8.onnx",
                22_693_618L,
                "12b0857402d31aead94df19d6783b4350d1f740e811f3a3202c70ad89ae11eea"
            ),
            VoiceModelFile(
                "encoder.onnx",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/encoder.onnx",
                72_713_165L,
                "e8f2f6d301ffb96e398b138a7dc6d3038622d236044636b73d920bab85890260"
            ),
            VoiceModelFile(
                "lm_flow.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/lm_flow.int8.onnx",
                9_962_530L,
                "8d627d235c44a597da908e1085ebe241cbbe358964c502c5a5063d18851a5529"
            ),
            VoiceModelFile(
                "lm_main.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/lm_main.int8.onnx",
                76_341_079L,
                "bfc0c7e7e3d72864fa3bb2ee499f62f21ddc1474b885f5f3ca570f8be73e787e"
            ),
            VoiceModelFile(
                "text_conditioner.onnx",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/text_conditioner.onnx",
                16_388_343L,
                "0b84e837d7bfaf2c896627b03e3f080320309f37f4fc7df7698c644f7ba5e6b1"
            ),
            VoiceModelFile(
                "token_scores.json",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/token_scores.json",
                123_616L,
                "5be2f278caf9b9800741f0fd82bff677f4943ec764c356f907213434b622d958"
            ),
            VoiceModelFile(
                "vocab.json",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/vocab.json",
                69_478L,
                "6fb646346cf931016f70c4921aab0900ce7a304b893cb02135c74e294abfea01"
            ),
            // Voice reference audio (female samples from the same repo).
            // "Lia" = bria.wav (default), "Loona" = loona.wav.
            // These are reference recordings for the voice-cloning
            // conditioner, NOT Maya's voice samples.
            VoiceModelFile(
                "voice_lia.wav",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/test_wavs/bria.wav",
                2_152_986L,
                "4ff24b228cd0e44e9174689ce5c4aa8c00a570554aed39044cb76d9640e6896d"
            ),
            VoiceModelFile(
                "voice_loona.wav",
                "$HF/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/test_wavs/loona.wav",
                50_478L,
                "166323125381b84025786ae3eb2b313ec2451cf846fcabb5fac45ef268f05736"
            )
        )
    )

    /** Item 2 — on-device speech recognition (English only). ~118 MB. MIT. */
    val MODEL_MOONSHINE = VoiceModel(
        id = "moonshine-tiny-en",
        displayName = "On-device speech recognition (English)",
        dirName = "moonshine",
        licence = "MIT (Useful Sensors Moonshine)",
        files = listOf(
            VoiceModelFile(
                "preprocess.onnx",
                "$HF/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/preprocess.onnx",
                6_800_738L,
                "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e"
            ),
            VoiceModelFile(
                "encode.int8.onnx",
                "$HF/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/encode.int8.onnx",
                18_249_187L,
                "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb"
            ),
            VoiceModelFile(
                "uncached_decode.int8.onnx",
                "$HF/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/uncached_decode.int8.onnx",
                53_216_096L,
                "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41"
            ),
            VoiceModelFile(
                "cached_decode.int8.onnx",
                "$HF/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/cached_decode.int8.onnx",
                45_264_830L,
                "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6"
            ),
            VoiceModelFile(
                "tokens.txt",
                "$HF/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/tokens.txt",
                436_688L,
                "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea"
            )
        )
    )

    /** Item 6 — VAD gate (Silero VAD v5). ~2.3 MB. MIT. */
    val MODEL_VAD = VoiceModel(
        id = "silero-vad-v5",
        displayName = "Voice activity detection (VAD)",
        dirName = "vad",
        licence = "MIT (Silero VAD)",
        files = listOf(
            VoiceModelFile(
                "silero_vad.onnx",
                "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx",
                2_327_524L,
                "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
            )
        )
    )

    /** Item 3 — speaker embedding (ERes2Net, 3D-Speaker). ~38 MB. Apache-2.0. */
    val MODEL_SPEAKER = VoiceModel(
        id = "eres2net-base",
        displayName = "Speaker voice-match",
        dirName = "speaker",
        licence = "Apache-2.0 (3D-Speaker ERes2Net)",
        files = listOf(
            VoiceModelFile(
                "eres2net.onnx",
                "$HF/onemira/eres2net-base-3dspeaker-onnx/resolve/main/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                39_593_761L,
                "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b"
            )
        )
    )

    /** Item 4 — laugh detection (Zipformer audio tagging). ~65 MB. Apache-2.0. */
    val MODEL_AUDIO_TAGGING = VoiceModel(
        id = "zipformer-audio-tagging",
        displayName = "Laugh detection",
        dirName = "audiotag",
        licence = "Apache-2.0 (sherpa-onnx Zipformer audio tagging)",
        files = listOf(
            VoiceModelFile(
                "model.int8.onnx",
                "$HF/k2-fsa/sherpa-onnx-zipformer-audio-tagging-2024-04-09/resolve/main/model.int8.onnx",
                68_358_288L,
                "898adfe8591e2535c90bca3841f45b849b9553fbf8ffd8960f127e049f141c74"
            ),
            VoiceModelFile(
                "class_labels_indices.csv",
                "$HF/k2-fsa/sherpa-onnx-zipformer-audio-tagging-2024-04-09/resolve/main/class_labels_indices.csv",
                14_675L,
                "cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429"
            )
        )
    )

    /** Item 5 — memory embeddings (MiniLM-L6-v2, TFLite). ~22 MB. Apache-2.0. */
    val MODEL_MINILM = VoiceModel(
        id = "minilm-l6-v2",
        displayName = "Memory embeddings (semantic search)",
        dirName = "minilm",
        licence = "Apache-2.0 (sentence-transformers MiniLM)",
        files = listOf(
            VoiceModelFile(
                "sentence_transformer.tflite",
                "$HF/Madhur-Prakash-Mangal/all-MiniLM-L6-v2-tflite/resolve/main/sentence_transformer.tflite",
                22_793_792L,
                "5d464cae0e38e59f08ffeaf86b2a72ec459c74b3518425126eaf571ebc6c9367"
            ),
            VoiceModelFile(
                "vocab.txt",
                "$HF/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt",
                231_508L,
                "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"
            )
        )
    )

    /** All V17 models, in feature order. */
    val MODELS: List<VoiceModel> = listOf(
        MODEL_POCKET_TTS,
        MODEL_MOONSHINE,
        MODEL_VAD,
        MODEL_SPEAKER,
        MODEL_AUDIO_TAGGING,
        MODEL_MINILM
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun voiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), MODEL_DIR_NAME).apply { mkdirs() }

    fun engineDir(context: Context, model: VoiceModel): File =
        File(voiceDir(context), model.dirName).apply { mkdirs() }

    fun modelFile(context: Context, model: VoiceModel, file: VoiceModelFile): File =
        File(engineDir(context, model), file.fileName)

    private fun partFile(context: Context, model: VoiceModel, file: VoiceModelFile): File =
        File(engineDir(context, model), file.fileName + ".part")

    /** True only when every file exists with the right size AND SHA-256. */
    fun isModelReady(context: Context, model: VoiceModel): Boolean {
        return model.files.all { file ->
            val f = modelFile(context, model, file)
            f.exists() && f.length() == file.sizeBytes &&
                runCatching { OfflineModelManager.sha256Hex(f) == file.sha256 }
                    .getOrDefault(false)
        }
    }

    /** Bytes already on the phone for this engine (complete + partial). */
    fun downloadedBytes(context: Context, model: VoiceModel): Long {
        var total = 0L
        for (file in model.files) {
            val done = modelFile(context, model, file)
            val part = partFile(context, model, file)
            total += when {
                done.exists() -> done.length()
                part.exists() -> part.length()
                else -> 0L
            }
        }
        return total
    }

    /**
     * Download (or resume) every file of [model], in order. Wi-Fi only.
     * [onProgress] gets 0..1 across the whole engine. Runs on IO.
     * A failed file keeps its partial download so the next try resumes;
     * a corrupt finish is deleted and reported.
     */
    suspend fun downloadModel(
        context: Context,
        model: VoiceModel,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady(context, model)) return@withContext Result.success(Unit)
        if (!OfflineModelManager.isWifiConnected(context)) {
            return@withContext Result.failure(
                IllegalStateException(
                    "On-device voice sirf Wi-Fi par download hota hai — " +
                        "Wi-Fi on kar ke dobara try karo."
                )
            )
        }
        var doneSoFar = downloadedBytes(context, model)
        val total = model.totalBytes
        for (file in model.files) {
            val target = modelFile(context, model, file)
            if (target.exists() && target.length() == file.sizeBytes &&
                runCatching { OfflineModelManager.sha256Hex(target) == file.sha256 }
                    .getOrDefault(false)
            ) {
                continue // this file is already good
            }
            val result = downloadOneFile(context, model, file)
            if (result.isFailure) return@withContext Result.failure(
                result.exceptionOrNull() ?: IllegalStateException("Download failed")
            )
            doneSoFar = downloadedBytes(context, model)
            onProgress?.invoke(doneSoFar.toFloat() / total)
        }
        onProgress?.invoke(1f)
        Result.success(Unit)
    }

    private suspend fun downloadOneFile(
        context: Context,
        model: VoiceModel,
        file: VoiceModelFile
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val part = partFile(context, model, file)
        val existing = if (part.exists()) part.length() else 0L
        try {
            val requestBuilder = Request.Builder().url(file.downloadUrl).get()
            if (existing > 0L && existing < file.sizeBytes) {
                requestBuilder.addHeader("Range", "bytes=$existing-")
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed: HTTP ${response.code}")
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IllegalStateException("Download failed: empty response")
                )
                // Server ignored the Range header: restart from zero.
                val resumeFrom = if (response.code == 206) existing else 0L
                if (resumeFrom == 0L && existing > 0L) {
                    part.delete()
                }
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(resumeFrom)
                    var readSoFar = resumeFrom
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            raf.write(buffer, 0, n)
                            readSoFar += n
                        }
                    }
                }
            }
            if (part.length() != file.sizeBytes) {
                return@withContext Result.failure(
                    IllegalStateException("Download adhoora reh gaya — dobara try karo (resume hoga).")
                )
            }
            if (OfflineModelManager.sha256Hex(part) != file.sha256) {
                part.delete()
                return@withContext Result.failure(
                    IllegalStateException("Download kharab ho gaya (checksum galat) — dobara try karo.")
                )
            }
            val target = modelFile(context, model, file)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Voice model download failed: ${file.fileName}", e)
            Result.failure(e)
        }
    }

    /** Delete an engine's files (and any partial downloads). */
    fun deleteModel(context: Context, model: VoiceModel) {
        for (file in model.files) {
            runCatching { modelFile(context, model, file).delete() }
            runCatching { partFile(context, model, file).delete() }
        }
    }
}
