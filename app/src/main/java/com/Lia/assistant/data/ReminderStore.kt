package com.Lia.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * V16 — simple reminders, stored on this phone only.
 *
 * Same storage pattern as [FactMemoryStore]: SharedPreferences with a
 * single JSON string, no Room, no new dependency. Reminders are created
 * by the Reminder skill (Skills screen) and are one of the three things
 * the one-JSON backup exports (chat + facts + reminders). There is no
 * alarm or notification attached to a reminder in V16 — it is a saved
 * note to remember, not a scheduled alert.
 */
data class LiaReminder(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false
)

object ReminderStore {

    private const val PREF_NAME = "lia_reminders"
    private const val KEY_REMINDERS = "reminders_json"

    /** At most this many reminders; the oldest goes first when trimming. */
    const val MAX_REMINDERS = 100

    fun loadReminders(context: Context): List<LiaReminder> {
        val raw = context
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REMINDERS, null) ?: return emptyList()
        return runCatching { parseReminders(raw) }.getOrDefault(emptyList())
    }

    /** Insert or replace [reminder], newest first; trims to [MAX_REMINDERS]. */
    fun saveReminder(context: Context, reminder: LiaReminder) {
        val trimmed = reminder.copy(text = reminder.text.trim().take(200))
        if (trimmed.text.isBlank()) return
        val reminders = loadReminders(context)
            .filter { it.id != trimmed.id }
            .toMutableList()
        reminders.add(0, trimmed)
        writeReminders(context, reminders.take(MAX_REMINDERS))
    }

    /** Flip the done flag on one reminder. No-op when it does not exist. */
    fun toggleDone(context: Context, id: String) {
        val reminders = loadReminders(context).map {
            if (it.id == id) it.copy(done = !it.done) else it
        }
        writeReminders(context, reminders)
    }

    /** Delete one reminder by id. No-op when it does not exist. */
    fun deleteReminder(context: Context, id: String) {
        val remaining = loadReminders(context).filter { it.id != id }
        writeReminders(context, remaining)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_REMINDERS)
            .apply()
    }

    private fun writeReminders(context: Context, reminders: List<LiaReminder>) {
        val array = JSONArray()
        for (reminder in reminders) {
            runCatching { array.put(encodeReminder(reminder)) }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDERS, array.toString())
            .apply()
    }

    private fun encodeReminder(reminder: LiaReminder): JSONObject {
        return JSONObject().apply {
            put("id", reminder.id)
            put("t", reminder.text)
            put("ts", reminder.createdAt)
            put("d", reminder.done)
        }
    }

    private fun parseReminders(raw: String): List<LiaReminder> {
        val reminders = mutableListOf<LiaReminder>()
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            runCatching {
                val obj = array.getJSONObject(i)
                val text = obj.optString("t", "").trim()
                if (text.isNotBlank()) {
                    reminders.add(
                        LiaReminder(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            text = text.take(200),
                            createdAt = obj.optLong("ts", System.currentTimeMillis()),
                            done = obj.optBoolean("d", false)
                        )
                    )
                }
            }
        }
        return reminders.sortedByDescending { it.createdAt }
    }
}
