package com.goldai.assistant.voice

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatTurn(
    val isUser: Boolean,
    val text: String
)

sealed class ChatReplyResult {
    data class Success(val text: String) : ChatReplyResult()

    /**
     * V15: the flags let callers tell a rate-limit / network failure
     * (worth a fast offline fallback) from a hard failure (bad key,
     * empty reply) that must NOT fall back silently.
     */
    data class Error(
        val message: String,
        val isRateLimited: Boolean = false,
        val isNetworkError: Boolean = false
    ) : ChatReplyResult()
}

object GeminiTextClient {

    val internalHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedResolvedModel: String? = null

    /** Primary chat model — gemini-3.8-flash is the current model for new API users. */
    private const val PRIMARY_MODEL = "models/gemini-3.8-flash"

    /**
     * Fallback chat model, tried once when the primary stays overloaded after
     * all retries. gemini-3.6-flash is Stable, has a free tier and is Google's
     * own recommended alternative to 3.8 Flash (Gemini API model docs, Nov 2026).
     * Never 2.5/2.0-flash — both are retired for new API users.
     */
    private const val FALLBACK_MODEL = "models/gemini-3.6-flash"

    /** How many times to retry the primary model when it is overloaded. */
    private const val MAX_PRIMARY_ATTEMPTS = 3

    /** Backoff between retries on an overloaded model: 2s, 5s, 10s. */
    private val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

    /** Result of one generateContent attempt plus whether it is worth retrying. */
    private data class AttemptOutcome(val result: ChatReplyResult, val retryable: Boolean)

    suspend fun resolveModel(apiKey: String): String {
        cachedResolvedModel?.let { return it }

        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            internalHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val fallback = PRIMARY_MODEL
                    cachedResolvedModel = fallback
                    return fallback
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val models = json.optJSONArray("models") ?: JSONArray()

                var flashCandidate: String? = null
                var anyGenerateCandidate: String? = null

                for (i in 0 until models.length()) {
                    val modelObj = models.getJSONObject(i)
                    val name = modelObj.optString("name", "")
                    val methods = modelObj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var canGenerate = false
                    for (m in 0 until methods.length()) {
                        if (methods.getString(m) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }

                    if (canGenerate) {
                        if (anyGenerateCandidate == null) {
                            anyGenerateCandidate = name
                        }
                        val lowerName = name.lowercase()
                        if (lowerName.contains("flash") &&
                            !lowerName.contains("live") &&
                            !lowerName.contains("image") &&
                            !lowerName.contains("tts") &&
                            !lowerName.contains("native-audio")
                        ) {
                            // Prefer the newest Flash generation first.
                            // gemini-2.5-flash is no longer available to new
                            // API users, so it must never win over a 3.x model.
                            if (flashCandidate == null ||
                                flashScore(lowerName) > flashScore(flashCandidate!!.lowercase())
                            ) {
                                flashCandidate = name
                            }
                        }
                    }
                }

                val chosen = flashCandidate ?: anyGenerateCandidate ?: PRIMARY_MODEL
                cachedResolvedModel = chosen
                return chosen
            }
        } catch (_: Exception) {
            val fallback = PRIMARY_MODEL
            cachedResolvedModel = fallback
            return fallback
        }
    }

    /**
     * Rank Flash models by generation so the newest available one wins:
     * gemini-3.8-flash (100) > other gemini-3.x (90) > gemini-2.5 (50) >
     * gemini-2.0 (40) > any other flash model (10).
     */
    private fun flashScore(lowerName: String): Int = when {
        lowerName.contains("3.8") -> 100
        lowerName.contains("3.") -> 90
        lowerName.contains("2.5") -> 50
        lowerName.contains("2.0") -> 40
        else -> 10
    }

    /**
     * Send one chat turn to Gemini with automatic recovery from overload:
     * - Retries the primary model up to [MAX_PRIMARY_ATTEMPTS] times with
     *   backoff when the API answers 503/429 (high demand is temporary).
     * - Then tries the fallback Flash model once.
     * - Only then returns a short friendly error instead of the raw API text.
     * [onRetrying] is invoked on each retry (attempt number, 1-based) so the
     * UI can show a friendly "retrying..." state while waiting.
     * Non-retryable failures (bad key, bad request) still return immediately.
     */
    suspend fun reply(
        apiKey: String,
        systemInstruction: String,
        history: List<ChatTurn>,
        onRetrying: ((attempt: Int) -> Unit)? = null
    ): ChatReplyResult {
        if (apiKey.isBlank()) {
            return ChatReplyResult.Error("Gemini API key is missing. Please add it in Settings.")
        }
        if (history.isEmpty()) {
            return ChatReplyResult.Error("No conversation history provided.")
        }

        val primaryModel = runCatching { resolveModel(apiKey) }.getOrDefault(PRIMARY_MODEL)

        // 1. Retry the primary model with backoff — overload spikes are temporary.
        var lastOutcome: AttemptOutcome? = null
        for (attempt in 0 until MAX_PRIMARY_ATTEMPTS) {
            val outcome = attemptReply(apiKey, systemInstruction, history, primaryModel)
            if (outcome.result is ChatReplyResult.Success || !outcome.retryable) {
                return outcome.result
            }
            lastOutcome = outcome
            if (attempt < MAX_PRIMARY_ATTEMPTS - 1) {
                onRetrying?.invoke(attempt + 1)
                delay(RETRY_DELAYS_MS[attempt])
            }
        }

        // 2. Primary still overloaded: one try on the fallback Flash model.
        val fallbackOutcome = attemptReply(apiKey, systemInstruction, history, FALLBACK_MODEL)
        if (fallbackOutcome.result is ChatReplyResult.Success) {
            return fallbackOutcome.result
        }

        // 3. Both models busy: short friendly message, never the raw API error.
        val busy = (lastOutcome?.retryable == true) || fallbackOutcome.retryable
        return if (busy) {
            ChatReplyResult.Error("Lia is very busy right now — please try again in a minute.")
        } else {
            fallbackOutcome.result
        }
    }

    /** True when an API error message means "overloaded / try again later". */
    private fun isOverloadMessage(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("overload") ||
            lower.contains("high demand") ||
            lower.contains("temporarily") ||
            lower.contains("try again later") ||
            lower.contains("unavailable")
    }

    /**
     * One generateContent call against [modelName]. The returned [AttemptOutcome]
     * marks overload / rate-limit / network failures as retryable; anything
     * else (bad key, bad request, empty/filtered reply) is final.
     */
    private fun attemptReply(
        apiKey: String,
        systemInstruction: String,
        history: List<ChatTurn>,
        modelName: String
    ): AttemptOutcome {
        val modelEndpoint = if (modelName.startsWith("models/")) modelName else "models/$modelName"
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelEndpoint:generateContent?key=$apiKey"

        val root = JSONObject()

        // System Instruction
        val sysInstructionObj = JSONObject()
        val sysParts = JSONArray()
        sysParts.put(JSONObject().apply { put("text", systemInstruction) })
        sysInstructionObj.put("parts", sysParts)
        root.put("systemInstruction", sysInstructionObj)

        // Contents
        val contentsArray = JSONArray()
        for (turn in history) {
            val contentObj = JSONObject()
            contentObj.put("role", if (turn.isUser) "user" else "model")
            val partsArray = JSONArray()
            partsArray.put(JSONObject().apply { put("text", turn.text) })
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }
        root.put("contents", contentsArray)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = root.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            internalHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    val reason = runCatching {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                    }.getOrNull() ?: "HTTP error $code"
                    val retryable = code == 503 || code == 429 || isOverloadMessage(reason)
                    return AttemptOutcome(ChatReplyResult.Error(reason), retryable)
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return AttemptOutcome(ChatReplyResult.Error("The model returned an empty response."), false)
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    return if (text.isNotBlank()) {
                        AttemptOutcome(ChatReplyResult.Success(text), false)
                    } else {
                        AttemptOutcome(ChatReplyResult.Error("The model returned an empty reply."), false)
                    }
                }

                return AttemptOutcome(ChatReplyResult.Error("The response was filtered or unavailable."), false)
            }
        } catch (e: IOException) {
            // Network blip — worth one more try, not a final failure.
            return AttemptOutcome(
                ChatReplyResult.Error("Couldn't reach Gemini. Please check your internet connection."),
                true
            )
        } catch (e: Exception) {
            return AttemptOutcome(
                ChatReplyResult.Error("Failed to communicate with Gemini: ${e.localizedMessage ?: "Unknown error"}"),
                false
            )
        }
    }
}
