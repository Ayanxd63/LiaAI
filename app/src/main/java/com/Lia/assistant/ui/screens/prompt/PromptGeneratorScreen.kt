package com.Lia.assistant.ui.screens.prompt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
 * V20 — Prompt Generator, simplified to the bare minimum: one topic
 * field, one "Prompt banayein" button, the result with a Copy button.
 * Removed: the Chat/Image mode picker (one fixed general instruction
 * now covers both uses), the 8 template cards, the editable draft
 * field (the result is shown as plain text), and the "Open Chat /
 * Open Create Image" button (copy the prompt and paste it where
 * you need it). Powered by Pollinations text — free, no key.
 */
@Composable
fun PromptGeneratorScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var topic by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun generatePrompt() {
        val source = topic.trim()
        if (source.isEmpty()) {
            errorMessage = "Pehle apna idea likho — kya banana hai?"
            return
        }
        isGenerating = true
        errorMessage = null
        statusMessage = null
        scope.launch(Dispatchers.IO) {
            // V20: one fixed instruction (was two, one per mode).
            val systemInstruction = "You are a prompt engineer. Rewrite the user's rough idea " +
                "into ONE clear, detailed prompt — subject, setting, style, colours, mood " +
                "when they fit. Keep the user's idea and language (Roman Urdu or English " +
                "as given). Reply with ONLY the improved prompt, no explanation, no quotes."
            val reply = PollinationsClient.chat(
                systemInstruction = systemInstruction,
                history = listOf(ChatTurn(isUser = true, text = source))
            )
            withContext(Dispatchers.Main) {
                isGenerating = false
                when (reply) {
                    is ChatReplyResult.Success -> {
                        result = reply.text.trim()
                        statusMessage = "Prompt tayyar — copy kar lo."
                    }
                    is ChatReplyResult.Error -> errorMessage = reply.message
                }
            }
        }
    }

    fun copyPrompt() {
        if (result.isBlank()) {
            errorMessage = "Copy karne ke liye pehle koi prompt banao."
            return
        }
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("Lia prompt", result.trim()))
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
            text = "Apna idea likho — Lia behtareen prompt bana degi. Free, no key.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Topic field
        NovaTextField(
            value = topic,
            onValueChange = { topic = it },
            placeholder = "e.g. a cat and dog chase, ya 'meri tareef karo'",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        NovaButton(
            text = if (isGenerating) "Bana rahi hun…" else "Prompt banayein",
            onClick = { generatePrompt() },
            type = NovaButtonType.PRIMARY,
            icon = Icons.Default.AutoAwesome,
            enabled = !isGenerating,
            modifier = Modifier.fillMaxWidth()
        )

        if (isGenerating) {
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

        // The result + Copy button
        if (result.isNotBlank()) {
            Spacer(modifier = Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceGlass)
                    .padding(16.dp)
            ) {
                Text(
                    text = result,
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            NovaButton(
                text = "Copy",
                onClick = { copyPrompt() },
                type = NovaButtonType.SECONDARY,
                icon = Icons.Default.ContentCopy,
                modifier = Modifier.fillMaxWidth()
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

        Spacer(modifier = Modifier.height(32.dp))
    }
}
