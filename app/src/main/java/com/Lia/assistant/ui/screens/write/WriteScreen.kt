package com.Lia.assistant.ui.screens.write

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.GeminiTextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Writing modes offered on the Write screen. */
enum class WriteMode(val label: String, val systemInstruction: String) {
    NOTE(
        "Note",
        "You are a helpful writing assistant. Write a short, clear note on the user's topic. " +
            "Keep it concise — around 100-150 words — with short lines and simple language unless the user asks for more."
    ),
    CODE(
        "Code",
        "You are a helpful coding assistant. Write complete, working code for the user's request, " +
            "with brief comments explaining the key parts. Keep it concise: only the code the user asked for, " +
            "plus a one-line explanation at the top. No long essays."
    ),
    CONTENT(
        "Content",
        "You are a helpful writing assistant. Write engaging content (a caption, post, message or paragraph) " +
            "on the user's topic. Keep it concise — around 100-150 words — unless the user asks for a longer piece."
    ),
    STORY(
        "Story",
        "You are a creative writing assistant. Write a short story on the user's topic. " +
            "Keep it concise — around 150-250 words — with a clear beginning, middle and end, unless the user asks for a longer story."
    )
}

@Composable
fun WriteScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(WriteMode.NOTE) }
    var topic by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var savedFile by remember { mutableStateOf<File?>(null) }

    val hasKey = ApiKeyStore.hasKey(context)

    fun generate() {
        val trimmed = topic.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "Please tell Lia what to write about."
            return
        }
        if (!hasKey) {
            errorMessage = "Gemini API key is missing. Please add it in Settings."
            return
        }
        isGenerating = true
        errorMessage = null
        statusMessage = null
        savedFile = null
        scope.launch(Dispatchers.IO) {
            val apiKey = ApiKeyStore.getKey(context)
            // Reuse the existing text client — no second text client.
            val reply = GeminiTextClient.reply(
                apiKey = apiKey,
                systemInstruction = mode.systemInstruction,
                history = listOf(ChatTurn(isUser = true, text = trimmed))
            )
            withContext(Dispatchers.Main) {
                isGenerating = false
                when (reply) {
                    is ChatReplyResult.Success -> result = reply.text
                    is ChatReplyResult.Error -> errorMessage = reply.message
                }
            }
        }
    }

    fun copyResult() {
        val text = result ?: return
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("Lia writing", text))
        statusMessage = "Copied to clipboard."
    }

    fun saveResult() {
        val text = result ?: return
        scope.launch(Dispatchers.IO) {
            val file = saveTextFile(context, text)
            withContext(Dispatchers.Main) {
                if (file != null) {
                    savedFile = file
                    statusMessage = "Saved as ${file.name}. Tap Share to send it."
                } else {
                    statusMessage = "Could not save the file. Please try again."
                }
            }
        }
    }

    fun shareResult() {
        val text = result ?: return
        scope.launch(Dispatchers.IO) {
            // Save first if needed, then share through the existing
            // FileProvider path (cache "shared/" in file_paths.xml).
            val file = savedFile ?: saveTextFile(context, text)
            withContext(Dispatchers.Main) {
                if (file != null) {
                    savedFile = file
                    shareTextFile(context, file)
                } else {
                    statusMessage = "Could not share. Please try again."
                }
            }
        }
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
                text = "Write",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = colors.textPrimary
            )
        }
        Text(
            text = "Notes, code, content",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Missing-key card — honest, with a button to Settings.
        if (!hasKey) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceGlass)
                    .padding(16.dp)
            ) {
                Text(
                    text = "A Gemini API key is needed to write.",
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add your own free Gemini key in Settings — it is stored only on this phone.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(
                    text = "Open Settings",
                    onClick = { navController.navigate("settings") },
                    type = NovaButtonType.PRIMARY
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Mode chips
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WriteMode.values().forEach { option ->
                val selected = mode == option
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.accent else colors.surfaceGlass)
                        .clickable { mode = option }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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

        NovaTextField(
            value = topic,
            onValueChange = { topic = it },
            placeholder = when (mode) {
                WriteMode.NOTE -> "What should the note be about?"
                WriteMode.CODE -> "What code do you need? e.g. a Python function to sort a list"
                WriteMode.CONTENT -> "What should Lia write? e.g. a birthday caption"
                WriteMode.STORY -> "What should the story be about?"
            },
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        NovaButton(
            text = if (isGenerating) "Writing…" else "Write",
            onClick = { generate() },
            type = NovaButtonType.PRIMARY,
            icon = Icons.Default.Edit,
            enabled = !isGenerating && hasKey,
            modifier = Modifier.fillMaxWidth()
        )

        if (isGenerating) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Lia is writing…",
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

        // Result — scrollable with the screen, Copy / Save / Share.
        result?.let { text ->
            Spacer(modifier = Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceGlass)
                    .padding(16.dp)
            ) {
                Text(
                    text = text,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                NovaButton(
                    text = "Copy",
                    onClick = { copyResult() },
                    type = NovaButtonType.SECONDARY,
                    icon = Icons.Default.ContentCopy,
                    modifier = Modifier.weight(1f)
                )
                NovaButton(
                    text = "Save",
                    onClick = { saveResult() },
                    type = NovaButtonType.SECONDARY,
                    icon = Icons.Default.Save,
                    modifier = Modifier.weight(1f)
                )
                NovaButton(
                    text = "Share",
                    onClick = { shareResult() },
                    type = NovaButtonType.PRIMARY,
                    icon = Icons.Default.Share,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.success
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Save the text as a .txt file under cache/shared/ — the FileProvider
 * cache-path already declared in file_paths.xml, same as Forge sharing. */
private fun saveTextFile(context: Context, text: String): File? {
    return try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "lia-write-${System.currentTimeMillis()}.txt")
        file.writeText(text)
        file
    } catch (_: Exception) {
        null
    }
}

/** Share the saved .txt via the repo's existing FileProvider — same
 * pattern as ResultToolbar.shareWebsiteFile. */
private fun shareTextFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Writing").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (_: Exception) {}
}
