package com.Lia.assistant.data

/**
 * V12 — which AI engine answers chat and image requests.
 *
 * POLLINATIONS (default): free, no key, works the moment the app opens.
 * GEMINI: the user's own Gemini key, better quality — only when a key
 * is saved AND the user picked Gemini in Settings. A key alone never
 * switches the engine; the default stays Pollinations so the app never
 * asks a new user for a key.
 *
 * V15 — OFFLINE: the on-device llama.cpp model (Qwen2.5-0.5B). No key,
 * no internet — but only after the model is downloaded (Settings >
 * Offline AI, Wi-Fi only) and only on phones whose ABI shipped the
 * native library. Image stays online-only in every mode.
 */
enum class ChatEngine {
    POLLINATIONS,
    GEMINI,
    OFFLINE
}

object EnginePreferences {
    private const val KEY_ENGINE = "chat_engine"
    private const val KEY_OFFLINE_FALLBACK = "offline_fallback_enabled"

    fun getEngine(context: android.content.Context): ChatEngine {
        val stored = NovaPreferences.getString(context, KEY_ENGINE, ChatEngine.POLLINATIONS.name)
        return try {
            ChatEngine.valueOf(stored)
        } catch (_: Exception) {
            ChatEngine.POLLINATIONS
        }
    }

    fun setEngine(context: android.content.Context, engine: ChatEngine) {
        NovaPreferences.putString(context, KEY_ENGINE, engine.name)
    }

    /** True only when Gemini is picked AND a key is actually saved. */
    fun useGemini(context: android.content.Context): Boolean {
        return getEngine(context) == ChatEngine.GEMINI && ApiKeyStore.hasKey(context)
    }

    /** True only when Offline is picked AND a verified model is on the phone. */
    fun useOffline(context: android.content.Context): Boolean {
        if (getEngine(context) != ChatEngine.OFFLINE) return false
        return com.Lia.assistant.voice.OfflineModelManager.isModelReady(context)
    }

    /**
     * V15 — automatic offline fallback (default ON): when the chosen
     * online engine fails with a NETWORK error and an offline model is
     * downloaded, answer offline and say so in plain language. Non-
     * network errors (bad key, overload) never fall back silently.
     */
    fun isOfflineFallbackEnabled(context: android.content.Context): Boolean {
        return NovaPreferences.getBoolean(context, KEY_OFFLINE_FALLBACK, true)
    }

    fun setOfflineFallbackEnabled(context: android.content.Context, enabled: Boolean) {
        NovaPreferences.putBoolean(context, KEY_OFFLINE_FALLBACK, enabled)
    }
}
