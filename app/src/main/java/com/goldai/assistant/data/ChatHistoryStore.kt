package com.goldai.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * V13 — persisted chat history.
 *
 * Conversations must survive app restarts. The lightest correct option
 * is the storage the app already uses everywhere: SharedPreferences
 * with a single JSON string. No Room, no new dependency — org.json
 * ships with Android and is already used by the chat clients.
 *
 * Limits (keeps the prefs file small and fast to read at startup):
 * at most 30 saved chats (oldest dropped first), at most 120 messages
 * per chat (oldest turns dropped first). All parsing is defensive: a
 * corrupt entry is skipped, a corrupt file loads as "no history".
 */
data class ChatHistoryMessage(
    val role: String, // "user" | "assistant"
    val text: String
)

/** One saved chat session (a conversation). */
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val preview: String,
    val timestamp: Long = System.currentTimeMillis(),
    val personality: String = "Girlfriend",
    val messages: List<ChatHistoryMessage> = emptyList()
) {
    fun toItem(): ConversationItem = ConversationItem(
        id = id,
        title = title,
        preview = preview,
        timestamp = timestamp,
        personality = personality
    )
}

object ChatHistoryStore {

    private const val PREF_NAME = "lia_chat_history"
    private const val KEY_SESSIONS = "sessions_json"

    /** At most this many saved chats; the oldest is dropped first. */
    const val MAX_SESSIONS = 30

    /** At most this many messages kept per chat; oldest turns go first. */
    private const val MAX_MESSAGES_PER_SESSION = 120

    /** All saved sessions, newest first. Never throws. */
    fun loadSessions(context: Context): List<ChatSession> {
        val raw = context
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching { parseSessions(raw) }.getOrDefault(emptyList())
    }

    /** Insert or replace [session], newest first; trims to [MAX_SESSIONS]. */
    fun saveSession(context: Context, session: ChatSession) {
        val trimmed = session.copy(
            messages = session.messages.takeLast(MAX_MESSAGES_PER_SESSION)
        )
        val sessions = loadSessions(context)
            .filter { it.id != trimmed.id }
            .toMutableList()
        sessions.add(0, trimmed)
        writeSessions(context, sessions.take(MAX_SESSIONS))
    }

    /** Delete one session by id. No-op when it does not exist. */
    fun deleteSession(context: Context, id: String) {
        val remaining = loadSessions(context).filter { it.id != id }
        writeSessions(context, remaining)
    }

    /** Delete every saved session. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSIONS)
            .apply()
    }

    private fun writeSessions(context: Context, sessions: List<ChatSession>) {
        val array = JSONArray()
        for (session in sessions) {
            runCatching { array.put(encodeSession(session)) }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS, array.toString())
            .apply()
    }

    private fun encodeSession(session: ChatSession): JSONObject {
        val messagesArray = JSONArray()
        for (message in session.messages) {
            messagesArray.put(JSONObject().apply {
                put("r", message.role)
                put("t", message.text)
            })
        }
        return JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("preview", session.preview)
            put("ts", session.timestamp)
            put("personality", session.personality)
            put("messages", messagesArray)
        }
    }

    private fun parseSessions(raw: String): List<ChatSession> {
        val sessions = mutableListOf<ChatSession>()
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            runCatching {
                val obj = array.getJSONObject(i)
                val messages = mutableListOf<ChatHistoryMessage>()
                val messagesArray = obj.optJSONArray("messages")
                if (messagesArray != null) {
                    for (j in 0 until messagesArray.length()) {
                        val msg = messagesArray.getJSONObject(j)
                        val role = msg.optString("r", "user")
                        val text = msg.optString("t", "")
                        if (text.isNotBlank()) {
                            messages.add(
                                ChatHistoryMessage(
                                    role = if (role == "assistant") "assistant" else "user",
                                    text = text
                                )
                            )
                        }
                    }
                }
                sessions.add(
                    ChatSession(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.optString("title", "Chat"),
                        preview = obj.optString("preview", ""),
                        timestamp = obj.optLong("ts", System.currentTimeMillis()),
                        personality = obj.optString("personality", "Girlfriend"),
                        messages = messages
                    )
                )
            }
        }
        return sessions.sortedByDescending { it.timestamp }
    }
}
