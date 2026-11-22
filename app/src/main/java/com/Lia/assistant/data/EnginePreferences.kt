package com.Lia.assistant.data

/**
 * V12 — which AI engine answers chat and image requests.
 *
 * POLLINATIONS (default): free, no key, works the moment the app opens.
 * GEMINI: the user's own Gemini key, better quality — only when a key
 * is saved AND the user picked Gemini in Settings. A key alone never
 * switches the engine; the default stays Pollinations so the app never
 * asks a new user for a key.
 */
enum class ChatEngine {
    POLLINATIONS,
    GEMINI
}

object EnginePreferences {
    private const val KEY_ENGINE = "chat_engine"

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
}
