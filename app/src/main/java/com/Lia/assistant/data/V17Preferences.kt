package com.Lia.assistant.data

import android.content.Context

/**
 * V17 — voice output preferences, in the shared NovaPreferences file
 * (no new files, no new dependencies). Same pattern as V16.
 *
 * V18 diet: the on-device voice engine is gone (Pocket TTS, Moonshine
 * STT, speaker voice-match, laugh detection, memory embeddings and the
 * VAD gate were all removed). What is left:
 * - ONLINE: internet voice (Kokoro via mesmer.tools) — human-like,
 *   no key, ~20 generations/hour, needs internet. Default.
 * - SYSTEM: device TTS (cute voice) — last resort, always works.
 * A stored "POCKET" value from a V17 install maps to ONLINE via the
 * migration in [VoiceOutputPreferences.getEngine].
 */

/**
 * Which voice speaks Lia's no-key replies:
 * - ONLINE: internet voice (Kokoro via mesmer.tools) — human-like,
 *   no key, ~20 generations/hour, needs internet. Default.
 * - SYSTEM: device TTS (cute voice) — last resort, always works.
 */
enum class VoiceOutputEngine {
    ONLINE,
    SYSTEM
}

object VoiceOutputPreferences {

    fun getEngine(context: Context): VoiceOutputEngine {
        migrateToOnlineDefault(context)
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
            VoiceOutputEngine.ONLINE.name
        )
        return try {
            VoiceOutputEngine.valueOf(stored)
        } catch (_: Exception) {
            VoiceOutputEngine.ONLINE
        }
    }

    fun setEngine(context: Context, engine: VoiceOutputEngine) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
            engine.name
        )
    }

    /**
     * One-time migration (V17 online voice, kept in V18): before V17
     * the implicit default was SYSTEM. Users who never chose a voice
     * move to ONLINE once. A stored "POCKET" (V17 on-device voice,
     * removed in V18) also moves to ONLINE. Users who explicitly
     * picked SYSTEM keep it. After this runs, the stored value IS
     * the explicit choice.
     */
    private fun migrateToOnlineDefault(context: Context) {
        if (NovaPreferences.getBoolean(
                context, NovaPreferences.Keys.ONLINE_VOICE_MIGRATED, false
            )
        ) return
        val stored = NovaPreferences.getString(
            context, NovaPreferences.Keys.VOICE_OUTPUT_ENGINE, ""
        )
        if (stored != VoiceOutputEngine.ONLINE.name &&
            stored != VoiceOutputEngine.SYSTEM.name
        ) {
            NovaPreferences.putString(
                context,
                NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
                VoiceOutputEngine.ONLINE.name
            )
        }
        NovaPreferences.putBoolean(
            context, NovaPreferences.Keys.ONLINE_VOICE_MIGRATED, true
        )
    }

    /**
     * Voice engines to try, in order, for one reply. The user's
     * explicit choice is always first; SYSTEM is always last (and
     * alone when explicitly chosen — it never fails). The caller
     * tries each in turn and moves on only on failure, so a reply
     * is never lost to one dead engine.
     */
    fun resolveOrder(context: Context): List<VoiceOutputEngine> {
        return when (getEngine(context)) {
            VoiceOutputEngine.ONLINE ->
                listOf(VoiceOutputEngine.ONLINE, VoiceOutputEngine.SYSTEM)
            VoiceOutputEngine.SYSTEM ->
                listOf(VoiceOutputEngine.SYSTEM)
        }
    }
}

/**
 * Online voice quality: LOW (Kokoro-82M, default — fast, reliable)
 * or HIGH (Azure GPT-audio narrator — better when it works, but it
 * fails often and the service then falls back to Kokoro on its own;
 * the response says so via fellBack, and the app shows it).
 */
enum class OnlineTtsQuality(val apiValue: String) {
    LOW("low"),
    HIGH("high")
}

object OnlineTtsQualityPreferences {

    fun getQuality(context: Context): String {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_QUALITY,
            OnlineTtsQuality.LOW.apiValue
        )
        return if (stored == OnlineTtsQuality.HIGH.apiValue) {
            OnlineTtsQuality.HIGH.apiValue
        } else {
            OnlineTtsQuality.LOW.apiValue
        }
    }

    fun setQuality(context: Context, quality: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_QUALITY,
            if (quality == OnlineTtsQuality.HIGH.apiValue) {
                OnlineTtsQuality.HIGH.apiValue
            } else {
                OnlineTtsQuality.LOW.apiValue
            }
        )
    }
}

/**
 * Online voice choice: Bella (af_bella, warm American female —
 * default) or Hindi (hf_alpha, Kokoro Hindi female). The Hindi voice
 * is also auto-used for any Devanagari reply text, whatever is
 * chosen here (see OnlineTtsEngine.voiceForText).
 */
object OnlineTtsVoicePreferences {

    const val VOICE_BELLA = "af_bella"
    const val VOICE_HINDI = "hf_alpha"

    fun getVoiceId(context: Context): String {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_VOICE,
            VOICE_BELLA
        )
        return if (stored == VOICE_HINDI) VOICE_HINDI else VOICE_BELLA
    }

    fun setVoiceId(context: Context, voiceId: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.ONLINE_TTS_VOICE,
            if (voiceId == VOICE_HINDI) VOICE_HINDI else VOICE_BELLA
        )
    }
}
