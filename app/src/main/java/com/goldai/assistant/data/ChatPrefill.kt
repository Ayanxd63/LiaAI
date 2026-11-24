package com.goldai.assistant.data

/**
 * V16 — Home ask bar → Chat handoff.
 *
 * Home has an "ask bar" (P0-1): the user types a question there and taps
 * Send. The text is parked here and ChatScreen picks it up on entry and
 * sends it as a normal message. One-shot by design: [take] clears it, so
 * a rotation or a second visit to Chat never re-sends it.
 */
object ChatPrefill {

    @Volatile
    private var pending: String? = null

    fun set(text: String) {
        pending = text
    }

    fun take(): String? {
        val text = pending
        pending = null
        return text?.takeIf { it.isNotBlank() }
    }
}
