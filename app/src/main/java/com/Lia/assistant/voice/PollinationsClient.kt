package com.Lia.assistant.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * V12 — the no-key AI engine: Pollinations.ai.
 *
 * Pollinations is a free, open-source gen-AI gateway. Its CLASSIC
 * endpoints need no sign-up and no API key at all, verified live from
 * this VM on 2026-11-22:
 *
 * - Text chat: POST https://text.pollinations.ai/openai (OpenAI-compatible
 *   chat completions), model "openai" — alias of "openai-fast" (GPT-OSS
 *   20B). Anonymous tier ("user_tier":"anonymous" in the response).
 *   A plain GET to https://text.pollinations.ai/{prompt}?model=openai
 *   works too. Answer came back in ~1-5s.
 * - Image: GET https://image.pollinations.ai/prompt/{prompt}?model=flux
 *   returns a JPEG in ~5s, no key. Model "flux" is the free default.
 *
 * What does NOT work without a key (also verified live, so do not try):
 * - The new unified API https://gen.pollinations.ai needs a key:
 *   POST /v1/audio/speech without a key answers HTTP 401 "A valid API
 *   key is required", and POST /v1/chat/completions hangs. Classic TTS
 *   model "openai-audio" was REMOVED from the Classic API (HTTP 404
 *   "isn't on the Classic API"). So voice output on the no-key path is
 *   Android's built-in TextToSpeech ([CuteVoice]), never Pollinations.
 * - Video ("wan-fast") is not used: an anonymous video call returned a
 *   still JPEG, and video is dropped in V12 anyway.
 *
 * Anonymous access is rate-limited to roughly 1 request per 15s per IP,
 * so overload/rate-limit failures are retried with backoff and surfaced
 * with a friendly Roman Urdu status, mirroring [GeminiTextClient].
 * No key is stored, sent, or logged anywhere in this file.
 */
object PollinationsClient {

    private const val TEXT_URL = "https://text.pollinations.ai/openai"

    /**
     * Free anonymous chat model. "openai" is the alias of "openai-fast"
     * (GPT-OSS 20B, tier "anonymous" in the live /models list).
     */
    private const val TEXT_MODEL = "openai"

    private const val IMAGE_BASE_URL = "https://image.pollinations.ai/prompt/"

    /** Free image model (verified live, no key). */
    private const val IMAGE_MODEL = "flux"

    /** Retries on overload / rate-limit / network blips. */
    private const val MAX_ATTEMPTS = 3

    /** Backoff between retries: 2s, 5s, 10s — same as GeminiTextClient. */
    private val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // V19 (S1 fix): was 180s — a hung first attempt could wedge
        // the voice overlay for 3 x 180s. 30s is the backstop; the
        // voice turn's own 30s deadline cancels the call sooner (see
        // executeCancellable). Text chat shares this client and also
        // benefits: a hung reply can never block longer than 30s per
        // attempt now.
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * V19 (S1 fix) — execute [request] so that cancelling the calling
     * coroutine CANCELS the HTTP call itself. The old blocking
     * `execute()` ignored coroutine cancellation entirely, so the V15
     * `withTimeoutOrNull(20s)` around `chat()` could never fire
     * mid-request — the overlay wedged on "Soch rahi hun…" for up to
     * 9 minutes (3 attempts x 180s read timeout). With `enqueue()` +
     * `invokeOnCancellation { call.cancel() }` the 30s voice deadline
     * is real: the call dies within milliseconds of the timeout.
     */
    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    private data class AttemptOutcome(val result: ChatReplyResult, val retryable: Boolean)

    /**
     * One chat turn against Pollinations, with the same retry behaviour
     * as [GeminiTextClient.reply]: up to [MAX_ATTEMPTS] tries on the
     * primary model with backoff, then a short friendly error.
     * [onRetrying] is invoked on each retry (attempt number, 1-based) so
     * the UI can show a friendly "retrying..." state while waiting.
     *
     * V15 (fast replies): [retryDelaysMs] lets voice use shorter waits
     * (1s/2s) than text chat; errors carry [ChatReplyResult.Error]
     * flags so the caller can fall back to the offline engine at once
     * on a rate-limit / network failure instead of waiting out the
     * whole retry loop.
     */
    suspend fun chat(
        systemInstruction: String,
        history: List<ChatTurn>,
        onRetrying: ((attempt: Int) -> Unit)? = null,
        retryDelaysMs: LongArray = RETRY_DELAYS_MS
    ): ChatReplyResult {
        if (history.isEmpty()) {
            return ChatReplyResult.Error("No conversation history provided.")
        }

        var lastOutcome: AttemptOutcome? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            val outcome = attemptChat(systemInstruction, history)
            if (outcome.result is ChatReplyResult.Success || !outcome.retryable) {
                return outcome.result
            }
            lastOutcome = outcome
            if (attempt < MAX_ATTEMPTS - 1) {
                onRetrying?.invoke(attempt + 1)
                delay(retryDelaysMs.getOrElse(attempt) { RETRY_DELAYS_MS.last() })
            }
        }

        val busy = lastOutcome?.retryable == true
        return if (busy) {
            ChatReplyResult.Error(
                "Lia is very busy right now — please try again in a minute.",
                isRateLimited = true
            )
        } else {
            lastOutcome?.result
                ?: ChatReplyResult.Error("Lia is very busy right now — please try again in a minute.")
        }
    }

    /**
     * V15 (fast replies) — the same chat turn, streamed: each token
     * chunk is handed to [onToken] (accumulated text so far) as it
     * arrives, so the UI shows the reply growing instead of waiting
     * for the whole answer. The OpenAI-compatible endpoint answers
     * Server-Sent Events when `"stream": true` is sent.
     *
     * A stream that dies mid-way with text already shown still counts
     * as a (partial) success — a half reply beats an error. A failure
     * before the first token retries exactly like [chat], and the final
     * error carries the same fallback flags.
     */
    suspend fun chatStream(
        systemInstruction: String,
        history: List<ChatTurn>,
        onToken: (String) -> Unit,
        onRetrying: ((attempt: Int) -> Unit)? = null,
        retryDelaysMs: LongArray = RETRY_DELAYS_MS
    ): ChatReplyResult {
        if (history.isEmpty()) {
            return ChatReplyResult.Error("No conversation history provided.")
        }

        var lastOutcome: AttemptOutcome? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            val outcome = attemptChatStream(systemInstruction, history, onToken)
            if (outcome.result is ChatReplyResult.Success || !outcome.retryable) {
                return outcome.result
            }
            lastOutcome = outcome
            if (attempt < MAX_ATTEMPTS - 1) {
                onRetrying?.invoke(attempt + 1)
                delay(retryDelaysMs.getOrElse(attempt) { RETRY_DELAYS_MS.last() })
            }
        }

        val busy = lastOutcome?.retryable == true
        return if (busy) {
            ChatReplyResult.Error(
                "Lia is very busy right now — please try again in a minute.",
                isRateLimited = true
            )
        } else {
            lastOutcome?.result
                ?: ChatReplyResult.Error("Lia is very busy right now — please try again in a minute.")
        }
    }

    /** One OpenAI-compatible chat-completions call to the Classic API. */
    private suspend fun attemptChat(
        systemInstruction: String,
        history: List<ChatTurn>
    ): AttemptOutcome {
        val request = buildChatRequest(systemInstruction, history, stream = false)

        try {
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    val reason = runCatching {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                    }.getOrNull().ifNullOrBlank { "HTTP error $code" }
                    val retryable = code == 503 || code == 429 || isOverloadMessage(reason)
                    return AttemptOutcome(
                        ChatReplyResult.Error(
                            friendlyChatError(code, reason),
                            isRateLimited = retryable
                        ),
                        retryable
                    )
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return AttemptOutcome(ChatReplyResult.Error("The model returned an empty response."), false)
                }
                val text = choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()
                return if (text.isNotBlank()) {
                    AttemptOutcome(ChatReplyResult.Success(text.trim()), false)
                } else {
                    AttemptOutcome(ChatReplyResult.Error("The model returned an empty reply."), false)
                }
            }
        } catch (e: CancellationException) {
            throw e // a timeout / session stop must cancel, never retry
        } catch (e: IOException) {
            return AttemptOutcome(
                ChatReplyResult.Error(
                    "Couldn't reach the free AI. Please check your internet connection.",
                    isNetworkError = true
                ),
                true
            )
        } catch (e: Exception) {
            return AttemptOutcome(
                ChatReplyResult.Error("Failed to talk to the free AI: ${e.localizedMessage ?: "Unknown error"}"),
                false
            )
        }
    }

    /** One streamed (SSE) chat-completions call to the Classic API. */
    private suspend fun attemptChatStream(
        systemInstruction: String,
        history: List<ChatTurn>,
        onToken: (String) -> Unit
    ): AttemptOutcome {
        val request = buildChatRequest(systemInstruction, history, stream = true)
        var streamedText = ""
        try {
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    val reason = runCatching {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                    }.getOrNull().ifNullOrBlank { "HTTP error $code" }
                    val retryable = code == 503 || code == 429 || isOverloadMessage(reason)
                    return AttemptOutcome(
                        ChatReplyResult.Error(
                            friendlyChatError(code, reason),
                            isRateLimited = retryable
                        ),
                        retryable
                    )
                }

                val body = response.body
                    ?: return AttemptOutcome(
                        ChatReplyResult.Error("The model returned an empty response."),
                        false
                    )
                val accumulated = StringBuilder()
                val rawText = StringBuilder()
                var sawSseData = false
                body.byteStream().bufferedReader().useLines { lines ->
                    for (line in lines) {
                        // V19: a slow-drip stream must still die on the
                        // voice deadline — check cancellation per line.
                        // (Bare ensureActive() does not resolve here:
                        // this is a suspend fun, not a CoroutineScope.)
                        currentCoroutineContext().ensureActive()
                        val data = line.trim()
                        if (!data.startsWith("data:")) {
                            // Not an SSE line: keep it in case the server
                            // answered with plain JSON instead of a stream.
                            rawText.append(line).append('\n')
                            continue
                        }
                        sawSseData = true
                        val payload = data.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val content = runCatching {
                            JSONObject(payload)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "")
                        }.getOrDefault("") ?: ""
                        if (content.isNotEmpty()) {
                            accumulated.append(content)
                            streamedText = accumulated.toString()
                            onToken(streamedText)
                        }
                    }
                }
                // The endpoint ignored "stream": true and answered with
                // plain JSON (one shot) — parse that instead of failing.
                if (accumulated.isEmpty() && !sawSseData && rawText.isNotBlank()) {
                    val fallbackText = runCatching {
                        JSONObject(rawText.toString())
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                    }.getOrDefault("") ?: ""
                    if (fallbackText.isNotBlank()) {
                        streamedText = fallbackText.trim()
                        onToken(streamedText)
                        return AttemptOutcome(ChatReplyResult.Success(streamedText), false)
                    }
                }
                val text = accumulated.toString().trim()
                return if (text.isNotBlank()) {
                    AttemptOutcome(ChatReplyResult.Success(text), false)
                } else {
                    AttemptOutcome(ChatReplyResult.Error("The model returned an empty reply."), false)
                }
            }
        } catch (e: CancellationException) {
            throw e // a timeout / session stop must cancel, never retry
        } catch (e: IOException) {
            // Mid-stream drop after text already arrived: keep the
            // partial reply instead of showing an error.
            if (streamedText.isNotBlank()) {
                return AttemptOutcome(ChatReplyResult.Success(streamedText.trim()), false)
            }
            return AttemptOutcome(
                ChatReplyResult.Error(
                    "Couldn't reach the free AI. Please check your internet connection.",
                    isNetworkError = true
                ),
                true
            )
        } catch (e: Exception) {
            if (streamedText.isNotBlank()) {
                return AttemptOutcome(ChatReplyResult.Success(streamedText.trim()), false)
            }
            return AttemptOutcome(
                ChatReplyResult.Error("Failed to talk to the free AI: ${e.localizedMessage ?: "Unknown error"}"),
                false
            )
        }
    }

    /** Shared request builder for the chat and streamed-chat calls. */
    private fun buildChatRequest(
        systemInstruction: String,
        history: List<ChatTurn>,
        stream: Boolean
    ): Request {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstruction)
        })
        for (turn in history) {
            messagesArray.put(JSONObject().apply {
                put("role", if (turn.isUser) "user" else "assistant")
                put("content", turn.text)
            })
        }
        val root = JSONObject().apply {
            put("model", TEXT_MODEL)
            put("messages", messagesArray)
            put("max_tokens", 800)
            if (stream) put("stream", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        return Request.Builder()
            .url(TEXT_URL)
            .post(root.toString().toRequestBody(mediaType))
            .build()
    }

    /** True when an error message means "overloaded / try again later". */
    private fun isOverloadMessage(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("overload") ||
            lower.contains("high demand") ||
            lower.contains("rate limit") ||
            lower.contains("too many requests") ||
            lower.contains("temporarily") ||
            lower.contains("try again later") ||
            lower.contains("unavailable")
    }

    private fun friendlyChatError(code: Int, reason: String): String {
        return when {
            code == 429 -> "Bohat sare log ek saath use kar rahe hain — thoda wait karo, phir try karo."
            code == 503 -> "Free AI abhi busy hai — thoda wait karo, phir try karo."
            reason.isNotBlank() && reason.length < 160 -> reason
            else -> "Free AI se baat nahi ho saki. Please try again."
        }
    }

    private fun String?.ifNullOrBlank(default: () -> String): String {
        return if (this.isNullOrBlank()) default() else this
    }

    /**
     * Generate an image from [prompt] with the free Flux model — no key.
     * The prompt goes in the URL path, so it is URL-encoded (spaces as
     * %20, not '+', which is literal inside a path segment).
     * One retry after overload / rate-limit / network blips.
     */
    suspend fun generateImage(
        prompt: String,
        width: Int = 1024,
        height: Int = 1024
    ): ImageGenResult {
        if (prompt.isBlank()) {
            return ImageGenResult.Error("Please describe the image you want.")
        }

        val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val url = "$IMAGE_BASE_URL$encoded?model=$IMAGE_MODEL&width=$width&height=$height&nologo=true"

        repeat(2) { attempt ->
            val result = attemptImage(url)
            if (result is ImageGenResult.Success) return result
            if (result is ImageGenResult.Error && isOverloadMessage(result.message) && attempt == 0) {
                delay(2_000L)
            } else {
                return result
            }
        }
        return ImageGenResult.Error("Lia is very busy right now — please try again in a minute.")
    }

    private fun attemptImage(url: String): ImageGenResult {
        val request = Request.Builder().url(url).get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    val reason = runCatching {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                    }.getOrNull().ifNullOrBlank { "HTTP error $code" }
                    return ImageGenResult.Error(friendlyImageError(code, reason))
                }
                val contentType = response.header("Content-Type", "image/jpeg") ?: "image/jpeg"
                if (!contentType.startsWith("image/")) {
                    return ImageGenResult.Error("No image came back. Try a different prompt.")
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) {
                    return ImageGenResult.Error("No image came back. Try a different prompt.")
                }
                return ImageGenResult.Success(bytes, contentType.substringBefore(";").trim())
            }
        } catch (e: IOException) {
            return ImageGenResult.Error("Couldn't reach the free AI. Please check your internet connection.")
        } catch (e: Exception) {
            return ImageGenResult.Error("Failed to create the image: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun friendlyImageError(code: Int, reason: String): String {
        val lower = reason.lowercase()
        return when {
            code == 429 || lower.contains("rate limit") ->
                "Bohat sare log ek saath use kar rahe hain — thoda wait karo, phir try karo."
            code == 503 || lower.contains("overload") ->
                "Free AI abhi busy hai — thoda wait karo, phir try karo."
            lower.contains("blocked") || lower.contains("safety") || lower.contains("filtered") ->
                "That prompt was blocked. Please try a different description."
            reason.isNotBlank() && reason.length < 160 -> reason
            else -> "Image generation failed. Please try again."
        }
    }
}
