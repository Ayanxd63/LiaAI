package com.Lia.assistant.voice

import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Image generation through the Gemini API, using the user's own key
 * (read via [com.Lia.assistant.data.ApiKeyStore] by the caller — never
 * hardcoded here). Mirrors [GeminiTextClient]'s HTTP/JSON approach and
 * error style: short user-readable messages, never raw stack traces.
 *
 * Primary model: `gemini-2.5-flash-image` (Nano Banana, GA Oct 2025) via
 * `:generateContent` with `generationConfig.responseModalities`
 * `["TEXT", "IMAGE"]`. The response carries the picture as base64 in
 * `candidates[0].content.parts[].inlineData.data`.
 *
 * Fallback model: `gemini-2.0-flash-preview-image-generation` — the
 * original Gemini image-generation model, same request/response shape.
 * It is tried once when the primary model is unavailable for this key
 * (404 / "not found"). Chat/Live models are untouched by this client.
 */
sealed class ImageGenResult {
    /** Raw image bytes plus the MIME type the API reported (usually image/png). */
    data class Success(val bytes: ByteArray, val mimeType: String, val text: String = "") : ImageGenResult()
    data class Error(val message: String) : ImageGenResult()
}

object GeminiImageClient {

    private const val PRIMARY_MODEL = "models/gemini-2.5-flash-image"
    private const val FALLBACK_MODEL = "models/gemini-2.0-flash-preview-image-generation"

    /** One retry after overload, matching the text client's backoff spirit. */
    private const val RETRY_DELAY_MS = 2_000L

    private data class AttemptOutcome(
        val result: ImageGenResult,
        val retryable: Boolean,
        val modelNotFound: Boolean
    )

    /**
     * Generate an image from [prompt]. When [inputImageBytes] is supplied
     * (with its [inputMimeType], e.g. "image/jpeg"), the model edits /
     * restyles that photo instead of creating from text alone.
     */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        inputImageBytes: ByteArray? = null,
        inputMimeType: String? = null
    ): ImageGenResult {
        if (apiKey.isBlank()) {
            return ImageGenResult.Error("Gemini API key is missing. Please add it in Settings.")
        }
        if (prompt.isBlank()) {
            return ImageGenResult.Error("Please describe the image you want.")
        }

        // 1. Primary model.
        val first = attempt(apiKey, prompt, PRIMARY_MODEL, inputImageBytes, inputMimeType)
        if (first.result is ImageGenResult.Success) return first.result

        // 2. Model unavailable for this key/account: try the fallback model once.
        if (first.modelNotFound) {
            val fallback = attempt(apiKey, prompt, FALLBACK_MODEL, inputImageBytes, inputMimeType)
            if (fallback.result is ImageGenResult.Success) return fallback.result
            return (fallback.result as ImageGenResult.Error).let {
                ImageGenResult.Error(it.message)
            }
        }

        // 3. Overloaded / rate-limited / network blip: one retry on the primary.
        if (first.retryable) {
            delay(RETRY_DELAY_MS)
            val retry = attempt(apiKey, prompt, PRIMARY_MODEL, inputImageBytes, inputMimeType)
            if (retry.result is ImageGenResult.Success) return retry.result
            if (retry.modelNotFound) {
                val fallback = attempt(apiKey, prompt, FALLBACK_MODEL, inputImageBytes, inputMimeType)
                if (fallback.result is ImageGenResult.Success) return fallback.result
            }
            return ImageGenResult.Error("Lia is very busy right now — please try again in a minute.")
        }

        return first.result
    }

    private fun attempt(
        apiKey: String,
        prompt: String,
        modelName: String,
        inputImageBytes: ByteArray?,
        inputMimeType: String?
    ): AttemptOutcome {
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelName:generateContent?key=$apiKey"

        val root = JSONObject()

        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        contentObj.put("role", "user")
        val partsArray = JSONArray()
        partsArray.put(JSONObject().apply { put("text", prompt) })
        if (inputImageBytes != null) {
            val inlineData = JSONObject().apply {
                put("mimeType", inputMimeType ?: "image/jpeg")
                put("data", Base64.encodeToString(inputImageBytes, Base64.NO_WRAP))
            }
            partsArray.put(JSONObject().apply { put("inlineData", inlineData) })
        }
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        root.put("contents", contentsArray)

        // Both image models need TEXT + IMAGE; IMAGE-only is rejected.
        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().apply {
                put("TEXT")
                put("IMAGE")
            })
        }
        root.put("generationConfig", generationConfig)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = root.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            GeminiTextClient.internalHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    val reason = runCatching {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message", "")
                    }.getOrNull() ?: "HTTP error $code"
                    val lower = reason.lowercase()
                    val modelNotFound = code == 404 ||
                        lower.contains("not found") ||
                        lower.contains("is not found") ||
                        lower.contains("does not support") ||
                        lower.contains("not supported")
                    val retryable = code == 503 || code == 429 ||
                        lower.contains("overload") ||
                        lower.contains("try again later") ||
                        lower.contains("temporarily") ||
                        lower.contains("unavailable")
                    return AttemptOutcome(ImageGenResult.Error(friendlyError(code, reason)), retryable, modelNotFound)
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return AttemptOutcome(
                        ImageGenResult.Error("The model returned an empty response."),
                        retryable = false,
                        modelNotFound = false
                    )
                }

                val parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    return AttemptOutcome(
                        ImageGenResult.Error("The image was filtered or unavailable. Try a different prompt."),
                        retryable = false,
                        modelNotFound = false
                    )
                }

                var imageBytes: ByteArray? = null
                var mimeType = "image/png"
                val textBuilder = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) textBuilder.append(text)
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val data = inlineData.optString("data", "")
                        if (data.isNotBlank()) {
                            imageBytes = Base64.decode(data, Base64.DEFAULT)
                            mimeType = inlineData.optString("mimeType", "image/png")
                                .ifBlank { "image/png" }
                        }
                    }
                }

                return if (imageBytes != null) {
                    AttemptOutcome(
                        ImageGenResult.Success(imageBytes, mimeType, textBuilder.toString()),
                        retryable = false,
                        modelNotFound = false
                    )
                } else {
                    AttemptOutcome(
                        ImageGenResult.Error("No image came back. Try a different prompt."),
                        retryable = false,
                        modelNotFound = false
                    )
                }
            }
        } catch (e: IOException) {
            return AttemptOutcome(
                ImageGenResult.Error("Couldn't reach Gemini. Please check your internet connection."),
                retryable = true,
                modelNotFound = false
            )
        } catch (e: Exception) {
            return AttemptOutcome(
                ImageGenResult.Error("Failed to communicate with Gemini: ${e.localizedMessage ?: "Unknown error"}"),
                retryable = false,
                modelNotFound = false
            )
        }
    }

    /** Short user-readable message for an API failure — never the raw body. */
    private fun friendlyError(code: Int, reason: String): String {
        val lower = reason.lowercase()
        return when {
            code == 400 && (lower.contains("api key") || lower.contains("api_key")) ->
                "Your Gemini API key looks invalid. Please check it in Settings."
            code == 401 || code == 403 && lower.contains("api key") ->
                "Your Gemini API key looks invalid. Please check it in Settings."
            code == 403 && lower.contains("quota") ->
                "Your Gemini free quota is used up for now. Please try again later."
            code == 403 ->
                "Gemini refused this request. Your key may not have access to image generation."
            code == 429 ->
                "Lia is very busy right now — please try again in a minute."
            code == 503 ->
                "Gemini is overloaded right now. Please try again in a minute."
            code == 404 ->
                "Image generation is not available for this key right now."
            lower.contains("blocked") || lower.contains("safety") || lower.contains("filtered") ->
                "That prompt was blocked. Please try a different description."
            reason.isNotBlank() && reason.length < 160 -> reason
            else -> "Image generation failed. Please try again."
        }
    }
}
