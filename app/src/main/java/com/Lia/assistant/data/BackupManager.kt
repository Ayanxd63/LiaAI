package com.Lia.assistant.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V16 (P1-10) — one-JSON backup: chat + facts + reminders.
 *
 * One file in Downloads (`lia-backup-yyyyMMdd-HHmmss.json`) with
 * everything Lia remembers: all saved chats, all memory facts, all
 * reminders. The user shares it from the Backup screen.
 *
 * What is NEVER in the backup: API keys, tokens, passwords, or any
 * credential. This exporter never reads ApiKeyStore, ElevenLabsStore,
 * or SecureCredentialStore — only the three user-data stores below.
 * Import is P2 — deliberately NOT built in V16.
 */
object BackupManager {

    /** Export everything to one JSON file in Downloads. Null on failure. */
    fun export(context: Context): File? {
        return runCatching {
            val root = JSONObject().apply {
                put("app", "Lia AI")
                put("version", 16)
                put(
                    "exportedAt",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                )
                put("chats", exportChats(context))
                put("facts", exportFacts(context))
                put("reminders", exportReminders(context))
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.apply { mkdirs() }
                ?: return null
            val file = File(dir, "lia-backup-$stamp.json")
            file.writeText(root.toString(2))
            file
        }.getOrNull()
    }

    /** Shareable content:// URI for [file] (FileProvider). */
    fun shareUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun exportChats(context: Context): JSONArray {
        val array = JSONArray()
        for (session in ChatHistoryStore.loadSessions(context)) {
            runCatching {
                val messages = JSONArray()
                for (msg in session.messages) {
                    messages.put(
                        JSONObject().apply {
                            put("role", msg.role)
                            put("text", msg.text)
                        }
                    )
                }
                array.put(
                    JSONObject().apply {
                        put("id", session.id)
                        put("title", session.title)
                        put("preview", session.preview)
                        put("timestamp", session.timestamp)
                        put("personality", session.personality)
                        put("messages", messages)
                    }
                )
            }
        }
        return array
    }

    private fun exportFacts(context: Context): JSONArray {
        val array = JSONArray()
        for (fact in FactMemoryStore.loadFacts(context)) {
            runCatching {
                array.put(
                    JSONObject().apply {
                        put("category", fact.category)
                        put("text", fact.text)
                        put("source", fact.source)
                        put("createdAt", fact.createdAt)
                    }
                )
            }
        }
        return array
    }

    private fun exportReminders(context: Context): JSONArray {
        val array = JSONArray()
        for (reminder in ReminderStore.loadReminders(context)) {
            runCatching {
                array.put(
                    JSONObject().apply {
                        put("text", reminder.text)
                        put("createdAt", reminder.createdAt)
                        put("done", reminder.done)
                    }
                )
            }
        }
        return array
    }
}
