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

/** Which voice speaks Lia's no-key replies: system TTS or Pocket TTS. */
enum class VoiceOutputEngine {
    SYSTEM,
    POCKET
}

object VoiceOutputPreferences {

    fun getEngine(context: Context): VoiceOutputEngine {
        val stored = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
            VoiceOutputEngine.SYSTEM.name
        )
        return try {
            VoiceOutputEngine.valueOf(stored)
        } catch (_: Exception) {
            VoiceOutputEngine.SYSTEM
        }
    }

    fun setEngine(context: Context, engine: VoiceOutputEngine) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.VOICE_OUTPUT_ENGINE,
            engine.name
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
