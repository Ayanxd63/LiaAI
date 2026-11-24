package com.goldai.assistant.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * V12 — the cute voice for the no-key path.
 *
 * Pollinations has no anonymous TTS (verified live 2026-11-22: the
 * Classic audio model was removed, HTTP 404; the new API answers
 * HTTP 401 "A valid API key is required"). So spoken replies on the
 * no-key path use Android's built-in TextToSpeech — no key, no network,
 * works offline on every phone.
 *
 * The "cute" part: prefer a FEMALE system voice (Google TTS names them
 * like "en-us-x-sfg#female_1-local") and speak a little higher and
 * slower than default (pitch 1.2, rate 0.95). If the phone has no
 * female voice, any English voice is used — never a crash, never silence.
 *
 * This does NOT drive lip sync: system TTS exposes no audio buffers,
 * so the home character's mouth stays still while TTS speaks.
 * Gemini Live (Sulafat) still drives lip sync when a key is present.
 */
object CuteVoice {

    /** Slightly higher pitch — softer, cuter delivery. */
    const val PITCH = 1.2f

    /** Slightly slower than default — warmer, clearer delivery. */
    const val RATE = 0.95f

    /**
     * Apply the cute voice to [tts]. Call inside the TextToSpeech
     * onInit callback (voices are only listed once the engine is ready).
     *
     * V16 (P0-5): when the user picked a device voice in the voice
     * picker, [preferredVoiceName] names it — that voice wins. Otherwise
     * the best female system voice is used, as before.
     */
    fun applyCuteVoice(tts: TextToSpeech, preferredVoiceName: String? = null) {
        if (!preferredVoiceName.isNullOrBlank()) {
            val wanted = try {
                tts.voices?.firstOrNull { it.name == preferredVoiceName }
            } catch (_: Exception) {
                null
            }
            if (wanted != null) {
                tts.voice = wanted
                tts.setPitch(PITCH)
                tts.setSpeechRate(RATE)
                return
            }
            // Stored voice is gone (engine changed) — fall through to auto.
        }
        val female = pickFemaleVoice(tts)
        if (female != null) {
            tts.voice = female
        } else {
            tts.language = Locale.US
        }
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
    }

    /** Best English female voice on this phone, or null when none exists. */
    fun pickFemaleVoice(tts: TextToSpeech): Voice? {
        val voices = try {
            tts.voices
        } catch (_: Exception) {
            return null
        } ?: return null
        val english = voices.filter { it.locale.language == "en" }
        if (english.isEmpty()) return null
        return english.firstOrNull { it.name.contains("female", ignoreCase = true) }
            ?: english.firstOrNull { it.quality >= Voice.QUALITY_HIGH }
            ?: english.firstOrNull()
    }
}
