package com.Lia.assistant.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class ConversationItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val preview: String,
    val timestamp: Long = System.currentTimeMillis(),
    // V13: which personality this chat was with (shown in History).
    val personality: String = "Girlfriend"
)

class NovaAppState(context: Context) {
    private val ioScope = CoroutineScope(Dispatchers.IO)

    var themeMode by mutableStateOf(
        NovaPreferences.getString(context, NovaPreferences.Keys.THEME_MODE, "DARK")
    )
        private set

    var reducedMotion by mutableStateOf(
        NovaPreferences.getBoolean(context, NovaPreferences.Keys.REDUCED_MOTION, false)
    )
        private set

    var orbStyle by mutableStateOf(
        NovaPreferences.getString(context, NovaPreferences.Keys.ORB_STYLE, "NOVA")
    )
        private set

    // V16 (P1-11): accent colour — ROSE (default, preserves the V13
    // warm rose theme), BLUE, or PURPLE. Drives Material3 primary via
    // NovaTheme; the stored value wins when the user picked one.
    var accent by mutableStateOf(
        NovaPreferences.getString(context, NovaPreferences.Keys.ACCENT, "ROSE")
    )
        private set

    // V12: Girlfriend is the default personality (ayan's order). The
    // stored value wins when the user picked something else.
    var personality by mutableStateOf(
        Personality.fromString(
            NovaPreferences.getString(
                context,
                NovaPreferences.Keys.PERSONALITY,
                Personality.GF_MODE.name
            )
        )
    )
        private set

    var assistantName by mutableStateOf(
        NovaPreferences.getString(
            context,
            NovaPreferences.Keys.ASSISTANT_NAME,
            AssistantBrand.NAME
        )
    )
        private set

    var userName by mutableStateOf(
        NovaPreferences.getString(
            context,
            NovaPreferences.Keys.USER_NAME,
            "Friend"
        )
    )
        private set

    var language by mutableStateOf(
        LanguagePreference.fromString(
            NovaPreferences.getString(
                context,
                NovaPreferences.Keys.LANGUAGE,
                LanguagePreference.AUTO.name
            )
        )
    )
        private set

    var voiceName by mutableStateOf(
        NovaPreferences.getString(
            context,
            NovaPreferences.Keys.VOICE_NAME,
            "Aoede"
        )
    )
        private set

    var speechSpeed by mutableFloatStateOf(
        NovaPreferences.getFloat(
            context,
            NovaPreferences.Keys.SPEECH_SPEED,
            1.0f
        )
    )
        private set

    var voiceVolume by mutableFloatStateOf(
        NovaPreferences.getFloat(
            context,
            NovaPreferences.Keys.VOICE_VOLUME,
            1.0f
        )
    )
        private set

    // V13: persisted chat history. Loaded once from ChatHistoryStore
    // (SharedPreferences JSON) so past chats survive app restarts. The
    // list is newest first; the store caps at 30 chats.
    // V15: the JSON load runs on IO, not in the initializer, so app
    // start never stutters on a big history file — the list fills in
    // just after launch.
    val conversations = mutableStateListOf<ConversationItem>()

    init {
        ioScope.launch {
            val sessions = runCatching { ChatHistoryStore.loadSessions(context) }
                .getOrDefault(emptyList())
            // V15: seed the voice short-term memory from the latest
            // saved session, so the first voice turn already knows
            // what was said before the app restarted.
            val latest = sessions.firstOrNull()
            if (latest != null && latest.messages.isNotEmpty()) {
                com.Lia.assistant.voice.ConversationMemory.seedFromSession(latest.messages)
            }
            conversations.addAll(sessions.map { it.toItem() })
        }
    }

    /** Save (insert or replace) one chat session, newest first. */
    fun saveChatSession(context: Context, session: ChatSession) {
        ChatHistoryStore.saveSession(context, session)
        val item = session.toItem()
        conversations.removeAll { it.id == item.id }
        conversations.add(0, item)
        while (conversations.size > ChatHistoryStore.MAX_SESSIONS) {
            conversations.removeAt(conversations.lastIndex)
        }
    }

    /** Delete one saved chat, from the store and the list. */
    fun deleteConversation(context: Context, id: String) {
        ChatHistoryStore.deleteSession(context, id)
        conversations.removeAll { it.id == id }
    }

    /** Delete every saved chat, from the store and the list. */
    fun clearConversations(context: Context) {
        ChatHistoryStore.clearAll(context)
        conversations.clear()
    }

    fun applyThemeMode(context: Context, mode: String) {
        themeMode = mode
        NovaPreferences.putString(context, NovaPreferences.Keys.THEME_MODE, mode)
    }

    fun applyReducedMotion(context: Context, enabled: Boolean) {
        reducedMotion = enabled
        NovaPreferences.putBoolean(context, NovaPreferences.Keys.REDUCED_MOTION, enabled)
    }

    fun applyOrbStyle(context: Context, style: String) {
        orbStyle = style
        NovaPreferences.putString(context, NovaPreferences.Keys.ORB_STYLE, style)
    }

    fun applyAccent(context: Context, accentName: String) {
        val normalized = accentName.uppercase()
        accent = when (normalized) {
            "BLUE", "PURPLE" -> normalized
            else -> "ROSE"
        }
        NovaPreferences.putString(context, NovaPreferences.Keys.ACCENT, accent)
    }

    fun applyPersonality(context: Context, p: Personality) {
        personality = p
        NovaPreferences.putString(context, NovaPreferences.Keys.PERSONALITY, p.name)
        ioScope.launch {
            PersonalityRepository.setPersonality(context, p)
        }
    }

    fun applyAssistantName(context: Context, name: String) {
        val trimmed = name.trim().ifEmpty { AssistantBrand.NAME }
        assistantName = trimmed
        NovaPreferences.putString(context, NovaPreferences.Keys.ASSISTANT_NAME, trimmed)
        ioScope.launch {
            PersonalityRepository.setAssistantName(context, trimmed)
        }
    }

    fun applyUserName(context: Context, name: String) {
        val trimmed = name.trim().ifEmpty { "Friend" }
        userName = trimmed
        NovaPreferences.putString(context, NovaPreferences.Keys.USER_NAME, trimmed)
    }

    fun applyLanguage(context: Context, lang: LanguagePreference) {
        language = lang
        NovaPreferences.putString(context, NovaPreferences.Keys.LANGUAGE, lang.name)
        ioScope.launch {
            PersonalityRepository.setLanguage(context, lang)
        }
    }

    fun applyVoiceName(context: Context, voice: String) {
        val trimmed = voice.trim().ifEmpty { "Aoede" }
        voiceName = trimmed
        NovaPreferences.putString(context, NovaPreferences.Keys.VOICE_NAME, trimmed)
        ioScope.launch {
            PersonalityRepository.setVoiceName(context, trimmed)
        }
    }

    fun applySpeechSpeed(context: Context, speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        speechSpeed = clamped
        NovaPreferences.putFloat(context, NovaPreferences.Keys.SPEECH_SPEED, clamped)
    }

    fun applyVoiceVolume(context: Context, volume: Float) {
        val clamped = volume.coerceIn(0.1f, 1.0f)
        voiceVolume = clamped
        NovaPreferences.putFloat(context, NovaPreferences.Keys.VOICE_VOLUME, clamped)
    }

}
