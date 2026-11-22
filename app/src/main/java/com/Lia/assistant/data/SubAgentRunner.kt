package com.Lia.assistant.data

import android.content.Context
import android.os.Environment
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.GeminiTextClient
import com.Lia.assistant.voice.OfflineLlmClient
import com.Lia.assistant.voice.PollinationsClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V16 (P1-9) — the sub-agent output contract.
 *
 * Three real sub-agents: Research, Story, Song-list. Each one takes the
 * user's topic, asks the first available engine in the preset order,
 * and writes the result to a DATED text file in Documents/LiaAI/
 * (e.g. `lia-research-20261122-163000.txt`). The caller gets the file
 * back and offers Open / Share via FileProvider — the file itself is
 * the output, not a chat bubble.
 *
 * Honest failure: when no engine answers, the result is a plain
 * Failure with a human reason — never an empty file, never a fake
 * "done". Auto-reply is NOT part of this contract (see the note in
 * WhatsAppArrivalAlerts: it needs the user's explicit approval).
 */
enum class SubAgentKind(val displayName: String, val filePrefix: String) {
    RESEARCH("Research", "lia-research"),
    STORY("Story", "lia-story"),
    SONG_LIST("Song list", "lia-songs")
}

sealed class SubAgentResult {
    data class Success(val file: File, val chars: Int) : SubAgentResult()
    data class Failure(val message: String) : SubAgentResult()
}

object SubAgentRunner {

    /** Dated output folder: Documents/LiaAI/ on the phone's storage. */
    fun outputDir(context: Context): File {
        return File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "LiaAI"
        ).apply { mkdirs() }
    }

    suspend fun run(
        context: Context,
        kind: SubAgentKind,
        topic: String
    ): SubAgentResult {
        val trimmed = topic.trim()
        if (trimmed.isBlank()) {
            return SubAgentResult.Failure("Pehle topic likho — kis baare mein chahiye?")
        }
        val reply = generate(context, kind, trimmed)
        if (reply is ChatReplyResult.Error) {
            return SubAgentResult.Failure(reply.message)
        }
        val text = (reply as ChatReplyResult.Success).text.trim()
        if (text.isBlank()) {
            return SubAgentResult.Failure(
                "Jawab khaali aaya — dobara try karo."
            )
        }
        return runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(outputDir(context), "${kind.filePrefix}-$stamp.txt")
            val dateLine = SimpleDateFormat("d MMMM yyyy, h:mm a", Locale.US).format(Date())
            file.writeText(
                buildString {
                    appendLine("Lia AI — ${kind.displayName}")
                    appendLine("Topic: $trimmed")
                    appendLine("Date: $dateLine")
                    appendLine()
                    appendLine(text)
                    appendLine()
                    appendLine("— Lia AI (V16)")
                }
            )
            SubAgentResult.Success(file, text.length)
        }.getOrElse {
            SubAgentResult.Failure(
                "File save nahi ho saki — storage check kar ke dobara try karo."
            )
        }
    }

    private suspend fun generate(
        context: Context,
        kind: SubAgentKind,
        topic: String
    ): ChatReplyResult {
        val systemInstruction = when (kind) {
            SubAgentKind.RESEARCH ->
                "You are Lia's Research sub-agent. Research the topic below and write a " +
                    "clear, short research note in Roman Urdu (or the user's language): " +
                    "key facts, 3–5 bullet points, and one 'aur parhne ke liye' line. " +
                    "Keep it under 300 words. Never invent dates, numbers or quotes — " +
                    "if you are unsure, say so plainly."
            SubAgentKind.STORY ->
                "You are Lia's Story sub-agent. Write a short original story (under " +
                    "300 words) in Roman Urdu based on the idea below. Warm, fun, with " +
                    "a happy ending. Original characters only."
            SubAgentKind.SONG_LIST ->
                "You are Lia's Song-list sub-agent. Make a song list of 8 songs for " +
                    "the mood or occasion below: song name — artist, and one short line " +
                    "why each fits. Roman Urdu headings, English song names are fine. " +
                    "Only suggest real, well-known songs."
        }
        val history = listOf(ChatTurn(isUser = true, text = topic))
        val engines = EnginePresetPreferences.resolveEngines(context)
        if (engines.isEmpty()) {
            return ChatReplyResult.Error(
                "Koi engine available nahi — net check karo, ya Settings > " +
                    "Offline AI se model download karo."
            )
        }
        var lastError: ChatReplyResult.Error? = null
        for (engine in engines) {
            val result: ChatReplyResult = when (engine) {
                ChatEngine.POLLINATIONS -> PollinationsClient.chat(
                    systemInstruction,
                    history
                )
                ChatEngine.OFFLINE -> OfflineLlmClient.chat(
                    context,
                    systemInstruction,
                    topic
                )
                ChatEngine.GEMINI -> {
                    val key = ApiKeyStore.getKey(context)
                    if (key.isBlank()) {
                        ChatReplyResult.Error("Gemini key nahi hai.")
                    } else {
                        GeminiTextClient.reply(key, systemInstruction, history)
                    }
                }
            }
            when (result) {
                is ChatReplyResult.Success -> return result
                is ChatReplyResult.Error -> {
                    lastError = result
                    if (!result.isRateLimited && !result.isNetworkError) return result
                }
            }
        }
        return lastError ?: ChatReplyResult.Error(
            "Sub-agent nahi chal saka — dobara try karo."
        )
    }
}
