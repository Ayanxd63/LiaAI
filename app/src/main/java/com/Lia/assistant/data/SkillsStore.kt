package com.Lia.assistant.data

import android.content.Context
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.GeminiTextClient
import com.Lia.assistant.voice.OfflineLlmClient
import com.Lia.assistant.voice.PollinationsClient

/**
 * V16 (P1-2) — the skills store: 6 real local skills.
 *
 * Each skill is a prompt template over Lia's free engines (Pollinations
 * / offline / Gemini-when-keyed) — no fake buttons, no mock results.
 * "Install" pins the skill (persisted); an installed skill can Run with
 * the user's own input and shows the real reply in a result card. The
 * header shows "N/6 installed". Skills run through the same engine
 * preset order as chat (see [EnginePresetPreferences.resolveEngines]).
 */
data class LiaSkill(
    val id: String,
    val name: String,
    val tagline: String,
    val inputHint: String,
    val promptTemplate: String
)

object LiaSkills {

    val ALL: List<LiaSkill> = listOf(
        LiaSkill(
            id = "morning_briefing",
            name = "Morning Briefing",
            tagline = "Subah ka plan — aaj kya karna hai, ek clean briefing.",
            inputHint = "Aaj kya karna hai? (e.g. college, parhai, kaam…)",
            promptTemplate = "You are Lia's Morning Briefing skill. The user will tell you " +
                "about their day. Reply with a short morning briefing in Roman Urdu: " +
                "today's top 3 priorities, one motivation line, and one practical tip. " +
                "Keep it under 120 words."
        ),
        LiaSkill(
            id = "study_helper",
            name = "Study Helper",
            tagline = "Parhai mein madad — concept samjhao, yaad karwao.",
            inputHint = "Konsa topic samajhna hai? (e.g. Compiler Construction…)",
            promptTemplate = "You are Lia's Study Helper skill. Explain the topic the user " +
                "gives in simple Roman Urdu, with one easy example and one short summary " +
                "to remember. Keep it under 180 words. Never invent a definition — if " +
                "you are unsure, say so plainly."
        ),
        LiaSkill(
            id = "music_dj",
            name = "Music DJ",
            tagline = "Mood ke hisaab se gaano ki list banao.",
            inputHint = "Kaisa mood hai? (e.g. happy, sad, drive, parhai…)",
            promptTemplate = "You are Lia's Music DJ skill. The user gives a mood or occasion. " +
                "Reply with a list of 8 songs: song name — artist, and one short line " +
                "why each fits. Roman Urdu headings, English song names are fine. Only " +
                "suggest real, well-known songs."
        ),
        LiaSkill(
            id = "journal_writer",
            name = "Journal Writer",
            tagline = "Aaj ka din likho — ek khoobsurat journal entry.",
            inputHint = "Aaj kya hua? (2–3 lines mein batao…)",
            promptTemplate = "You are Lia's Journal Writer skill. Turn what the user tells " +
                "you about their day into a warm, short journal entry in Roman Urdu " +
                "(under 150 words), ending with one gentle thought for tomorrow. " +
                "Keep their facts — do not invent events they did not mention."
        ),
        LiaSkill(
            id = "reminder",
            name = "Reminder",
            tagline = "Yaad dilane wali baat — clean reminder banao aur save karo.",
            inputHint = "Kya yaad rakhna hai? (e.g. kal subah 7 baje jagna…)",
            promptTemplate = "You are Lia's Reminder skill. Turn what the user says into ONE " +
                "clean, short reminder line in Roman Urdu (under 25 words) — what to " +
                "do and when, nothing extra. Reply with only that line."
        ),
        LiaSkill(
            id = "translator",
            name = "Translator",
            tagline = "English ↔ Roman Urdu tarjuma.",
            inputHint = "Kya translate karna hai? (English ya Urdu likho…)",
            promptTemplate = "You are Lia's Translator skill. Translate what the user writes: " +
                "English → Roman Urdu, Roman Urdu → English. Reply with the translation " +
                "only, plus one short note if a word has two meanings. Keep names and " +
                "numbers unchanged."
        )
    )

    fun byId(id: String): LiaSkill? = ALL.firstOrNull { it.id == id }
}

object SkillsStore {

    fun installedIds(context: Context): Set<String> {
        val raw = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.SKILLS_INSTALLED,
            ""
        )
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isInstalled(context: Context, skillId: String): Boolean {
        return skillId in installedIds(context)
    }

    fun setInstalled(context: Context, skillId: String, installed: Boolean) {
        val ids = installedIds(context).toMutableSet()
        if (installed) ids += skillId else ids -= skillId
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.SKILLS_INSTALLED,
            ids.joinToString(",")
        )
    }

    fun installedCount(context: Context): Int = installedIds(context).size

    /**
     * Run [skill] with the user's [input] on the first engine in the
     * preset order that answers. Rate-limit / network errors move to
     * the next engine; a hard error stops the chain. Never throws.
     */
    suspend fun runSkill(
        context: Context,
        skill: LiaSkill,
        input: String
    ): ChatReplyResult {
        val history = listOf(ChatTurn(isUser = true, text = input))
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
                    skill.promptTemplate,
                    history
                )
                ChatEngine.OFFLINE -> OfflineLlmClient.chat(
                    context,
                    skill.promptTemplate,
                    input
                )
                ChatEngine.GEMINI -> {
                    val key = ApiKeyStore.getKey(context)
                    if (key.isBlank()) {
                        ChatReplyResult.Error("Gemini key nahi hai.")
                    } else {
                        GeminiTextClient.reply(key, skill.promptTemplate, history)
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
            "Skill nahi chal saki — dobara try karo."
        )
    }
}
