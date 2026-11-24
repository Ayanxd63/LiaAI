package com.goldai.assistant.data

import android.content.Context
import java.io.File

/**
 * V16 (P1-1) — crash-report screen support.
 *
 * The crash handler itself already exists (LiaApplication installs a
 * global handler that writes redacted reports to filesDir/crashes/ —
 * at most 3 files, URLs and key-like values stripped). This store is
 * the read side for the warm next-launch screen: show the newest crash
 * in plain words, let the user Share it, and let them clear it and
 * open Lia. A crash is "unseen" until the user opens the crash screen
 * or clears it — that is what drives the next-launch screen.
 */
object CrashReportStore {

    private const val CRASH_DIR = "crashes"

    fun crashDir(context: Context): File = File(context.filesDir, CRASH_DIR)

    /** All crash reports, newest first. */
    fun all(context: Context): List<File> {
        val dir = crashDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            f.name.startsWith("crash_") && f.name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** The newest crash report, or null when the app never crashed. */
    fun latest(context: Context): File? = all(context).firstOrNull()

    /** True when a crash report exists that the user has not seen yet. */
    fun hasUnseen(context: Context): Boolean {
        val newest = latest(context) ?: return false
        val seen = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.LAST_SEEN_CRASH,
            ""
        )
        return newest.name != seen
    }

    /** Mark the newest crash as seen (user opened the crash screen). */
    fun markSeen(context: Context) {
        val newest = latest(context) ?: return
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.LAST_SEEN_CRASH,
            newest.name
        )
    }

    /** Delete every crash report and clear the seen marker. */
    fun clearAll(context: Context) {
        all(context).forEach { runCatching { it.delete() } }
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.LAST_SEEN_CRASH,
            ""
        )
    }

    /** Short plain summary: exception line + message, first 6 lines. */
    fun summary(file: File): String {
        return runCatching {
            file.readLines().take(6).joinToString("\n").take(400)
        }.getOrDefault("(report could not be read)")
    }
}
