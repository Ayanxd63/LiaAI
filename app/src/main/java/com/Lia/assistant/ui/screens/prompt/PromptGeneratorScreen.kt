package com.Lia.assistant.ui.screens.prompt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.PollinationsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V12 — Prompt Generator. Turns a rough idea into a strong prompt for
 * chat or for image generation, powered by Pollinations text (free,
 * no key — same engine as chat). No key is ever asked for.
 *
 * Flow: pick Chat or Image → type a topic → tap a template (fills the
 * draft) → "Improve with AI" rewrites it → Copy, or open Chat /
 * Create Image to use it.
 */
private enum class PromptMode(val label: String) {
    CHAT("Chat prompt"),
    IMAGE("Image prompt")
}

private data class PromptTemplate(val label: String, val base: String)

private val CHAT_TEMPLATES = listOf(
    PromptTemplate(
        "Girlfriend chat",
        "Talk to me like my loving girlfriend in Roman Urdu — sweet, warm, short replies, full of feelings."
    ),
    PromptTemplate(
        "Study help",
        "Explain this topic in simple, easy words with examples, like a friendly teacher."
    ),
    PromptTemplate(
        "Story",
        "Tell a short, fun story about this topic, with a happy ending."
    ),
    PromptTemplate(
        "Joke / funny",
        "Make a light, funny joke about this topic in Roman Urdu."
    )
)

private val IMAGE_TEMPLATES = listOf(
    PromptTemplate(
        "Cute cat-dog cartoon",
        "A cute grey cat and a brown dog in a funny slapstick chase, bright cartoon style, sunny garden."
    ),
    PromptTemplate(
        "Beautiful scenery",
        "A beautiful peaceful scenery, golden sunset, mountains and a lake, photorealistic."
    ),
    PromptTemplate(
        "Anime portrait",
        "A beautiful anime-style portrait of a girl with big eyes and a soft smile, colourful background."
    ),
    PromptTemplate(
        "Festival",
        "A joyful festival celebration with lights, colours and happy people, bright and cheerful."
    )
)

@Composable
fun PromptGeneratorScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(PromptMode.CHAT) }
    var topic by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var isImproving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val templates = if (mode == PromptMode.CHAT) CHAT_TEMPLATES else IMAGE_TEMPLATES

    fun improveWithAi() {
        val source = draft.trim().ifEmpty { topic.trim() }
        if (source.isEmpty()) {
            errorMessage = "Pehle topic ya prompt likho, phir Improve dabao."
            return
        }
        isImproving = true
        errorMessage = null
        statusMessage = null
        scope.launch(Dispatchers.IO) {
            val systemInstruction = if (mode == PromptMode.CHAT) {
                "You are a prompt engineer. Rewrite the user's rough idea into ONE clear, " +
                    "detailed chat prompt for an AI girlfriend companion. Keep the user's idea " +
                    "and language (Roman Urdu or English as given). Reply with ONLY the " +
                    "improved prompt, no explanation, no quotes."
            } else {
                "You are a prompt engineer. Rewrite the user's rough idea into ONE clear, " +
                    "detailed, vivid image-generation prompt: subject, setting, style, colours, " +
                    "mood. Keep the user's idea. Reply with ONLY the improved prompt, no " +
                    "explanation, no quotes."
            }
            val result = PollinationsClient.chat(
                systemInstruction = systemInstruction,
                history = listOf(ChatTurn(isUser = true, text = source))
            )
            withContext(Dispatchers.Main) {
                isImproving = false
                when (result) {
                    is ChatReplyResult.Success -> {
                        draft = result.text.trim()
                        statusMessage = "Prompt improved — copy it or open Chat / Create Image to use it."
                    }
                    is ChatReplyResult.Error -> errorMessage = result.message
                }
            }
        }
    }

    fun copyPrompt() {
        if (draft.isBlank()) {
            errorMessage = "Copy karne ke liye pehle koi prompt banao."
            return
        }
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("Lia prompt", draft.trim()))
        statusMessage = "Prompt copied."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Text(
                text = "Prompt Generator",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = colors.textPrimary
            )
        }
        Text(
            text = "Free — no key needed. Better prompts, better answers.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Mode choice
        NovaSectionHeader(title = "What is this prompt for?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PromptMode.values().forEach { option ->
                val selected = mode == option
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.accent else colors.surfaceGlass)
                        .clickable { mode = option }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) colors.onAccent else colors.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Topic field
        NovaSectionHeader(title = "Your idea / topic")
        NovaTextField(
            value = topic,
            onValueChange = { topic = it },
            placeholder = "e.g. a cat and dog chase, or 'meri tareef karo'",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Templates
        NovaSectionHeader(title = "Or start from a template")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            templates.forEach { template ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceGlass)
                        .clickable {
                            draft = template.base
                            if (topic.isBlank()) topic = template.label
                            errorMessage = null
                            statusMessage = null
                        }
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = template.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = template.base,
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Editable draft
        NovaSectionHeader(title = "Your prompt (edit it if you like)")
        NovaTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Your prompt will appear here…",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        NovaButton(
            text = if (isImproving) "Improving…" else "Improve with AI",
            onClick = { improveWithAi() },
            type = NovaButtonType.PRIMARY,
            icon = Icons.Default.AutoAwesome,
            enabled = !isImproving,
            modifier = Modifier.fillMaxWidth()
        )

        if (isImproving) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Lia is improving your prompt…",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.error
            )
        }

        statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.success
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Use the prompt
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            NovaButton(
                text = "Copy",
                onClick = { copyPrompt() },
                type = NovaButtonType.SECONDARY,
                icon = Icons.Default.ContentCopy,
                modifier = Modifier.weight(1f)
            )
            NovaButton(
                text = if (mode == PromptMode.CHAT) "Open Chat" else "Open Create Image",
                onClick = {
                    navController.navigate(if (mode == PromptMode.CHAT) "chat" else "create_image")
                },
                type = NovaButtonType.PRIMARY,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
