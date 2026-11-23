package com.Lia.assistant.data

import android.content.Context

/**
 * V16 — small display/voice preferences, each in the shared
 * NovaPreferences file (no new files, no new dependencies).
 */

/**
 * P0-5 — the device TTS voice the user picked in the voice picker.
 * Empty = auto: Lia's cute voice (best female system voice, pitch 1.2,
 * rate 0.95 — see CuteVoice). The stored value is a system voice NAME
 * (e.g. "en-us-x-sfg#female_1-local"); it only ever applies to the
 * on-device TextToSpeech engine, never to Gemini Live.
 */
object DeviceTtsVoicePreferences {

    fun getVoiceName(context: Context): String {
        return NovaPreferences.getString(
            context,
            NovaPreferences.Keys.DEVICE_TTS_VOICE,
            ""
        )
    }

    fun setVoiceName(context: Context, name: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.DEVICE_TTS_VOICE,
            name
        )
    }
}

/** P0-3 — first-run setup wizard: shown once after onboarding, re-runnable from Settings. */
object SetupWizardPreferences {

    fun isDone(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.SETUP_WIZARD_DONE,
            false
        )
    }

    fun setDone(context: Context, done: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.SETUP_WIZARD_DONE,
            done
        )
    }
}
