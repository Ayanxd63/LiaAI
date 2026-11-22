package com.Lia.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * V15 — long-term memory: facts Lia remembers about the user.
 *
 * A small fact layer on top of the persisted chat history (V13). Same
 * storage pattern as [ChatHistoryStore]: SharedPreferences with a
 * single JSON string — no Room, no new dependency. At most 100 facts,
 * each at most 200 chars, so the file stays tiny.
 *
 * Facts are captured only two ways (see [MemoryCapture]):
 * 1. Explicit command — "yaad rakhna ke …" / "remember that …".
 * 2. Light auto-rules with a "Yaad rakhun? Yes / No" confirmation.
 * Nothing is ever stored silently, and health / financial /
 * credential-like facts are never captured at all.
 *
 * Privacy: facts live only on this phone, in the same storage as chat
 * history. They are sent to Pollinations/Gemini ONLY as part of the
 * chat prompt while chatting (exactly like history is today); in
 * offline mode they never leave the phone. They are never written to
 * crash reports or logs.
 *
 * All parsing is defensive (same as ChatHistoryStore): a corrupt entry
 * is skipped, a corrupt file loads as "no facts".
 */
data class MemoryFact(
    val id: String = java.util.UUID.randomUUID().toString(),
    val category: String, // identity | preference | people | life | routine | misc
    val text: String,
    val source: String = "user", // "user" (explicit) | "confirmed" (auto-rule, user said yes)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object FactMemoryStore {

    private const val PREF_NAME = "lia_fact_memory"
    private const val KEY_FACTS = "facts_json"
    private const val KEY_ENABLED = "memory_enabled"

    /** At most this many facts; the oldest non-pinned fact goes first. */
    const val MAX_FACTS = 100

    /** A fact longer than this is trimmed at save time. */
    const val MAX_FACT_CHARS = 200

    /** Master toggle: OFF = no capture and no injection into prompts. */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** All saved facts, newest first. Never throws. */
    fun loadFacts(context: Context): List<MemoryFact> {
        val raw = context
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FACTS, null) ?: return emptyList()
        return runCatching { parseFacts(raw) }.getOrDefault(emptyList())
    }

    /** Insert or replace [fact], newest first; trims to [MAX_FACTS]. */
    fun saveFact(context: Context, fact: MemoryFact) {
        val trimmed = fact.copy(text = fact.text.trim().take(MAX_FACT_CHARS))
        if (trimmed.text.isBlank()) return
        val facts = loadFacts(context)
            .filter { it.id != trimmed.id }
            .toMutableList()
        facts.add(0, trimmed.copy(updatedAt = System.currentTimeMillis()))
        writeFacts(context, facts.take(MAX_FACTS))
    }

    /** Delete one fact by id. No-op when it does not exist. */
    fun deleteFact(context: Context, id: String) {
        val remaining = loadFacts(context).filter { it.id != id }
        writeFacts(context, remaining)
    }

    /** Delete every saved fact. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FACTS)
            .apply()
    }

    /**
     * The "Things I remember about you:" block injected into prompts.
     * Top 10 facts by recency, at most 800 chars. Empty string when
     * memory is off or there are no facts — callers skip injection then.
     */
    fun memoryBlock(context: Context): String {
        if (!isEnabled(context)) return ""
        val facts = loadFacts(context).take(10)
        if (facts.isEmpty()) return ""
        val lines = facts.joinToString("\n") { "- ${it.text}" }
        val block = "Things I remember about you:\n$lines"
        return if (block.length > 800) block.take(800) else block
    }

    private fun writeFacts(context: Context, facts: List<MemoryFact>) {
        val array = JSONArray()
        for (fact in facts) {
            runCatching { array.put(encodeFact(fact)) }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FACTS, array.toString())
            .apply()
    }

    private fun encodeFact(fact: MemoryFact): JSONObject {
        return JSONObject().apply {
            put("id", fact.id)
            put("c", fact.category)
            put("t", fact.text)
            put("s", fact.source)
            put("ts", fact.createdAt)
            put("ut", fact.updatedAt)
        }
    }

    private fun parseFacts(raw: String): List<MemoryFact> {
        val facts = mutableListOf<MemoryFact>()
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            runCatching {
                val obj = array.getJSONObject(i)
                val text = obj.optString("t", "").trim()
                if (text.isNotBlank()) {
                    facts.add(
                        MemoryFact(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            category = obj.optString("c", "misc"),
                            text = text.take(MAX_FACT_CHARS),
                            source = obj.optString("s", "user"),
                            createdAt = obj.optLong("ts", System.currentTimeMillis()),
                            updatedAt = obj.optLong("ut", System.currentTimeMillis())
                        )
                    )
                }
            }
        }
        return facts.sortedByDescending { it.updatedAt }
    }
}
