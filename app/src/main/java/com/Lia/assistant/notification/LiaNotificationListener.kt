package com.Lia.assistant.notification

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * V14 — read + reply to notifications (user-granted).
 *
 * This is the ONLY Play-safe way to read notifications and reply to
 * them: the system delivers them to this listener service, and a reply
 * is sent through the ORIGINAL app's own notification action
 * (RemoteInput) — Lia never taps inside WhatsApp or any other app, and
 * never automates a send. The user enables it in Settings →
 * Notifications → Notification access (a system screen with a warning),
 * and only AFTER the prominent in-app disclosure in PermissionsScreen.
 *
 * Privacy: notifications are kept IN MEMORY ONLY (max 20, newest
 * first) and only to answer "mere notifications parho" and to send a
 * reply the user dictated. Nothing is written to disk, nothing is
 * uploaded, and Lia's own notifications are never stored. The listener
 * survives a force-stop — Android reconnects it.
 */
class LiaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName) return
        val notification = posted.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(posted.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            posted.packageName
        }
        NotificationStore.add(
            LiaNotification(
                key = posted.key,
                packageName = posted.packageName,
                appLabel = label,
                title = title,
                text = text,
                postedAtMs = posted.postTime,
                hasReplyAction = notification.actions?.any {
                    it.remoteInputs?.isNotEmpty() == true
                } == true,
                sbn = posted
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { NotificationStore.remove(it.key) }
    }
}

/** One notification, as Lia remembers it (in memory only). */
data class LiaNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val hasReplyAction: Boolean,
    val sbn: StatusBarNotification
)

object NotificationStore {

    private const val MAX_KEPT = 20

    private val lock = Any()
    private val items = ArrayDeque<LiaNotification>()

    /** True when the user enabled Lia in the system notification-access screen. */
    fun isEnabled(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun add(notification: LiaNotification) {
        synchronized(lock) {
            items.removeAll { it.key == notification.key }
            items.addFirst(notification)
            while (items.size > MAX_KEPT) items.removeLast()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            items.removeAll { it.key == key }
        }
    }

    fun recent(limit: Int = 5): List<LiaNotification> {
        synchronized(lock) {
            return items.take(limit).toList()
        }
    }

    /**
     * The newest notification that can be replied to — from [packageHint]
     * when given ("whatsapp"), otherwise from any app.
     */
    fun latestWithReply(packageHint: String?): List<LiaNotification> {
        synchronized(lock) {
            return items.filter { it.hasReplyAction }.filter { item ->
                packageHint.isNullOrBlank() ||
                    item.packageName.contains(packageHint, ignoreCase = true) ||
                    item.appLabel.contains(packageHint, ignoreCase = true)
            }
        }
    }

    /**
     * Send [text] as a reply through the notification's own RemoteInput
     * action. Returns true when the reply was handed to the original app.
     */
    fun sendReply(context: Context, notification: LiaNotification, text: String): Boolean {
        val action = notification.sbn.notification.actions?.firstOrNull {
            it.remoteInputs?.isNotEmpty() == true
        } ?: return false
        val remoteInput = action.remoteInputs.firstOrNull() ?: return false
        val results = Bundle().apply {
            putCharSequence(remoteInput.resultKey, text)
        }
        val replyIntent = Intent()
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, results)
        return try {
            action.actionIntent.send(context, 0, replyIntent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
