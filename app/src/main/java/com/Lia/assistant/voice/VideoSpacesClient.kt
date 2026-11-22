package com.Lia.assistant.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * One public video-generation Space that Lia can call.
 *
 * [fnIndex] and [buildData] come from the Space's live Gradio config
 * (/gradio_api/info), verified with curl on 2026-11-22 — never guessed:
 *
 * - CogVideoX-2B (zai-org/CogVideoX-2B-Space): /generate is fn_index 0,
 *   inputs [prompt: str, inference_steps: 50, guidance_scale: 6.0],
 *   outputs [video, download video, GIF]. Declared GPU duration 240s.
 * - CogVideoX-5B (zai-org/CogVideoX-5B-Space): /generate is fn_index 2,
 *   inputs [prompt, image=null, video=null, strength=0.8, seed=-1,
 *   super-resolution=false, frame-interpolation=false],
 *   outputs [video, download video, GIF, seed]. Declared GPU duration 300s.
 *
 * Both make a ~6 second clip (49 frames at 8fps).
 */
data class VideoSpace(
    val id: String,
    val name: String,
    val subtitle: String,
    val host: String,
    val fnIndex: Int,
    val buildData: (String) -> JSONArray
)

sealed class VideoGenResult {
    data class Success(val bytes: ByteArray) : VideoGenResult()
    data class Error(val message: String) : VideoGenResult()
    object Cancelled : VideoGenResult()
}

/**
 * Plain OkHttp client for Hugging Face Gradio Spaces (v11).
 *
 * Talks the Gradio 5 queue protocol directly — the same calls the Space's
 * own web page makes:
 *   POST {host}/gradio_api/queue/join  {data, event_data, fn_index,
 *       session_hash, trigger_id}  -> {"event_id": "..."}
 *   GET  {host}/gradio_api/queue/data?session_hash=...  (SSE stream)
 *       events: estimation, process_starts, process_completed,
 *       process_failed, heartbeat, unexpected_error
 *   GET  {file url}  (the video file from process_completed output)
 *
 * Verified live 2026-11-22, including the failure modes:
 * - A browser-like User-Agent is REQUIRED: Python's default UA got HTTP 403
 *   on /queue/join, a browser UA got HTTP 200 with an event_id.
 * - Anonymous (no token) jobs are REJECTED by the ZeroGPU scheduler:
 *   "ZeroGPU illegal duration" and "You have exceeded your ZeroGPU quota
 *   (300s requested vs. 0s left). Authenticate with a Hugging Face token
 *   for more quota". So a free HF token is required for video — the client
 *   sends it as `Authorization: Bearer <token>` on every call, and the
 *   UI says so plainly instead of pretending "no key" works.
 * - Spaces run on several replicas: the SSE stream sometimes answers
 *   "404: Session not found" when a request lands on the wrong replica.
 *   The client re-opens the stream (up to 3 times) instead of failing.
 * - There is no working /queue/cancel endpoint (it 404s). Cancelling
 *   means closing the SSE stream — the server drops the job when the
 *   client disconnects.
 *
 * No token or key is stored or logged here — the caller passes the user's
 * own token from [com.Lia.assistant.data.HuggingFaceStore] on every call.
 * Errors are mapped to short user-readable messages, never raw traces.
 */
object VideoSpacesClient {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** Overall deadline for one job (queue + generation). */
    private const val MAX_JOB_MS = 12 * 60 * 1000L

    /** Give up downloading a video bigger than this. */
    private const val MAX_VIDEO_BYTES = 100L * 1024L * 1024L

    /** How many times the SSE stream is re-opened after "Session not found". */
    private const val MAX_STREAM_RETRIES = 3

    val ALL_SPACES: List<VideoSpace> = listOf(
        VideoSpace(
            id = "cogvideox-2b",
            name = "CogVideoX-2B",
            subtitle = "Faster — 6 second clip",
            host = "https://zai-org-cogvideox-2b-space.hf.space",
            fnIndex = 0,
            buildData = { prompt ->
                JSONArray().apply {
                    put(prompt)
                    put(50.0)
                    put(6.0)
                }
            }
        ),
        VideoSpace(
            id = "cogvideox-5b",
            name = "CogVideoX-5B",
            subtitle = "Better quality — 6 second clip",
            host = "https://zai-org-cogvideox-5b-space.hf.space",
            fnIndex = 2,
            buildData = { prompt ->
                JSONArray().apply {
                    put(prompt)
                    put(JSONObject.NULL)
                    put(JSONObject.NULL)
                    put(0.8)
                    put(-1)
                    put(false)
                    put(false)
                }
            }
        )
    )

    fun spaceById(id: String): VideoSpace =
        ALL_SPACES.firstOrNull { it.id == id } ?: ALL_SPACES.first()

    private val joinClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** SSE client: 60s read timeout so a silent stream wakes the loop
     * regularly (cancel + deadline checks); the stream itself stays open. */
    private val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authHeaders(builder: Request.Builder, token: String): Request.Builder {
        builder.header("User-Agent", BROWSER_UA)
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${token.trim()}")
        }
        return builder
    }

    /**
     * Run one generation end-to-end (blocking — call from Dispatchers.IO).
     *
     * [onStatus] is called from the worker thread with short progress text;
     * the caller posts it to the main thread. [isCancelled] is polled while
     * waiting; returning true closes the stream and the server drops the job.
     */
    fun generate(
        space: VideoSpace,
        prompt: String,
        token: String,
        onStatus: (String) -> Unit,
        isCancelled: () -> Boolean
    ): VideoGenResult {
        if (token.isBlank()) {
            return VideoGenResult.Error(
                "A free Hugging Face token is needed for video. " +
                    "Add one in Settings — Hugging Face."
            )
        }
        if (prompt.isBlank()) {
            return VideoGenResult.Error("Please describe the video first.")
        }
        val startedAt = System.currentTimeMillis()
        fun elapsed(): String {
            val s = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            return "%d:%02d".format(s / 60, s % 60)
        }

        val sessionHash = randomSessionHash()

        // ---- 1. Join the queue -------------------------------------------
        val joinBody = JSONObject().apply {
            put("data", space.buildData(prompt.trim()))
            put("event_data", JSONObject.NULL)
            put("fn_index", space.fnIndex)
            put("session_hash", sessionHash)
            put("trigger_id", JSONObject.NULL)
        }
        val joinRequest = authHeaders(
            Request.Builder().url("${space.host}/gradio_api/queue/join"),
            token
        )
            .post(joinBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val eventId: String = try {
            joinClient.newCall(joinRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return VideoGenResult.Error(joinHttpError(response.code))
                }
                val body = response.body?.string() ?: ""
                JSONObject(body).optString("event_id", "").trim()
            }
        } catch (_: IOException) {
            return VideoGenResult.Error(
                "Couldn't reach Hugging Face. Check your internet connection."
            )
        } catch (_: Exception) {
            return VideoGenResult.Error(
                "Couldn't start the video. Please try again."
            )
        }
        if (eventId.isEmpty()) {
            return VideoGenResult.Error(
                "Hugging Face didn't accept the video request. Please try again."
            )
        }
        onStatus("Request sent — waiting in queue… (${elapsed()})")

        // ---- 2. Read the SSE stream (re-open on replica misses) ----------
        var streamRetries = 0
        while (true) {
            if (isCancelled()) return VideoGenResult.Cancelled
            if (System.currentTimeMillis() - startedAt > MAX_JOB_MS) {
                return VideoGenResult.Error(
                    "The video took too long (over 12 minutes). " +
                        "The free queue is very busy — please try again later."
                )
            }
            val streamRequest = authHeaders(
                Request.Builder()
                    .url("${space.host}/gradio_api/queue/data?session_hash=$sessionHash"),
                token
            )
                .header("Accept", "text/event-stream")
                .get()
                .build()
            val outcome: VideoGenResult? = try {
                sseClient.newCall(streamRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Replica miss or a dead stream — retry the open.
                        streamRetries++
                        if (streamRetries > MAX_STREAM_RETRIES) {
                            return VideoGenResult.Error(
                                "Lost connection to Hugging Face mid-generation. " +
                                    "Please try again."
                            )
                        }
                        null // re-open the stream
                    } else {
                        readSseStream(
                            response, eventId, space, token,
                            onStatus, isCancelled, startedAt, ::elapsed
                        )
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Silent stream for 60s — not an error. Loop re-checks
                // cancel/deadline, then re-opens the stream.
                streamRetries++
                if (streamRetries > MAX_STREAM_RETRIES * 4) {
                    return VideoGenResult.Error(
                        "Hugging Face stopped answering. Please try again."
                    )
                }
                null
            } catch (_: IOException) {
                streamRetries++
                if (streamRetries > MAX_STREAM_RETRIES) {
                    return VideoGenResult.Error(
                        "Connection to Hugging Face dropped. Check your internet and try again."
                    )
                }
                null
            }
            if (outcome != null) return outcome
            // outcome == null means "re-open the stream and keep waiting".
            onStatus("Connection wobbled — reconnecting… (${elapsed()})")
        }
    }

    /**
     * Reads one SSE stream until process_completed / process_failed, a
     * session-not-found retry, cancel, or the deadline. Returns null when
     * the caller should re-open the stream and keep waiting.
     */
    private fun readSseStream(
        response: okhttp3.Response,
        eventId: String,
        space: VideoSpace,
        token: String,
        onStatus: (String) -> Unit,
        isCancelled: () -> Boolean,
        startedAt: Long,
        elapsed: () -> String
    ): VideoGenResult? {
        val source = response.body?.source() ?: return VideoGenResult.Error(
            "Hugging Face sent an empty reply. Please try again."
        )
        val eventLines = ArrayList<String>()
        while (true) {
            if (isCancelled()) return VideoGenResult.Cancelled
            if (System.currentTimeMillis() - startedAt > MAX_JOB_MS) {
                return VideoGenResult.Error(
                    "The video took too long (over 12 minutes). " +
                        "The free queue is very busy — please try again later."
                )
            }
            val line: String? = try {
                source.readUtf8Line()
            } catch (_: SocketTimeoutException) {
                // 60s of silence — bubble up as "re-open the stream".
                throw SocketTimeoutException("sse idle")
            }
            if (line == null) {
                // Stream closed by the server — re-open and keep waiting.
                return null
            }
            if (line.isBlank()) {
                if (eventLines.isEmpty()) continue
                val payload = eventLines
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trim() }
                eventLines.clear()
                if (payload.isBlank()) continue
                when (val outcome = handleSseEvent(
                    payload, eventId, space, token, onStatus, elapsed
                )) {
                    SseOutcome.RetryStream -> return null
                    is SseOutcome.Done -> return outcome.result
                    SseOutcome.Continue -> { /* keep reading this stream */ }
                }
            } else {
                eventLines.add(line)
            }
        }
    }

    /** One SSE event's outcome: keep reading, re-open the stream (this
     * replica lost the session), or finish with a terminal result. */
    private sealed interface SseOutcome {
        object Continue : SseOutcome
        object RetryStream : SseOutcome
        data class Done(val result: VideoGenResult) : SseOutcome
    }

    /** Handles one parsed SSE event. */
    private fun handleSseEvent(
        payload: String,
        eventId: String,
        space: VideoSpace,
        token: String,
        onStatus: (String) -> Unit,
        elapsed: () -> String
    ): SseOutcome {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return SseOutcome.Continue
        val msg = json.optString("msg", "")
        val evId = json.optString("event_id", "")
        // Events for other jobs on the same session are ignored.
        if (evId.isNotEmpty() && evId != eventId) return SseOutcome.Continue
        return when (msg) {
            "estimation" -> {
                val rank = json.optInt("rank", -1)
                val queueSize = json.optInt("queue_size", -1)
                val eta = json.optDouble("rank_eta", Double.NaN)
                val etaText = if (!eta.isNaN() && eta > 0) {
                    " — about ${eta.toInt()}s left in queue"
                } else ""
                onStatus(
                    if (rank >= 0) {
                        "Queue mein number ${rank + 1} of $queueSize$etaText… (${elapsed()})"
                    } else {
                        "Waiting in queue… (${elapsed()})"
                    }
                )
                SseOutcome.Continue
            }
            "process_starts" -> {
                onStatus("Video ban rahi hai… (${elapsed()})")
                SseOutcome.Continue
            }
            "process_completed" -> {
                val success = json.optBoolean("success", false)
                val output = json.opt("output")
                if (!success) {
                    val rawError = when (output) {
                        is JSONObject -> output.optString("error", "")
                        is String -> output
                        else -> ""
                    }
                    SseOutcome.Done(VideoGenResult.Error(mapZeroGpuError(rawError)))
                } else {
                    val fileUrl = extractVideoUrl(space, output)
                    if (fileUrl == null) {
                        SseOutcome.Done(
                            VideoGenResult.Error(
                                "The video finished but no video file came back. " +
                                    "Please try again."
                            )
                        )
                    } else {
                        SseOutcome.Done(downloadVideo(fileUrl, token))
                    }
                }
            }
            "process_failed" -> {
                val rawError = json.optString("message", "")
                    .ifBlank { json.optString("error", "") }
                SseOutcome.Done(VideoGenResult.Error(mapZeroGpuError(rawError)))
            }
            "unexpected_error" -> {
                val message = json.optString("message", "")
                if (message.contains("Session not found", ignoreCase = true)) {
                    // Wrong replica — re-open the stream and keep waiting.
                    SseOutcome.RetryStream
                } else {
                    SseOutcome.Done(VideoGenResult.Error(mapZeroGpuError(message)))
                }
            }
            // heartbeat + anything else: keep waiting.
            else -> SseOutcome.Continue
        }
    }

    /** Pull the downloadable video URL out of a process_completed output. */
    private fun extractVideoUrl(space: VideoSpace, output: Any?): String? {
        val array = output as? JSONArray ?: return null
        // outputs: [video, download video, GIF, (seed)]. The plain download
        // file (index 1) is preferred; the video component (index 0) is the
        // fallback.
        val candidates = listOf(
            array.optJSONObject(1),
            array.optJSONObject(0)?.optJSONObject("video"),
            array.optJSONObject(0)
        )
        for (candidate in candidates) {
            val url = candidate?.optString("url", "")?.trim().orEmpty()
            if (url.isNotEmpty()) return resolveFileUrl(space.host, url)
        }
        return null
    }

    /** Gradio returns relative file urls like
     * "/gradio_api/file=/tmp/gradio/.../video.mp4" — make them absolute. */
    private fun resolveFileUrl(host: String, url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> host + url
            else -> "$host/$url"
        }
    }

    private fun downloadVideo(fileUrl: String, token: String): VideoGenResult {
        val request = authHeaders(
            Request.Builder().url(fileUrl),
            token
        ).get().build()
        return try {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return VideoGenResult.Error(
                        "The video was made but couldn't be downloaded " +
                            "(code ${response.code}). Please try again."
                    )
                }
                val length = response.body?.contentLength() ?: -1L
                if (length > MAX_VIDEO_BYTES) {
                    return VideoGenResult.Error(
                        "The video file is unexpectedly large. Please try again."
                    )
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty() || bytes.size > MAX_VIDEO_BYTES) {
                    VideoGenResult.Error(
                        "The downloaded video was empty. Please try again."
                    )
                } else {
                    VideoGenResult.Success(bytes)
                }
            }
        } catch (_: IOException) {
            VideoGenResult.Error(
                "The video was made but the download failed. " +
                    "Check your internet and try again."
            )
        } catch (_: Exception) {
            VideoGenResult.Error(
                "The video was made but couldn't be saved. Please try again."
            )
        }
    }

    /** Check a token with GET /api/whoami. Returns the account name on
     * success, or an error message. Used by the settings screen's Test. */
    fun validateToken(token: String): Result<String> {
        if (token.isBlank()) {
            return Result.failure(IllegalArgumentException("No token entered."))
        }
        val request = authHeaders(
            Request.Builder().url("https://huggingface.co/api/whoami"),
            token.trim()
        ).get().build()
        return try {
            joinClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(
                        IllegalStateException(
                            when (response.code) {
                                401 -> "That token is not valid. Check it and try again."
                                else -> "Hugging Face returned an error (code ${response.code})."
                            }
                        )
                    )
                }
                val body = response.body?.string() ?: ""
                val name = JSONObject(body).optString("name", "").trim()
                if (name.isEmpty()) {
                    Result.failure(IllegalStateException("That token did not return a username."))
                } else {
                    Result.success(name)
                }
            }
        } catch (_: IOException) {
            Result.failure(
                IOException("Couldn't reach Hugging Face. Check your internet connection.")
            )
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Couldn't check the token. Please try again."))
        }
    }

    private fun joinHttpError(code: Int): String = when (code) {
        401 -> "That Hugging Face token is not valid. Check it in Settings — Hugging Face."
        403 -> "Hugging Face blocked this request. Please try again in a few minutes."
        429 -> "Hugging Face is busy right now. Please wait a moment and try again."
        else -> "Hugging Face returned an error (code $code). Please try again."
    }

    /**
     * The ZeroGPU scheduler's own error text, translated into plain words.
     * The raw text is kept only when it is already user-readable.
     */
    private fun mapZeroGpuError(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            return "The video could not be made. Please try again."
        }
        val lower = text.lowercase()
        return when {
            "quota" in lower ->
                "Aaj ki free limit khatam ho gayi hai. Free Hugging Face " +
                    "account par roz sirf ~1 chhoti video banti hai — " +
                    "kal dobara try karein."
            "illegal duration" in lower ->
                "Ye Space is waqt free account par nahi chal raha — " +
                    "usko zyada GPU time chahiye. Dusra model try karein " +
                    "ya baad mein dobara koshish karein."
            "no cuda" in lower || "no gpus are available" in lower ->
                "Hugging Face ke free GPU abhi busy hain. " +
                    "Thodi der baad dobara try karein."
            else -> text.take(300)
        }
    }

    private fun randomSessionHash(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder()
        repeat(11) { sb.append(chars[Random.nextInt(chars.length)]) }
        return sb.toString()
    }
}
