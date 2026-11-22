package com.Lia.assistant.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.MemoryFact
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

/**
 * V15 — long-term memory controls (Settings → Memory).
 *
 * Everything Lia remembers about the user, on this phone only:
 * view, edit, delete one fact, or clear them all. The master toggle
 * turns capture AND prompt injection off together. Facts are never
 * captured silently (see MemoryCapture) and never leave the phone
 * except inside a chat prompt while chatting.
 */
@Composable
fun MemoryScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = androidx.compose.ui.platform.LocalContext.current

    var memoryOn by remember { mutableStateOf(FactMemoryStore.isEnabled(context)) }
    var facts by remember { mutableStateOf(FactMemoryStore.loadFacts(context)) }

    fun refresh() {
        facts = FactMemoryStore.loadFacts(context)
    }

    var editingFact by remember { mutableStateOf<MemoryFact?>(null) }
    var editText by remember { mutableStateOf("") }
    var showClearAll by remember { mutableStateOf(false) }

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
                text = "Memory",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Jo main tumhare baare mein yaad rakhti hun — sirf is phone par. " +
                "\"Yaad rakhna ke …\" bolo to yaad rakh lungi; kuch bhi chupke se nahi.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 4.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Remember things about me",
                    subtitle = if (memoryOn) {
                        "On — facts are captured and used in chats"
                    } else {
                        "Off — nothing is captured or used"
                    },
                    icon = Icons.Default.Memory,
                    switch = memoryOn,
                    onCheckedChange = { checked ->
                        FactMemoryStore.setEnabled(context, checked)
                        memoryOn = checked
                    }
                )
            }
        }

        if (facts.isEmpty()) {
            Text(
                text = "Abhi kuch yaad nahi — \"yaad rakhna ke mera naam … hai\" " +
                    "jaisa kuch bolo, ya \"mujhe chai pasand hai\" kaho to main poochungi " +
                    "ke yaad rakhun ya nahi.",
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            facts.forEach { fact ->
                NovaGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    elevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fact.category.replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent
                            )
                            Text(
                                text = fact.text,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                editingFact = fact
                                editText = fact.text
                            }
                        ) {
                            Text(
                                text = "Edit",
                                fontSize = 13.sp,
                                color = colors.accent
                            )
                        }
                        IconButton(
                            onClick = {
                                FactMemoryStore.deleteFact(context, fact.id)
                                refresh()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete this fact",
                                tint = colors.error
                            )
                        }
                    }
                }
            }

            NovaButton(
                text = "Clear all memory",
                onClick = { showClearAll = true },
                type = NovaButtonType.SECONDARY,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }

    // Edit one fact
    editingFact?.let { fact ->
        AlertDialog(
            onDismissRequest = { editingFact = null },
            title = { Text(text = "Edit memory", color = colors.textPrimary) },
            text = {
                NovaTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    placeholder = "Fact"
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Save",
                    onClick = {
                        if (editText.isNotBlank()) {
                            FactMemoryStore.saveFact(context, fact.copy(text = editText.trim()))
                            refresh()
                        }
                        editingFact = null
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { editingFact = null },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }

    // Clear-all confirmation
    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text(text = "Clear all memory?", color = colors.textPrimary) },
            text = {
                Text(
                    text = "Sab kuch bhool jaungi jo tumhare baare mein yaad rakha hai. " +
                        "Ye wapas nahi aayega.",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Clear all",
                    onClick = {
                        FactMemoryStore.clearAll(context)
                        refresh()
                        showClearAll = false
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showClearAll = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}
