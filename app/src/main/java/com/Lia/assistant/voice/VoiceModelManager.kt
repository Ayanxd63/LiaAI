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
 * V19 — on-device voice model catalogue + downloader (Pocket TTS only).
 *
 * Restored from V17, stripped to the ONE engine V19 brings back:
 * Pocket TTS (Lia's on-device voice). Moonshine STT, the VAD gate,
 * speaker voice-match, laugh detection and memory embeddings stay OUT
 * (V18 diet) — their model descriptors were deleted here, so nothing
 * in the app can download or reference them.
 *
 * Same rules as the V15 offline LLM: the model is NEVER bundled in
 * the APK. Its files download once, on Wi-Fi only, into the app's
 * external files dir (`voice_models/pocket-tts/`), with resume
 * support (HTTP Range) and SHA-256 verification before first use.
 * A corrupt finish is deleted and reported; a partial download
 * resumes on the next try.
 *
 * URL + sizes + SHA-256 below were verified live against the
 * Hugging Face API on 2026-11-22 (LFS oids are the file SHA-256s;
 * small non-LFS files were downloaded and hashed on the spot).
 * Licence: Pocket TTS int8 (sherpa export of Kyutai Pocket TTS):
 * CC-BY-4.0 — attribution required, shown in the On-device voice
 * screen.
 *
 * Total: ~191 MB, downloaded ONLY when the user taps Download in
 * Settings -> On-device voice. The APK itself stays small — the
 * model never ships inside it.
 *
 * Hindi/Hinglish gap (honest): the Kyutai Pocket TTS model has NO
 * Hindi — it speaks English (+ fr/de/pt/it/es) and reads Roman Urdu
 * with English pronunciation. The Indus Hindi Pocket TTS finetune
 * (CC-BY-4.0) has no published ONNX export sherpa-onnx can load, so
 * a native Hindi on-device voice is a future export job, not V19.
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

    /** All V19 models: Pocket TTS only. */
    val MODELS: List<VoiceModel> = listOf(
        MODEL_POCKET_TTS
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
