package com.Lia.assistant.action

/**
 * In-memory, per-session context about the last app Lia opened, plus a
 * marker for when Lia herself launched an external app.
 *
 * Two jobs:
 * 1. TWO-STEP COMMANDS: if Lia just opened YouTube and the user then says
 *    "song search karo" (no app named), the parser uses the last app as
 *    context and runs the follow-up there. Context expires after 5 minutes
 *    so a stale app is never used.
 * 2. VOICE CONTINUITY: when Lia launches an external app (YouTube,
 *    WhatsApp, Settings...), the activity goes to background. MainActivity
 *    checks [wasExternalLaunchRecent] in onStop and keeps the voice
 *    session alive instead of killing it — otherwise Lia goes silent the
 *    moment she opens another app.
 *
 * Pure Kotlin, no Android imports — fully unit-testable.
 */
object AppContext {

    /** How long the last-app context stays valid (5 minutes). */
    const val CONTEXT_TTL_MS = 5 * 60 * 1000L

    /** How long after a Lia-launched external app the session stays alive. */
    const val BACKGROUND_STAY_ALIVE_MS = 60 * 1000L

    private var lastApp: String? = null
    private var lastAppAtMs: Long = 0L
    private var lastExternalLaunchAtMs: Long = 0L

    /** The app Lia opened most recently: "youtube", "whatsapp", or null. */
    fun current(): String? {
        val app = lastApp ?: return null
        if (System.currentTimeMillis() - lastAppAtMs > CONTEXT_TTL_MS) {
            lastApp = null
            return null
        }
        return app
    }

    fun record(app: String) {
        lastApp = app.lowercase()
        lastAppAtMs = System.currentTimeMillis()
    }

    /** Call whenever Lia launches an external app/intent herself. */
    fun markExternalLaunch() {
        lastExternalLaunchAtMs = System.currentTimeMillis()
    }

    /** True if Lia launched an external app within the last [windowMs]. */
    fun wasExternalLaunchRecent(windowMs: Long = 15_000L): Boolean {
        return System.currentTimeMillis() - lastExternalLaunchAtMs <= windowMs
    }

    fun clear() {
        lastApp = null
        lastAppAtMs = 0L
        lastExternalLaunchAtMs = 0L
    }
}
