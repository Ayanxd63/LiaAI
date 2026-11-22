package com.Lia.assistant.voice

import android.content.Context
import com.Lia.assistant.data.NovaPreferences

/**
 * V16 (P1-5) — Voice Guardian-style enrolment for "Hey Lia".
 *
 * The user speaks 3 phrases; each one is confirmed by on-device speech
 * recognition (the same Android SpeechRecognizer the no-key voice path
 * already uses). Progress is per-phrase, a role tag is stored
 * (Owner / Family / Friend), and "Test my voice" re-checks one phrase
 * on demand.
 *
 * Honest limit, shown on the enrolment screen itself: this is
 * wake-word gating, NOT biometric verification. The on-device spotter
 * matches the WORDS "Hey Lia" — it cannot tell whose voice said them,
 * so anyone saying "Hey Lia" can still wake Lia. Enrolment is practice
 * + a role tag, not a lock.
 */
object VoiceEnrolmentStore {

    /** The 3 phrases to speak, in order. */
    val PHRASES = listOf(
        "Hey Lia",
        "Hey Lia, suno",
        "Lia, meri baat suno"
    )

    fun isEnrolled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.VOICE_ENROLLED,
            false
        )
    }

    fun setEnrolled(context: Context, enrolled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.VOICE_ENROLLED,
            enrolled
        )
    }

    /** How many of the 3 phrases are confirmed (0–3). */
    fun confirmedCount(context: Context): Int {
        return NovaPreferences.getString(
            context,
            NovaPreferences.Keys.VOICE_ENROL_COUNT,
            "0"
        ).toIntOrNull()?.coerceIn(0, PHRASES.size) ?: 0
    }

    fun setConfirmedCount(context: Context, count: Int) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.VOICE_ENROL_COUNT,
            count.coerceIn(0, PHRASES.size).toString()
        )
    }

    /** Role tag: Owner, Family, or Friend. */
    fun getRole(context: Context): String {
        return NovaPreferences.getString(
            context,
            NovaPreferences.Keys.VOICE_ENROL_ROLE,
            "Owner"
        )
    }

    fun setRole(context: Context, role: String) {
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.VOICE_ENROL_ROLE,
            role
        )
    }

    fun reset(context: Context) {
        setEnrolled(context, false)
        setConfirmedCount(context, 0)
    }
}
