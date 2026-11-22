package com.Lia.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * V14 — the on-device wake-word voice model (Vosk).
 *
 * The model is NOT bundled in the APK (it would add ~40 MB to every
 * install for a feature that is off by default). It downloads once,
 * the first time the user turns always-listening on, into the app's
 * private files dir — never on external storage, never shared.
 *
 * Model: vosk-model-small-en-us-0.15 (small English, ~40 MB zip).
 * Only "Hey Lia" is ever listened for — the recognizer runs in grammar
 * mode with the phrase list ["hey lia", "hey leah", "hey liya", "[unk]"],
 * so it is a keyword spotter, not a transcriber: ambient speech is
 * recognised as [unk] and ignored on-device.
 *
 * V15 hook: a second, larger model is NOT needed for the wake word.
 * The offline LLM work (lia-v15-plan.md) uses its own model files
 * under a separate dir — do not reuse this manager for that.
 */
object WakeWordModelManager {

    private const val TAG = "WakeWordModel"

    /** The model folder name inside the zip and on disk. */
    const val MODEL_NAME = "vosk-model-small-en-us-0.15"

    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_NAME)

    /**
     * A model is ready only when the acoustic model file exists — a
     * half-downloaded or half-unzipped dir does not count.
     */
    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, "am/final.mdl").exists() &&
            File(dir, "graph").exists()
    }

    /**
     * Download + unzip the model. [onProgress] gets 0..1 while the bytes
     * arrive (-1 when the server gives no content length). Runs on IO.
     * A failed or cancelled download deletes its partial files, so a
     * later retry starts clean and [isModelReady] never sees a broken dir.
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        val tempFile = File(context.cacheDir, "$MODEL_NAME.zip")
        try {
            val request = Request.Builder().url(MODEL_URL).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed: HTTP ${response.code}")
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IllegalStateException("Download failed: empty response")
                )
                val total = body.contentLength()
                var readSoFar = 0L
                FileOutputStream(tempFile).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            out.write(buffer, 0, n)
                            readSoFar += n
                            if (total > 0) onProgress?.invoke(readSoFar.toFloat() / total)
                        }
                    }
                }
            }
            onProgress?.invoke(1f)
            unzipModel(tempFile, context.filesDir)
            if (!isModelReady(context)) {
                return@withContext Result.failure(
                    IllegalStateException("Downloaded model is incomplete — please retry.")
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Wake-word model download failed", e)
            runCatching { tempFile.delete() }
            runCatching { dir.deleteRecursively() }
            Result.failure(e)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun deleteModel(context: Context) {
        runCatching { modelDir(context).deleteRecursively() }
    }

    /**
     * Unzip into [targetDir]. The zip's top folder IS the model folder,
     * so entries land at filesDir/vosk-model-small-en-us-0.15/...
     * Zip-slip guarded: every entry must stay inside [targetDir].
     */
    private fun unzipModel(zipFile: File, targetDir: File) {
        val targetCanonical = targetDir.canonicalPath
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                require(outFile.canonicalPath.startsWith(targetCanonical)) {
                    "Unsafe zip entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
