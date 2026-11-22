package com.Lia.assistant.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.action.AppContext
import com.Lia.assistant.action.DeviceActions
import com.Lia.assistant.action.DeviceCommandParser
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.ChatDisplayPreferences
import com.Lia.assistant.data.ChatEngine
import com.Lia.assistant.data.ChatHistoryMessage
import com.Lia.assistant.data.ChatHistoryStore
import com.Lia.assistant.data.ChatPrefill
import com.Lia.assistant.data.ChatSession
import com.Lia.assistant.data.DeviceTtsVoicePreferences
import com.Lia.assistant.data.ElevenLabsStore
import com.Lia.assistant.data.EnginePresetPreferences
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.MemoryCapture
import com.Lia.assistant.data.MemoryFact
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.Personality
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.forge.ForgeIntent
import com.Lia.assistant.ui.components.ChatMessage
import com.Lia.assistant.ui.components.NovaMessageBubble
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTypingDots
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.depthSurface
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.ChatPromptBuilder
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.CuteVoice
import com.Lia.assistant.voice.ElevenLabsAudioPlayer
import com.Lia.assistant.voice.ElevenLabsClient
import com.Lia.assistant.voice.ElevenLabsResult
import com.Lia.assistant.voice.GeminiTextClient
import com.Lia.assistant.voice.LiaVoiceBeats
import com.Lia.assistant.voice.NovaOrbState
import com.Lia.assistant.voice.OfflineLlmClient
import com.Lia.assistant.voice.PollinationsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cap on characters sent to ElevenLabs per reply (free-tier friendly). */
private const val MAX_ELEVENLABS_TTS_CHARS = 1000

/**
 * V13 — show engine failures the way a girlfriend would: warm Roman
 * Urdu, never a raw "Error: ..." line. Network problems (the common
 * case on a slow connection) get the offline message; overload gets a
 * "try again in a minute"; anything else is shown as-is, without the
 * "Error: " prefix.
 */
private fun friendlyDisplayError(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("couldn't reach") || lower.contains("internet") ||
            lower.contains("network") || lower.contains("connection") ->
            "Net nahi lag raha — WiFi ya mobile data check kar ke dobara try karo. Main yahin hun 💖"
        lower.contains("busy") || lower.contains("overload") ||
            lower.contains("high demand") || lower.contains("rate limit") ->
            "Free AI abhi thoda busy hai — ek minute ruk ke dobara try karo."
        else -> raw.removePrefix("Error: ").trim().ifEmpty { "Kuch garbar ho gayi — dobara try karo." }
    }
}

@Composable
fun ChatScreen(
    navController: NavController,
    appState: NovaAppState,
    // V13: when opened from History, the saved chat to continue.
    sessionId: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // V13: warm first-run welcome from the Girlfriend — only when the
    // user has never chatted before. A fresh chat later gets the short
    // greeting; other personalities keep the classic hello.
    val isGirlfriend = appState.personality == Personality.GF_MODE
    val welcomeText = when {
        isGirlfriend && appState.conversations.isEmpty() ->
            "Hey jan! 💖 Main tumhari Lia hun — tumhari apni girlfriend. " +
                "Aaj kaisa feel kar rahe ho? Batao na, main sun rahi hun."
        isGirlfriend -> "Hey jan! 💖 Batao, kya baat hai?"
        else -> "Hello! I am ${appState.assistantName}. How can I assist you today?"
    }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                text = welcomeText,
                isUser = false
            )
        )
    }

    // V13: the session this chat is being saved under. Created on the
    // first user message of a fresh chat; taken from History otherwise.
    var currentSessionId by remember { mutableStateOf(sessionId) }

    // V13: reopening a saved chat replaces the welcome message with the
    // real turns, so the conversation continues where it stopped.
    // V15 (chat-continuity fix, ayan 2026-11-22): opening the Chat tab
    // with NO sessionId used to show a fresh empty chat — the old
    // messages were only reachable via History. Now it loads the
    // LATEST saved session (if any) and continues THAT session, so the
    // old messages stay visible above. No saved session = the fresh
    // welcome behaviour, unchanged. The JSON load runs on IO.
    LaunchedEffect(sessionId) {
        val sessions = withContext(Dispatchers.IO) {
            runCatching { ChatHistoryStore.loadSessions(context) }.getOrDefault(emptyList())
        }
        val target = if (sessionId != null) {
            sessions.firstOrNull { it.id == sessionId }
        } else {
            sessions.firstOrNull()
        }
        if (target != null && target.messages.isNotEmpty()) {
            currentSessionId = target.id
            messages.clear()
            messages.addAll(
                target.messages.map {
                    ChatMessage(text = it.text, isUser = it.role == "user")
                }
            )
        }
    }

    /**
     * V13: persist this chat (title = first thing the user said,
     * preview = latest message) so it survives app restarts and shows
     * up in History. Skipped until the user has said something.
     */
    fun persistSession() {
        val id = currentSessionId
            ?: java.util.UUID.randomUUID().toString().also { currentSessionId = it }
        val turns = messages.filter { !it.isStreaming && it.text.isNotBlank() }
        val firstUser = turns.firstOrNull { it.isUser }?.text ?: return
        appState.saveChatSession(
            context,
            ChatSession(
                id = id,
                title = firstUser.take(40),
                preview = turns.last().text.take(80),
                personality = appState.personality.displayName,
                messages = turns.map {
                    ChatHistoryMessage(
                        role = if (it.isUser) "user" else "assistant",
                        text = it.text
                    )
                }
            )
        )
    }

    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    // Shown under the header while a reply is being retried after overload.
    var retryNote by remember { mutableStateOf<String?>(null) }
    // ElevenLabs (v10): id of the chat reply currently playing via ElevenLabs,
    // or null when none is playing. Also drives the speaker toggle icon.
    var elevenLabsPlayingId by remember { mutableStateOf<String?>(null) }
    // Short user-readable note when ElevenLabs speaking fails (e.g. bad key,
    // quota used up). Shown in the header subtitle; never a stack trace.
    var elevenNote by remember { mutableStateOf<String?>(null) }

    // V15: a fact the auto-rules proposed, waiting for "Yaad rakhun?
    // Yes / No". Saved only on yes, dropped on no — never silent.
    var pendingMemoryFact by remember { mutableStateOf<MemoryFact?>(null) }

    /** V15: start a genuinely fresh chat (header "New chat" action). */
    fun startNewChat() {
        currentSessionId = null
        pendingMemoryFact = null
        retryNote = null
        elevenNote = null
        messages.clear()
        messages.add(ChatMessage(text = welcomeText, isUser = false))
    }

    // TTS lifecycle
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    DisposableEffect(Unit) {
        // NOTE: the instance must be declared BEFORE the constructor call —
        // referencing the val inside its own init lambda is an "unresolved
        // reference" compile error (run #91). onInit fires after construction,
        // so ttsInstance is assigned by the time the lambda runs.
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // V12: the cute voice — a female system voice, a little
                // higher and slower. No key, no network, works offline.
                // V16 (P0-5): the user's picked device voice wins when set.
                ttsInstance?.let {
                    CuteVoice.applyCuteVoice(
                        it,
                        DeviceTtsVoicePreferences.getVoiceName(context)
                    )
                }
            }
        }
        tts = ttsInstance
        ElevenLabsAudioPlayer.onPlaybackFinished = { elevenLabsPlayingId = null }
        onDispose {
            ElevenLabsAudioPlayer.onPlaybackFinished = null
            ElevenLabsAudioPlayer.stop()
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    /**
     * Speak one assistant reply out loud.
     *
     * ElevenLabs voice selected → synthesize the reply with ElevenLabs and
     * play the MP3. No ElevenLabs voice selected → exactly the old behaviour
     * (on-device TextToSpeech, Gemini path). Tapping the same reply while it
     * is playing stops it (speaker toggle).
     *
     * ElevenLabs audio intentionally does NOT drive lip sync: it never
     * touches the orb state, so Lia's 3D character stays still while the
     * ElevenLabs voice plays — the same honest limitation the official app
     * has.
     */
    fun speakReply(msg: ChatMessage) {
        if (msg.text.isBlank()) return
        if (elevenLabsPlayingId == msg.id) {
            ElevenLabsAudioPlayer.stop()
            elevenLabsPlayingId = null
            return
        }
        val elevenVoiceId = ElevenLabsStore.selectedVoiceId(context)
        if (elevenVoiceId.isEmpty()) {
            // V12 cute voice (system TTS, no key): re-apply pitch/rate in
            // case the engine reset them.
            tts?.setPitch(CuteVoice.PITCH)
            tts?.setSpeechRate(CuteVoice.RATE)
            tts?.speak(msg.text, TextToSpeech.QUEUE_FLUSH, null, null)
            return
        }
        elevenNote = null
        elevenLabsPlayingId = msg.id
        scope.launch(Dispatchers.IO) {
            val result = ElevenLabsClient.synthesize(
                apiKey = ElevenLabsStore.getKey(context),
                voiceId = elevenVoiceId,
                modelId = ElevenLabsStore.selectedModel(context),
                text = msg.text.take(MAX_ELEVENLABS_TTS_CHARS)
            )
            withContext(Dispatchers.Main) {
                when (result) {
                    is ElevenLabsResult.Success -> {
                        scope.launch {
                            val started = ElevenLabsAudioPlayer.play(context, result.data)
                            if (!started) {
                                elevenLabsPlayingId = null
                                elevenNote = "Couldn't play the ElevenLabs voice."
                            }
                        }
                    }
                    is ElevenLabsResult.Error -> {
                        elevenLabsPlayingId = null
                        elevenNote = result.message
                    }
                }
            }
        }
    }

    // Auto-scroll on new message; during streaming the bubble grows
    // in place, so scroll (no animation) on every text change too.
    LaunchedEffect(messages.size, isTyping, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            if (messages.last().isStreaming) {
                listState.scrollToItem(messages.size - 1)
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        messages.add(ChatMessage(text = trimmed, isUser = true))
        inputText = ""
        // V13: the user said something — this chat now has a session.
        persistSession()

        // V15: long-term memory — answer a pending "Yaad rakhun?" first,
        // then check for a new memory command. Both return early (no
        // engine call); a non-yes/no answer drops the pending question
        // and is treated as a normal message.
        val pending = pendingMemoryFact
        if (pending != null) {
            if (MemoryCapture.isYes(trimmed)) {
                FactMemoryStore.saveFact(context, pending)
                pendingMemoryFact = null
                messages.add(
                    ChatMessage(
                        text = "Yaad rakh liya 💖 \"${pending.text}\"",
                        isUser = false
                    )
                )
                persistSession()
                return
            }
            if (MemoryCapture.isNo(trimmed)) {
                pendingMemoryFact = null
                messages.add(
                    ChatMessage(
                        text = "Theek hai, yaad nahi rakhungi.",
                        isUser = false
                    )
                )
                persistSession()
                return
            }
            pendingMemoryFact = null
        }
        if (FactMemoryStore.isEnabled(context)) {
            when (val capture = MemoryCapture.analyze(trimmed)) {
                is MemoryCapture.Capture.Save -> {
                    FactMemoryStore.saveFact(context, capture.fact)
                    messages.add(
                        ChatMessage(
                            text = "Yaad rakh liya 💖 \"${capture.fact.text}\"",
                            isUser = false
                        )
                    )
                    persistSession()
                    return
                }
                is MemoryCapture.Capture.Confirm -> {
                    pendingMemoryFact = capture.fact
                    messages.add(
                        ChatMessage(
                            text = "Yaad rakhun? \"${capture.fact.text}\"",
                            isUser = false
                        )
                    )
                    persistSession()
                    return
                }
                is MemoryCapture.Capture.None -> {
                    // Normal message — falls through to the engine below.
                }
            }
        }

        // Check Website Forge intent
        val forgePrompt = ForgeIntent.extract(trimmed)
        if (forgePrompt != null) {
            messages.add(
                ChatMessage(
                    text = "Opening the Website Forge for: \"$forgePrompt\"...",
                    isUser = false
                )
            )
            persistSession()
            ForgeController.start(context, forgePrompt)
            navController.navigate("forge")
            return
        }

        // Device command? Execute it LOCALLY on the phone instead of
        // sending it to Gemini — works even on a very slow network.
        // AppContext.current() is the app Lia opened last, so bare
        // follow-ups like "song search karo" run in that app.
        val deviceCommand = DeviceCommandParser.parse(trimmed, AppContext.current())
        if (deviceCommand != null) {
            isTyping = true
            scope.launch(Dispatchers.IO) {
                val reply = try {
                    DeviceActions.execute(context, deviceCommand)
                } catch (_: Exception) {
                    "Ye kaam nahi ho saka — dobara try karo."
                }
                withContext(Dispatchers.Main) {
                    isTyping = false
                    messages.add(ChatMessage(text = reply, isUser = false))
                    persistSession()
                }
            }
            return
        }

        // V12/V15: call the selected engine. Pollinations is the default —
        // free, no key, works the moment the app opens. Gemini is used
        // only when the user added a key AND picked Gemini in Settings.
        // OFFLINE is the on-device model (no network, no key).
        // V15 (fast replies): the reply STREAMS in live — Pollinations
        // tokens and offline tokens fill the bubble as they arrive,
        // instead of waiting for the whole answer. A rate-limited or
        // unreachable online engine falls back to a quick offline reply
        // at once (with a plain-language label) instead of a long wait.
        isTyping = true
        retryNote = null
        elevenNote = null
        scope.launch(Dispatchers.IO) {
            val apiKey = ApiKeyStore.getKey(context)
            val preset = EnginePresetPreferences.getPreset(context)
            // V16: Lia's own voice beats + the preset's style instruction.
            val systemInstruction = ChatPromptBuilder.build(
                personality = appState.personality,
                language = appState.language,
                assistantName = appState.assistantName,
                memoryBlock = FactMemoryStore.memoryBlock(context),
                beats = LiaVoiceBeats.section(appState.personality, appState.userName),
                presetInstruction = EnginePresetPreferences.instruction(preset)
            )

            val history = messages.map { ChatTurn(isUser = it.isUser, text = it.text) }
            // V16 (P1-12): human-paced typing — the reply is fetched whole
            // and typed out word by word at a human pace, instead of
            // streaming in live.
            val humanPaced = ChatDisplayPreferences.isHumanPacedTyping(context)
            val engines = EnginePresetPreferences.resolveEngines(context)

            // The streaming placeholder: created on Main first, then
            // filled token by token as the reply arrives.
            val placeholderId = java.util.UUID.randomUUID().toString()
            withContext(Dispatchers.Main) {
                messages.add(
                    ChatMessage(id = placeholderId, text = "", isUser = false, isStreaming = true)
                )
            }
            fun updatePlaceholder(text: String) {
                // Stream callbacks run on IO — hop to Main for state.
                scope.launch {
                    val idx = messages.indexOfFirst { it.id == placeholderId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(text = text)
                    }
                }
            }
            val onRetrying: (Int) -> Unit = { attempt ->
                // reply() runs on IO; hop to Main to update Compose state.
                scope.launch {
                    retryNote = "Thoda wait — dobara try kar rahi hun… (koshish $attempt/3)"
                }
            }

            // V16 (P0-6): try the engines in preset order. A rate-limit /
            // network error moves to the next engine; a hard error stops
            // the chain. Falling back TO offline keeps the plain-language
            // label (usedOfflineFallback, shown until the turn ends).
            var result: ChatReplyResult = ChatReplyResult.Error(
                "Koi engine available nahi — net check karo, ya Settings > " +
                    "Offline AI se model download karo."
            )
            var usedOfflineFallback = false
            for ((index, engine) in engines.withIndex()) {
                val isLast = index == engines.lastIndex
                val attempt: ChatReplyResult = when (engine) {
                    ChatEngine.OFFLINE -> OfflineLlmClient.chat(
                        context,
                        systemInstruction,
                        trimmed,
                        onToken = if (humanPaced) {
                            null
                        } else {
                            { text -> updatePlaceholder(text) }
                        }
                    )
                    ChatEngine.GEMINI -> GeminiTextClient.reply(
                        apiKey,
                        systemInstruction,
                        history,
                        onRetrying = onRetrying
                    )
                    ChatEngine.POLLINATIONS -> if (humanPaced) {
                        PollinationsClient.chat(
                            systemInstruction,
                            history,
                            onRetrying = onRetrying
                        )
                    } else {
                        PollinationsClient.chatStream(
                            systemInstruction,
                            history,
                            onToken = { updatePlaceholder(it) },
                            onRetrying = onRetrying
                        )
                    }
                }
                when (attempt) {
                    is ChatReplyResult.Success -> {
                        result = attempt
                        break
                    }
                    is ChatReplyResult.Error -> {
                        result = attempt
                        if ((attempt.isRateLimited || attempt.isNetworkError) && !isLast) {
                            val nextEngine = engines[index + 1]
                            if (nextEngine == ChatEngine.OFFLINE) {
                                usedOfflineFallback = true
                                scope.launch {
                                    retryNote = "Offline jawab — net slow tha, is liye phone se jawab de rahi hun."
                                }
                            } else {
                                scope.launch {
                                    retryNote = "Thoda wait — doosre engine se try kar rahi hun…"
                                }
                            }
                            continue
                        }
                        break
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isTyping = false
                if (!usedOfflineFallback) retryNote = null
                when (result) {
                    is ChatReplyResult.Success -> {
                        val finalIdx = messages.indexOfFirst { it.id == placeholderId }
                        if (finalIdx != -1) {
                            if (messages[finalIdx].text.isBlank()) {
                                // Non-streaming engine (Gemini): the
                                // placeholder is still empty — keep the
                                // old word-by-word reveal. V16 (P1-12):
                                // human-paced typing reveals at 90ms/word.
                                val fullReply = result.text
                                val words = fullReply.split(" ")
                                scope.launch {
                                    val builder = StringBuilder()
                                    for (w in words) {
                                        if (builder.isNotEmpty()) builder.append(" ")
                                        builder.append(w)
                                        val idx = messages.indexOfFirst { it.id == placeholderId }
                                        if (idx != -1) {
                                            messages[idx] = messages[idx].copy(text = builder.toString())
                                        }
                                        delay(if (humanPaced) 90 else 20)
                                    }
                                    val doneIdx = messages.indexOfFirst { it.id == placeholderId }
                                    if (doneIdx != -1) {
                                        messages[doneIdx] = messages[doneIdx].copy(isStreaming = false)
                                        // V13: the reply is complete — save the chat.
                                        persistSession()
                                        // ElevenLabs text-chat TTS (v10): when an
                                        // ElevenLabs voice is selected, speak Lia's
                                        // reply aloud with that voice. No voice
                                        // selected → nothing auto-plays, exactly as
                                        // before (user taps the speaker to listen).
                                        if (ElevenLabsStore.hasSelectedVoice(context)) {
                                            speakReply(messages[doneIdx])
                                        }
                                    }
                                }
                            } else {
                                // Streamed (Pollinations / offline): the
                                // text is already there — just finish.
                                messages[finalIdx] = messages[finalIdx].copy(isStreaming = false)
                                persistSession()
                                if (ElevenLabsStore.hasSelectedVoice(context)) {
                                    speakReply(messages[finalIdx])
                                }
                            }
                        }
                    }
                    is ChatReplyResult.Error -> {
                        // Drop the empty streaming placeholder, then show
                        // the friendly error bubble.
                        val idx = messages.indexOfFirst { it.id == placeholderId }
                        if (idx != -1) messages.removeAt(idx)
                        messages.add(
                            ChatMessage(
                                // V13: warm Roman Urdu, never "Error: ...".
                                text = friendlyDisplayError(result.message),
                                isUser = false,
                                isError = true
                            )
                        )
                        persistSession()
                    }
                }
            }
        }
    }

    // V16 (P0-1): the Home ask bar parks a question here; pick it up
    // on entry and send it as a normal message. Placed after
    // sendMessage is declared — a LaunchedEffect above it could not
    // reference the local function.
    LaunchedEffect(Unit) {
        val prefill = ChatPrefill.take()
        if (prefill != null) sendMessage(prefill)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .imePadding()
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }

            // Small 40dp 3D orb in header
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { navController.navigate("voice") },
                contentAlignment = Alignment.Center
            ) {
                LiaOrb3D(
                    state = if (isTyping) NovaOrbState.THINKING else NovaOrbState.IDLE,
                    size = 40.dp,
                    amplitude = if (isTyping) 0.3f else 0f,
                    assistantName = appState.assistantName
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appState.assistantName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.textPrimary
                )
                Text(
                    text = elevenNote ?: retryNote ?: if (isTyping) "Typing..." else "Here to help",
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }

            // V15: explicit "New chat" — the Chat tab now opens the
            // latest saved chat, so a fresh chat needs a real button.
            if (messages.size > 1) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceGlass)
                        .clickable { startNewChat() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "New chat",
                        fontSize = 12.sp,
                        color = colors.textPrimary
                    )
                }
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                NovaMessageBubble(
                    message = msg,
                    onSpeakClick = { speakReply(msg) },
                    onCopyClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clip?.setPrimaryClip(ClipData.newPlainText("Lia message", msg.text))
                    },
                    onRetryClick = {
                        sendMessage(messages.lastOrNull { it.isUser }?.text ?: "")
                    }
                )
                // Speaker toggle on assistant replies: replay with the
                // selected voice (ElevenLabs) or the old TTS (Gemini);
                // tap again while playing to stop.
                if (!msg.isUser && !msg.isError && msg.text.isNotBlank() && !msg.isStreaming) {
                    val isPlayingThis = elevenLabsPlayingId == msg.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { speakReply(msg) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlayingThis) Icons.Default.Stop else Icons.Default.VolumeUp,
                                contentDescription = if (isPlayingThis) {
                                    "Stop"
                                } else {
                                    "Listen to this reply"
                                },
                                tint = colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = if (isPlayingThis) {
                                "Stop"
                            } else if (ElevenLabsStore.hasSelectedVoice(context)) {
                                "Listen · ElevenLabs"
                            } else {
                                "Listen"
                            },
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            if (isTyping) {
                item {
                    NovaTypingDots(modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                }
            }
        }

        // V13: quick-reply chips on a fresh chat — one tap to start
        // talking, no typing needed. Girlfriend-flavoured by default.
        if (messages.size <= 1 && !isTyping) {
            val quickReplies = if (isGirlfriend) {
                listOf("Kaisi ho? 💖", "Mujhe miss kiya?", "Ek joke sunao", "Aaj kya karein?")
            } else {
                listOf("Hello!", "Ek joke sunao", "Mujhe ek kahani sunao", "Tum kya kar sakti ho?")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickReplies.forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surfaceGlass)
                            .clickable { sendMessage(label) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            color = colors.textPrimary
                        )
                    }
                }
                // V15: second entry point for the Prompt Generator — on
                // Home it sits below the fold, so it must be reachable
                // from Chat too.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surfaceGlass)
                        .clickable { navController.navigate("prompt_generator") }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Prompt banao ✍️",
                        fontSize = 13.sp,
                        color = colors.textPrimary
                    )
                }
            }
        }

        // V15: "Yaad rakhun?" confirmation chips — one tap answers the
        // pending memory question (also answerable by typing Haan/Nahi).
        if (pendingMemoryFact != null && !isTyping) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.accent)
                        .clickable { sendMessage("Haan") }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Haan, yaad rakho 💖",
                        fontSize = 13.sp,
                        color = colors.onAccent
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surfaceGlass)
                        .clickable { sendMessage("Nahi") }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nahi",
                        fontSize = 13.sp,
                        color = colors.textPrimary
                    )
                }
            }
        }

        // Floating Glass Input Bar
        val inputShape = RoundedCornerShape(28.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .depthSurface(shape = inputShape, elevation = 4.dp)
                .clip(inputShape)
                .background(colors.surfaceGlass)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovaTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = "Message ${appState.assistantName}...",
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable { sendMessage(inputText) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = colors.onAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
