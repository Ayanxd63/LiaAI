package com.Lia.assistant.ui.screens.subagents

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.Lia.assistant.data.SubAgentKind
import com.Lia.assistant.data.SubAgentResult
import com.Lia.assistant.data.SubAgentRunner
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * V16 (P1-9) — the sub-agent output contract.
 *
 * Three sub-agents: Research, Story, Song-list. Each takes a topic,
 * asks the first available engine, and writes the result to a DATED
 * text file in Documents/LiaAI/. The file is the output: Open reads it
 * back, Share sends it. When no engine answers, the failure is shown
 * in plain words — never an empty file, never a fake "done".
 */
@Composable
fun SubAgentsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputs by remember { mutableStateOf<Map<SubAgentKind, String>>(emptyMap()) }
    var busyKind by remember { mutableStateOf<SubAgentKind?>(null) }
    var results by remember { mutableStateOf<Map<SubAgentKind, SubAgentResult>>(emptyMap()) }

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp)
    ) {
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
            Text(
                text = "Sub-agents",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Har sub-agent apna kaam karke ek dated file banata hai — " +
                "Documents/LiaAI/ mein. Open karo ya Share karo.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        for (kind in SubAgentKind.values()) {
            val input = inputs[kind].orEmpty()
            val busy = busyKind == kind
            val result = results[kind]
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Text(
                        text = kind.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colors.textPrimary
                    )
                    Text(
                        text = when (kind) {
                            SubAgentKind.RESEARCH ->
                                "Kisi topic par short research note — facts + bullet points."
                            SubAgentKind.STORY ->
                                "Ek chhoti si original kahani — warm, fun, happy ending."
                            SubAgentKind.SONG_LIST ->
                                "Mood ya occasion ke liye 8 gaano ki list."
                        },
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    NovaTextField(
                        value = input,
                        onValueChange = { inputs = inputs + (kind to it) },
                        placeholder = when (kind) {
                            SubAgentKind.RESEARCH -> "Topic (e.g. Big Bang theory…)"
                            SubAgentKind.STORY -> "Story idea (e.g. ek billi aur kutta…)"
                            SubAgentKind.SONG_LIST -> "Mood ya occasion (e.g. drive…)"
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    NovaButton(
                        text = if (busy) "Kaam ho raha hai…" else "Run",
                        onClick = {
                            if (input.isBlank() || busy) return@NovaButton
                            busyKind = kind
                            results = results - kind
                            scope.launch(Dispatchers.IO) {
                                val output = SubAgentRunner.run(context, kind, input)
                                withContext(Dispatchers.Main) {
                                    busyKind = null
                                    results = results + (kind to output)
                                }
                            }
                        }
                    )
                    when (result) {
                        is SubAgentResult.Success -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            NovaInfoCard(
                                title = "File ban gayi ✓",
                                body = "${result.file.name} — ${result.chars} characters, " +
                                    "Documents/LiaAI/ mein save hai."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NovaButton(
                                    text = "Open",
                                    onClick = { openFile(result.file) },
                                    type = NovaButtonType.SECONDARY
                                )
                                NovaButton(
                                    text = "Share",
                                    onClick = { shareFile(result.file) }
                                )
                            }
                        }
                        is SubAgentResult.Failure -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            NovaInfoCard(
                                title = "Nahi ho saka",
                                body = result.message
                            )
                        }
                        null -> {}
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
