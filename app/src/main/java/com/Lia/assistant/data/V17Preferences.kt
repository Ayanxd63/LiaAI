package com.Lia.assistant.data

import android.content.Context

/**
 * V17 — on-device voice preferences, each in the shared NovaPreferences
 * file (no new files, no new dependencies). Same pattern as V16.
 *
 * Every V17 feature is OFF by default: each one downloads its own
 * model (Wi-Fi only) on first enable, so a fresh install fetches
 * nothing until the user asks for it. A toggle alone never starts a
 * download — the On-device voice screen does that explicitly.
 */

/**
 * Which voice speaks Lia's no-key replies:
 * - ONLINE: internet voice (Kokoro via mesmer.tools) — human-like,
 *   no key, ~20 generations/hour, needs internet. Default.
 * - POCKET: on-device Lia voice — offline, always available once
 *   its ~191 MB model is downloaded.
 * - SYSTEM: device TTS (cute voice) — last resort, always works.
 */
enum class VoiceOutputEngine {
    ONLINE,
    POCKET,
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
     * One-time migration (V17 online voice): before this version the
     * implicit default was SYSTEM. Users who never chose a voice
     * move to ONLINE once; users who explicitly picked POCKET keep
     * it. After this runs, the stored value IS the explicit choice.
     */
    private fun migrateToOnlineDefault(context: Context) {
        if (NovaPreferences.getBoolean(
                context, NovaPreferences.Keys.ONLINE_VOICE_MIGRATED, false
            )
        ) return
        val stored = NovaPreferences.getString(
            context, NovaPreferences.Keys.VOICE_OUTPUT_ENGINE, ""
        )
        if (stored != VoiceOutputEngine.POCKET.name) {
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
     * explicit choice is always first; the rest follows the V16
     * engine preset — FAST keeps the fallback on-device and instant
     * (no network hop when the first voice fails), WARM/SMART prefer
     * the human-like online voice over the system voice. SYSTEM is
     * always last (and alone when explicitly chosen — it never fails).
     * The caller tries each in turn and moves on only on failure, so
     * a reply is never lost to one dead engine.
     */
    fun resolveOrder(context: Context): List<VoiceOutputEngine> {
        val choice = getEngine(context)
        if (choice == VoiceOutputEngine.SYSTEM) {
            return listOf(VoiceOutputEngine.SYSTEM)
        }
        val fast = EnginePresetPreferences.getPreset(context) == EnginePreset.FAST
        return when {
            choice == VoiceOutputEngine.ONLINE ->
                listOf(VoiceOutputEngine.ONLINE, VoiceOutputEngine.POCKET, VoiceOutputEngine.SYSTEM)
            fast ->
                listOf(VoiceOutputEngine.POCKET, VoiceOutputEngine.SYSTEM)
            else ->
                listOf(VoiceOutputEngine.POCKET, VoiceOutputEngine.ONLINE, VoiceOutputEngine.SYSTEM)
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

/**
 * Pocket TTS voice reference: "lia" (bria.wav, the default Lia voice)
 * or "loona" (loona.wav, the second voice). These are the reference
 * recordings from the public sherpa Pocket TTS model repo — Lia's own
 * voice character, not any vendor's voice.
 */
object PocketTtsVoicePreferences {

    const val VOICE_LIA = "lia"
    const val VOICE_LOONA = "loona"

    fun getVoiceId(context: Context): String {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.POCKET_TTS_VOICE,
            VOICE_LIA
        )
        return if (stored == VOICE_LOONA) VOICE_LOONA else VOICE_LIA
    }

    fun setVoiceId(context: Context, voiceId: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.POCKET_TTS_VOICE,
            if (voiceId == VOICE_LOONA) VOICE_LOONA else VOICE_LIA
        )
    }
}

/** Item 2 — on-device speech recognition (Moonshine, English only). Default OFF. */
object OnDeviceSttPreferences {

    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.ONDEVICE_STT_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.ONDEVICE_STT_ENABLED,
            enabled
        )
    }
}

/** Item 6 — VAD gate in front of the Vosk wake-word spotter. Default OFF. */
object VadGatePreferences {

    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.VAD_GATE_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.VAD_GATE_ENABLED,
            enabled
        )
    }
}

/** Item 4 — laugh detection during voice sessions. Default OFF. */
object LaughDetectionPreferences {

    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.LAUGH_DETECTION_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.LAUGH_DETECTION_ENABLED,
            enabled
        )
    }
}

/** Item 3 — speaker voice-match gating on "Hey Lia" enrolment. Default OFF. */
object SpeakerVerifyPreferences {

    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.SPEAKER_VERIFY_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.SPEAKER_VERIFY_ENABLED,
            enabled
        )
    }
}

/** Item 5 — semantic (embedding) search over saved facts. Default OFF. */
object MemoryEmbeddingPreferences {

    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.MEMORY_EMBEDDINGS_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.MEMORY_EMBEDDINGS_ENABLED,
            enabled
        )
    }
}
