package com.goldai.assistant.notification

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.goldai.assistant.data.DeviceTtsVoicePreferences
import com.goldai.assistant.data.NovaPreferences
import com.goldai.assistant.ui.character.AppVisibility
import com.goldai.assistant.voice.CuteVoice
import com.goldai.assistant.voice.VoiceSessionManager

/**
 * V16 (P1-8) — WhatsApp arrival alerts ONLY.
 *
 * When a WhatsApp message arrives and Lia is idle, she speaks one short
 * line ("WhatsApp par <sender> ka message aaya hai.") — nothing more.
 * She never speaks the message text itself (it could be private), never
 * alerts mid-conversation, and never alerts while the user is already
 * looking at the app (he can see the notification himself).
 *
 * Gates, all of them required:
 * - the user turned alerts on in Settings (default OFF),
 * - notification access is granted (otherwise this listener never runs),
 * - the notification is from WhatsApp (com.whatsapp / com.whatsapp.w4b),
 * - no voice session is active (the voice-service awake flag —
 *   [VoiceSessionManager.isActive]),
 * - the app is not in the foreground,
 * - at most one alert per 60 seconds (a group chat burst must not make
 *   Lia talk non-stop).
 *
 * AUTO-REPLY IS DELIBERATELY NOT HERE. Replying to WhatsApp messages
 * automatically needs the user's explicit approval — his number was
 * permanently banned on 2026-11-19, most likely because of bot
 * automation, and an auto-reply would be the same kind of automation.
 * The design lives only in this comment: a future auto-reply would
 * need (a) his explicit yes, (b) a per-contact allow-list, (c) a
 * "Lia is replying for you" disclosure in the reply itself, and
 * (d) the existing RemoteInput path (NotificationStore.sendReply) —
 * never typing inside WhatsApp. None of that is wired to send.
 */
object WhatsAppArrivalAlerts {

    private const val MIN_ALERT_GAP_MS = 60_000L
    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    @Volatile
    private var lastAlertMs: Long = 0L

    @Volatile
    private var currentTts: TextToSpeech? = null

    fun isEnabled(context: Context): Boolean {
        return NovaPreferences.getBoolean(
            context,
            NovaPreferences.Keys.WHATSAPP_ALERTS_ENABLED,
            false
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NovaPreferences.putBoolean(
            context,
            NovaPreferences.Keys.WHATSAPP_ALERTS_ENABLED,
            enabled
        )
    }

    /** Called from [LiaNotificationListener] for every new notification. */
    fun maybeAlert(context: Context, notification: LiaNotification) {
        if (!isEnabled(context)) return
        if (notification.packageName != WHATSAPP_PACKAGE &&
            notification.packageName != WHATSAPP_BUSINESS_PACKAGE
        ) {
            return
        }
        // Never mid-conversation: the voice service is awake.
        if (VoiceSessionManager.isActive.value) return
        // Never while the user is already looking at the app.
        if (AppVisibility.isForeground.value) return
        // Throttle: one alert per minute at most.
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < MIN_ALERT_GAP_MS) return
        lastAlertMs = now

        val sender = notification.title.trim()
        val line = if (sender.isNotBlank()) {
            "WhatsApp par $sender ka message aaya hai."
        } else {
            "WhatsApp par ek message aaya hai."
        }
        speakLine(context.applicationContext, line)
    }

    /**
     * One spoken line via the on-device TTS (no mic, no network, no
     * session). The TTS instance is kept alive until the utterance
     * finishes, then shut down — a fresh instance per alert, so a
     * dead engine can never wedge the next one.
     */
    private fun speakLine(appContext: Context, line: String) {
        runCatching {
            currentTts?.stop()
            currentTts?.shutdown()
            currentTts = null
        }
        val tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                runCatching { currentTts?.shutdown() }
                currentTts = null
                return@TextToSpeech
            }
            val engine = currentTts ?: return@TextToSpeech
            CuteVoice.applyCuteVoice(
                engine,
                DeviceTtsVoicePreferences.getVoiceName(appContext)
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runCatching { engine.shutdown() }
                    if (currentTts == engine) currentTts = null
                }

                override fun onError(utteranceId: String?) {
                    runCatching { engine.shutdown() }
                    if (currentTts == engine) currentTts = null
                }
            })
            engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, "lia-wa-alert")
        }
        currentTts = tts
    }
}
