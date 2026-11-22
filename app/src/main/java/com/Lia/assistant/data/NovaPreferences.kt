package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences

object NovaPreferences {
    private const val PREF_NAME = "nova_prefs"

    object Keys {
        const val THEME_MODE = "theme_mode"
        const val REDUCED_MOTION = "reduced_motion"
        const val ORB_STYLE = "orb_style"
        const val PERSONALITY = "personality"
        const val USER_NAME = "user_name"
        const val VOICE_INPUT_ENABLED = "voice_input_enabled"
        const val WAKE_WORD_ENABLED = "wake_word_enabled"
        const val LANGUAGE = "language"
        const val HAS_ONBOARDED = "has_onboarded"
        const val VOICE_NAME = "voice_name"
        const val RESPONSE_STYLE = "response_style"
        const val SPEECH_SPEED = "speech_speed"
        const val VOICE_VOLUME = "voice_volume"
        const val ASSISTANT_NAME = "assistant_name"
        // V16: accent picker (rose/blue/purple), engine preset
        // (Fast/Warm/Smart), human-paced typing, chosen device TTS
        // voice, first-run setup wizard, WhatsApp arrival alerts,
        // voice enrolment, skills, weather cache, crash reports.
        const val ACCENT = "accent"
        const val ENGINE_PRESET = "engine_preset"
        const val HUMAN_PACED_TYPING = "human_paced_typing"
        const val DEVICE_TTS_VOICE = "device_tts_voice"
        const val SETUP_WIZARD_DONE = "setup_wizard_done"
        const val WHATSAPP_ALERTS_ENABLED = "whatsapp_alerts_enabled"
        const val VOICE_ENROLLED = "voice_enrolled"
        const val VOICE_ENROL_ROLE = "voice_enrol_role"
        const val VOICE_ENROL_COUNT = "voice_enrol_count"
        const val SKILLS_INSTALLED = "skills_installed"
        const val WEATHER_CACHE_JSON = "weather_cache_json"
        const val WEATHER_CACHE_TIME = "weather_cache_time"
        const val LAST_SEEN_CRASH = "last_seen_crash"
        // V17: on-device voice — output engine (internet / system).
        // V18 diet: the per-engine opt-in keys (Pocket TTS voice,
        // on-device STT, VAD gate, laugh detection, speaker verify,
        // memory embeddings) were removed with the engines they served.
        const val VOICE_OUTPUT_ENGINE = "v17_voice_output_engine"
        // V17 (online voice) — internet TTS via mesmer.tools (Kokoro,
        // free, no key): voice choice + quality, the one-time
        // migration to the online default, and the local 20/hour
        // quota counter (the server enforces the real limit; the
        // local counter just fails fast). Stored as strings —
        // NovaPreferences has no Long/Int accessors.
        const val ONLINE_TTS_QUALITY = "v17_online_tts_quality"
        const val ONLINE_TTS_VOICE = "v17_online_tts_voice"
        const val ONLINE_VOICE_MIGRATED = "v17_online_voice_migrated"
        const val ONLINE_TTS_QUOTA_WINDOW_START = "v17_online_tts_quota_window_start"
        const val ONLINE_TTS_QUOTA_COUNT = "v17_online_tts_quota_count"
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getString(context: Context, key: String, defaultValue: String): String {
        return getPrefs(context).getString(key, defaultValue) ?: defaultValue
    }

    fun putString(context: Context, key: String, value: String) {
        getPrefs(context).edit().putString(key, value).apply()
    }

    fun getBoolean(context: Context, key: String, defaultValue: Boolean): Boolean {
        return getPrefs(context).getBoolean(key, defaultValue)
    }

    fun putBoolean(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit().putBoolean(key, value).apply()
    }

    fun getFloat(context: Context, key: String, defaultValue: Float): Float {
        return getPrefs(context).getFloat(key, defaultValue)
    }

    fun putFloat(context: Context, key: String, value: Float) {
        getPrefs(context).edit().putFloat(key, value).apply()
    }
}
