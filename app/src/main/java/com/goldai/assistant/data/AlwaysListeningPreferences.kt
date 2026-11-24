package com.goldai.assistant.data

import android.content.Context

/**
 * V14 — "Always listening" (wake word "Hey Lia").
 *
 * Stored in the existing NovaPreferences file, under the
 * WAKE_WORD_ENABLED key that was declared but never used before.
 * DEFAULT IS OFF: the microphone only listens in the background when
 * the user turns this on in Settings, after the plain-language privacy
 * line ("Lia listens on your phone only for 'Hey Lia'. Nothing is
 * recorded or sent until she hears it."). When OFF, Lia behaves exactly
 * like V13 — session-based, mic released on exit.
 *
 * V15 hook: the on-device offline LLM (see lia-v15-plan.md) will reuse
 * this same toggle as its "hands-free" gate — the wake word wakes the
 * session, the engine choice (Pollinations / Gemini / offline) stays
 * independent in EnginePreferences.
 */
object AlwaysListeningPreferences {

    /** True only after the user turned always-listening on in Settings. */
    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.WAKE_WORD_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.WAKE_WORD_ENABLED,
            enabled
        )
    }
}
