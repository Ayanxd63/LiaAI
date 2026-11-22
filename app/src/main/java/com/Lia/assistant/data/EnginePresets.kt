package com.Lia.assistant.data

import android.content.Context
import com.Lia.assistant.voice.OfflineModelManager

/**
 * V16 — engine presets: prompt + behaviour presets over Lia's free
 * engines (Pollinations / offline / Gemini-when-keyed). A preset drives
 * the fallback ORDER (which engine is tried first) and adds one short
 * style instruction to the chat/voice prompt.
 *
 * - FAST: offline first (no network wait — the quickest reply on the
 *   phone), then Pollinations, then Gemini. Best when the user wants
 *   speed above all.
 * - WARM (default): the user's chosen engine first, then Pollinations,
 *   then offline, then Gemini. This is exactly the V15 behaviour, so
 *   nothing changes for existing users.
 * - SMART: Gemini first when a key is saved (best quality), otherwise
 *   Pollinations, then offline.
 *
 * Only engines that are actually available are listed: Gemini needs a
 * saved key, Offline needs the downloaded model. Pollinations is always
 * available (free, no key).
 */
enum class EnginePreset(val displayName: String, val tagline: String) {
    FAST(
        "Fast",
        "Sab se tez jawab — offline pehle, chhote jawab"
    ),
    WARM(
        "Warm",
        "Garm, pyaar bhara lehja — tumhara chosen engine pehle"
    ),
    SMART(
        "Smart",
        "Sab se behtareen jawab — Gemini pehle (key ho to)"
    )
}

object EnginePresetPreferences {

    fun getPreset(context: Context): EnginePreset {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.ENGINE_PRESET,
            EnginePreset.WARM.name
        )
        return try {
            EnginePreset.valueOf(stored)
        } catch (_: Exception) {
            EnginePreset.WARM
        }
    }

    fun setPreset(context: Context, preset: EnginePreset) {
        NovaPreferences.putString(context, NovaPreferences.Keys.ENGINE_PRESET, preset.name)
    }

    /** One short style instruction for the prompt, per preset. */
    fun instruction(preset: EnginePreset): String = when (preset) {
        EnginePreset.FAST ->
            "Reply style: very short — one or two sentences at most, " +
                "direct, no extra detail. Speed matters more than completeness."
        EnginePreset.WARM ->
            "Reply style: warm and affectionate, like a caring girlfriend " +
                "talking to someone she loves. A little playful, never cold " +
                "or formal."
        EnginePreset.SMART ->
            "Reply style: think carefully and give the most accurate, " +
                "complete answer. Explain briefly when it helps understanding."
    }

    private fun isUsable(context: Context, engine: ChatEngine): Boolean = when (engine) {
        ChatEngine.POLLINATIONS -> true
        ChatEngine.GEMINI -> ApiKeyStore.hasKey(context)
        ChatEngine.OFFLINE -> OfflineModelManager.isModelReady(context)
    }

    /**
     * Engines to try, in order, for the current preset. The caller tries
     * each in turn and moves to the next only on a rate-limit / network
     * error — a hard error (bad key, empty reply) stops the chain.
     */
    fun resolveEngines(context: Context): List<ChatEngine> {
        val chosen = EnginePreferences.getEngine(context)
        val offlineReady = OfflineModelManager.isModelReady(context)
        val geminiReady = ApiKeyStore.hasKey(context)
        val ordered = mutableListOf<ChatEngine>()
        fun add(engine: ChatEngine) {
            if (engine !in ordered && isUsable(context, engine)) ordered += engine
        }
        when (getPreset(context)) {
            EnginePreset.FAST -> {
                if (offlineReady) add(ChatEngine.OFFLINE)
                add(ChatEngine.POLLINATIONS)
                if (geminiReady) add(ChatEngine.GEMINI)
            }
            EnginePreset.SMART -> {
                if (geminiReady) add(ChatEngine.GEMINI)
                add(ChatEngine.POLLINATIONS)
                if (offlineReady) add(ChatEngine.OFFLINE)
            }
            EnginePreset.WARM -> {
                add(chosen)
                add(ChatEngine.POLLINATIONS)
                if (offlineReady) add(ChatEngine.OFFLINE)
                if (geminiReady) add(ChatEngine.GEMINI)
            }
        }
        return ordered
    }
}
