package com.Lia.assistant

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = File(filesDir, "crashes").apply { mkdirs() }
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val timestamp = sdf.format(Date())
                val crashFile = File(crashDir, "crash_$timestamp.txt")
                crashFile.writeText(buildString {
                    appendLine("=== Lia AI Crash Report ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable::class.qualifiedName}")
                    appendLine("Message: ${redact(throwable.message)}")
                    appendLine("Stacktrace:")
                    appendLine(redact(throwable.stackTraceToString()).lineSequence().take(80).joinToString("\n"))
                    throwable.cause?.let {
                        appendLine("Caused by: ${it::class.qualifiedName}: ${redact(it.message)}")
                        appendLine(redact(it.stackTraceToString()).lineSequence().take(40).joinToString("\n"))
                    }
                })
                // Cap at the 3 newest reports — old ones are deleted so a
                // crash loop can never fill the phone with reports.
                crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_FILES)
                    ?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /** At most this many crash reports are kept on the phone. */
        private const val MAX_CRASH_FILES = 3

        /**
         * Strip anything that could be the user's own text from a crash
         * message: URLs (a prompt can sit in a query string), anything
         * that looks like a key/token/password, and cap the length.
         * The exception class name and the stack frames stay — they are
         * what a crash report is actually for.
         */
        private fun redact(raw: String?): String {
            if (raw.isNullOrBlank()) return "(none)"
            var text = raw
            text = text.replace(Regex("https?://\\S+"), "[url]")
            text = text.replace(
                Regex("(?i)(api[_-]?key|token|password|secret|bearer)[=:]\\s*\\S+"),
                "$1=[redacted]"
            )
            return text.take(200)
        }
    }
}
