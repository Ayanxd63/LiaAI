package com.goldai.assistant.voice

/**
 * V16 (APK-delta addition) — the voice turn state machine.
 *
 * Maya's APK ships a dedicated ONNX turn/end-of-speech detector; Lia
 * has no such model and must not bundle one. The cheap, honest
 * equivalent is this explicit state machine driven by the
 * [android.speech.SpeechRecognizer] callbacks Lia already receives:
 * the recognizer itself reports speech start, speech end and results,
 * so the turn boundary is a real event, not a silence-timeout guess.
 *
 * States, in order for one no-key turn:
 * - LISTENING — recognizer ready, waiting for the user to speak.
 * - SPEECH_DETECTED — `onBeginningOfSpeech`: the user is speaking.
 * - END_OF_SPEECH — `onEndOfSpeech`: the user stopped; this is the
 *   turn boundary. The thinking watchdog is armed here (see
 *   [VoiceSessionManager]), so a lost result cannot wedge the
 *   overlay — the watchdog fires from this state, not from a timer
 *   started at listening time.
 * - THINKING — a transcript arrived and Lia is fetching a reply.
 * - SPEAKING — Lia is talking (TTS or Live audio).
 * - IDLE — no session, or a quiet-room restart between turns.
 * - ERROR — the turn failed (permission, recognizer error).
 *
 * The Live (Gemini) path is continuous streaming, so it only feeds
 * SPEAKING / LISTENING into this machine at playback start/end.
 * [label] is the one-line Roman Urdu text shown under the orb in the
 * voice overlay and in the voice pipeline debugger.
 */
enum class VoiceTurnState(val label: String) {
    IDLE("—"),
    LISTENING("Sunne ke liye tayyar"),
    SPEECH_DETECTED("Bol rahe ho — sun rahi hun"),
    END_OF_SPEECH("Bolna khatam — turn khatam"),
    THINKING("Soch rahi hun…"),
    SPEAKING("Lia bol rahi hai…"),
    ERROR("Kuch ghalat hua")
}
