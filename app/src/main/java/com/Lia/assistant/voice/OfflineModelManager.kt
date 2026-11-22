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
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * V15 — on-device LLM model manager (offline AI).
 *
 * The model is NEVER bundled in the APK (it would add ~0.5 GB to every
 * install). It downloads once, on Wi-Fi only, into the app's external
 * files dir, with resume support (HTTP Range), SHA-256 verification
 * before first use, and a delete option in Settings.
 *
 * First model: Qwen2.5-0.5B-Instruct Q4_K_M (Apache-2.0, ~0.46 GB).
 * File + SHA-256 verified live against the Hugging Face API 2026-11-22.
 * A 1B tier is NOT listed in V15: the Gemma 3 1B GGUF downloads on
 * Hugging Face are license-gated (they need an accepted licence and
 * an account), so the app cannot fetch them anonymously — the tier
 * stays open for V16 (e.g. an ungated Qwen 0.6B).
 *
 * RAM gate (plan §1): the 0.5B model needs ~0.7 GB peak. Loading is
 * refused politely when free RAM is below model size + 500 MB reserve.
 * 4 GB phones run the 0.5B model only; 8 GB phones could run a 1B
 * model when one is listed.
 */
data class OfflineModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Minimum total device RAM (bytes) to offer this model. */
    val minTotalRamBytes: Long
)

object OfflineModelManager {

    private const val TAG = "OfflineModel"

    const val MODEL_DIR_NAME = "offline_models"

    /** Qwen2.5-0.5B-Instruct Q4_K_M — verified 2026-11-22. */
    val MODEL_QWEN_0_5B = OfflineModel(
        id = "qwen2.5-0.5b-q4_k_m",
        displayName = "Qwen 0.5B (offline, small + fast)",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 491_400_032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        minTotalRamBytes = 0L // offered on every phone; the load-time gate decides
    )

    /** Models offered in V15, in order. */
    val MODELS: List<OfflineModel> = listOf(MODEL_QWEN_0_5B)

    /** Reserve kept free besides the model itself when loading. */
    private const val RAM_RESERVE_BYTES = 500L * 1024L * 1024L

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null), MODEL_DIR_NAME).apply { mkdirs() }

    fun modelFile(context: Context, model: OfflineModel): File =
        File(modelDir(context), model.fileName)

    private fun partFile(context: Context, model: OfflineModel): File =
        File(modelDir(context), model.fileName + ".part")

    /** True only when the full file exists AND its SHA-256 matches. */
    fun isModelReady(context: Context, model: OfflineModel = MODEL_QWEN_0_5B): Boolean {
        val file = modelFile(context, model)
        if (!file.exists() || file.length() != model.sizeBytes) return false
        return runCatching { sha256Hex(file) == model.sha256 }.getOrDefault(false)
    }

    /** Total device RAM in bytes (0 when unknown). */
    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /** Free RAM right now in bytes (0 when unknown). */
    fun freeRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /**
     * Load-time RAM gate: free RAM must cover the model file plus the
     * 500 MB reserve (runtime + KV cache). Returns null when loading
     * is fine, or a plain-language reason when it is not.
     */
    fun loadBlockedReason(context: Context, model: OfflineModel = MODEL_QWEN_0_5B): String? {
        val free = freeRamBytes(context)
        if (free == 0L) return null // unknown — let the load try
        return if (free < model.sizeBytes + RAM_RESERVE_BYTES) {
            "Phone ki memory kam hai — offline model load nahi ho sakta. " +
                "Kuch apps band kar ke dobara try karo."
        } else {
            null
        }
    }

    /** True when the active network is Wi-Fi (the only allowed download route). */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Bytes already downloaded (0 when nothing is cached). */
    fun downloadedBytes(context: Context, model: OfflineModel = MODEL_QWEN_0_5B): Long {
        val part = partFile(context, model)
        return if (part.exists()) part.length() else 0L
    }

    /**
     * Download (or resume) the model. Wi-Fi only — refused otherwise.
     * [onProgress] gets 0..1 while bytes arrive. Runs on IO. A failed
     * download keeps its partial file so the next try resumes instead
     * of starting over; a corrupt finish is deleted and reported.
     */
    suspend fun downloadModel(
        context: Context,
        model: OfflineModel = MODEL_QWEN_0_5B,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady(context, model)) return@withContext Result.success(Unit)
        if (!isWifiConnected(context)) {
            return@withContext Result.failure(
                IllegalStateException("Offline model sirf Wi-Fi par download hota hai — Wi-Fi on kar ke dobara try karo.")
            )
        }
        val part = partFile(context, model)
        val existing = if (part.exists()) part.length() else 0L
        try {
            val requestBuilder = Request.Builder().url(model.downloadUrl).get()
            if (existing > 0L && existing < model.sizeBytes) {
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
                            onProgress?.invoke(readSoFar.toFloat() / model.sizeBytes)
                        }
                    }
                }
            }
            if (part.length() != model.sizeBytes) {
                return@withContext Result.failure(
                    IllegalStateException("Download adhoora reh gaya — dobara try karo (resume hoga).")
                )
            }
            if (sha256Hex(part) != model.sha256) {
                part.delete()
                return@withContext Result.failure(
                    IllegalStateException("Download kharab ho gaya (checksum galat) — dobara try karo.")
                )
            }
            val target = modelFile(context, model)
            if (!part.renameTo(target)) {
                // Rename across filesystems can fail: copy instead.
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Offline model download failed", e)
            Result.failure(e)
        }
    }

    /** Delete the downloaded model (and any partial download). */
    fun deleteModel(context: Context, model: OfflineModel = MODEL_QWEN_0_5B) {
        runCatching { modelFile(context, model).delete() }
        runCatching { partFile(context, model).delete() }
    }

    /** SHA-256 of a file, lowercase hex. Never throws (returns "" then). */
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
}
