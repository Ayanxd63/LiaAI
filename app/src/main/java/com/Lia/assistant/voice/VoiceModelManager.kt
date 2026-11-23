package com.Lia.assistant.voice

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * V21 — on-device voice model catalogue + downloader.
 *
 * V19 restored Pocket TTS only. V21 adds the Hindi voice fix:
 * Supertonic 3 Hindi TTS (~139 MB) + Shruti Urdu STT (~51 MiB,
 * Urdu pack first) + Shruti Hindi STT (~51 MiB, fallback). Moonshine
 * STT (English), the VAD gate, speaker voice-match, laugh detection
 * and memory embeddings stay OUT (V18 diet) — their model
 * descriptors were deleted, so nothing in the app can download or
 * reference them.
 *
 * Same rules as the V15 offline LLM: a model is NEVER bundled in
 * the APK. Its files download once, on Wi-Fi only, into the app's
 * external files dir (`voice_models/<dir>/`), with resume support
 * (HTTP Range) and SHA-256 verification before first use.
 * A corrupt finish is deleted and reported; a partial download
 * resumes on the next try.
 *
 * URL + sizes + SHA-256 below were verified live against the
 * Hugging Face API on 2026-11-23 (LFS oids are the file SHA-256s;
 * small non-LFS files were downloaded and hashed on the spot).
 * Licences: Pocket TTS int8 (sherpa export of Kyutai Pocket TTS):
 * CC-BY-4.0; Supertonic 3: MIT (Supertone Inc.); Shruti: Apache-2.0
 * (UDGAM Labs) — attribution shown in the On-device voice screen.
 *
 * Totals: Pocket TTS ~191 MB, Supertonic 3 ~139 MB, Shruti Urdu
 * ~51.4 MiB, Shruti Hindi ~50.9 MiB — downloaded ONLY when the
 * user taps Download in Settings -> On-device voice. The APK
 * itself stays small — no model ever ships inside it.
 *
 * V19's "Hindi gap" note is superseded: Supertonic 3 IS a native
 * Hindi on-device voice (sherpa-onnx native support, 31 languages
 * incl. Hindi), and it is now the default when downloaded. The
 * Indus Hindi Pocket TTS finetune still has no published ONNX
 * export sherpa-onnx can load, so it is not used; the Laghu TTS 7M
 * Hindi ONNX model (Abhay2310/laghu-tts-7m-hindi, CC-BY-4.0,
 * ~30 MB) was evaluated as the fallback and NOT built — Supertonic
 * won (native sherpa-onnx support, no custom ONNX-Runtime glue
 * needed). System TTS stays available as the last fallback.
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

    /**
     * V21 — Hindi TTS (Supertonic 3 int8). sherpa-onnx native
     * Supertonic support; the voice fix. Total 145,317,917 bytes.
     * (Supertone Inc., MIT licence — see V17-LICENCES.md.)
     */
    val MODEL_SUPERTONIC_TTS = VoiceModel(
        id = "supertonic-tts",
        displayName = "Hindi voice (on-device)",
        dirName = "supertonic-tts",
        licence = "MIT (Supertonic 3, Supertone Inc.)",
        files = listOf(
            VoiceModelFile(
                "duration_predictor.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/duration_predictor.int8.onnx",
                3_700_147L,
                "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"
            ),
            VoiceModelFile(
                "text_encoder.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/text_encoder.int8.onnx",
                36_416_150L,
                "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"
            ),
            VoiceModelFile(
                "vector_estimator.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/vector_estimator.int8.onnx",
                78_400_833L,
                "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"
            ),
            VoiceModelFile(
                "vocoder.int8.onnx",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/vocoder.int8.onnx",
                25_991_073L,
                "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"
            ),
            VoiceModelFile(
                "voice.bin",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/voice.bin",
                517_168L,
                "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"
            ),
            VoiceModelFile(
                "unicode_indexer.bin",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/unicode_indexer.bin",
                262_144L,
                "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"
            ),
            VoiceModelFile(
                "tts.json",
                "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/tts.json",
                8_253L,
                "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"
            )
        )
    )

    /**
     * V21 — Urdu STT (Shruti, Moonshine pack). The listening side
     * of the voice fix: hears his spoken Urdu as Urdu. Total
     * 53,891,294 bytes. (UDGAM Labs, Apache-2.0.)
     */
    val MODEL_SHRUTI_URDU = VoiceModel(
        id = "shruti-stt-urdu",
        displayName = "Urdu sunne wala (on-device)",
        dirName = "shruti-stt-urdu",
        licence = "Apache-2.0 (Shruti, UDGAM Labs)",
        files = listOf(
            VoiceModelFile(
                "cached_decode.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/ur/sherpa-onnx/cached_decode.onnx",
                32_801_767L,
                "112b8f6c848e842d7f1bb4435d8810ab6e08303911946c53c8b9da15afccb3bc"
            ),
            VoiceModelFile(
                "encode.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/ur/sherpa-onnx/encode.onnx",
                19_637_087L,
                "2e9954cbe5eb84efa2c0a492fd630848d4052d75032e23515ec2243d9c5ad736"
            ),
            VoiceModelFile(
                "preprocess.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/ur/sherpa-onnx/preprocess.onnx",
                912_310L,
                "859b1bac985a6c0a9bff5b0ee520779e5ed14adbdcbe9188c664d3e918fb2203"
            ),
            VoiceModelFile(
                "uncached_decode.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/ur/sherpa-onnx/uncached_decode.onnx",
                421_454L,
                "bb50aaeffa579692be00448ec51ba24b73589864fe7e2c42cf3f1c6cf991b510"
            ),
            VoiceModelFile(
                "tokens.txt",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/ur/sherpa-onnx/tokens.txt",
                118_676L,
                "59613996b916de8b84eb0fa0c90b0b4956c2a1bc75a8d648719ecd7438c99b86"
            )
        )
    )

    /**
     * V21 — Hindi STT (Shruti, Moonshine pack). Fallback when the
     * Urdu pack is not downloaded. Total 53,319,232 bytes.
     * (UDGAM Labs, Apache-2.0.)
     */
    val MODEL_SHRUTI_HINDI = VoiceModel(
        id = "shruti-stt-hindi",
        displayName = "Hindi sunne wala (on-device)",
        dirName = "shruti-stt-hindi",
        licence = "Apache-2.0 (Shruti, UDGAM Labs)",
        files = listOf(
            VoiceModelFile(
                "cached_decode.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/hi/sherpa-onnx/cached_decode.onnx",
                32_241_067L,
                "5bf4a6cd95a15bea7506f1bc6c159a42c4ef9a8ed3fc1239e66ee19de4e1b0b3"
            ),
            VoiceModelFile(
                "encode.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/hi/sherpa-onnx/encode.onnx",
                19_637_087L,
                "7ea1ff910d43a70463bc1938cc8dc75438df851787de9bd0ada84c9d6e427754"
            ),
            VoiceModelFile(
                "preprocess.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/hi/sherpa-onnx/preprocess.onnx",
                912_310L,
                "859b1bac985a6c0a9bff5b0ee520779e5ed14adbdcbe9188c664d3e918fb2203"
            ),
            VoiceModelFile(
                "uncached_decode.onnx",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/hi/sherpa-onnx/uncached_decode.onnx",
                418_654L,
                "e28312e760ac79dad7e28046ba4dabfb9b1adb6d8b01bef8059bd9c71b5242be"
            ),
            VoiceModelFile(
                "tokens.txt",
                "$HF/udgamlabs/Shruti-ONNX/resolve/main/hi/sherpa-onnx/tokens.txt",
                110_114L,
                "7dde32df5c450b1cc7440bd614625d9ba57aaedcb9c6d4dc0e3fedbca2f116c8"
            )
        )
    )

    /** All V21 models: Pocket TTS + Hindi TTS + Urdu/Hindi STT. */
    val MODELS: List<VoiceModel> = listOf(
        MODEL_POCKET_TTS,
        MODEL_SUPERTONIC_TTS,
        MODEL_SHRUTI_URDU,
        MODEL_SHRUTI_HINDI
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
                runCatching { sha256Hex(f) == file.sha256 }
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
        if (!isWifiConnected(context)) {
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
                runCatching { sha256Hex(target) == file.sha256 }
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
        // The model just became ready — drop the cached verdict so the
        // voice chain sees it at once (see isModelReadyCached).
        invalidateReadyCache(model)
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
            if (sha256Hex(part) != file.sha256) {
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
        invalidateReadyCache(model)
    }
    // -- Local helpers (V19): these lived in the V15 OfflineModelManager
    //    (deleted in the V18 diet). Same implementations, moved here so
    //    the Pocket TTS download path is self-contained. ----------------

    /** SHA-256 of a file, hex, or "" on any error. */
    fun sha256Hex(file: File): String {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buffer).also { n = it } != -1) {
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    /** True when the active network is Wi-Fi (the only allowed download route). */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Free RAM right now (0 when unknown — the caller lets the load try). */
    fun freeRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /**
     * V19: cached readiness. [isModelReady] SHA-256-checks ~191 MB of
     * model files — far too slow to run on every voice reply. The
     * result is cached per process; [invalidateReadyCache] clears it
     * whenever a download finishes or a model is deleted, so the next
     * check re-verifies from disk.
     */
    private val readyCache = mutableMapOf<String, Boolean>()

    fun isModelReadyCached(context: Context, model: VoiceModel): Boolean {
        readyCache[model.id]?.let { return it }
        val ready = isModelReady(context, model)
        readyCache[model.id] = ready
        return ready
    }

    fun invalidateReadyCache(model: VoiceModel? = null) {
        if (model == null) readyCache.clear() else readyCache.remove(model.id)
    }
}
