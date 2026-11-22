package com.Lia.assistant.ui.screens.skills

import androidx.compose.foundation.layout.Arrangement
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
import androidx.navigation.NavController
import com.Lia.assistant.data.LiaReminder
import com.Lia.assistant.data.LiaSkills
import com.Lia.assistant.data.ReminderStore
import com.Lia.assistant.data.SkillsStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.ChatReplyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V16 (P1-2) — the skills store: 6 real local skills.
 *
 * Every skill runs on Lia's free engines (Pollinations / offline /
 * Gemini-when-keyed) with the user's own input — no fake buttons, no
 * mock results. Install pins a skill; an installed skill can Run and
 * shows the real reply in a result card. The header shows
 * "N/6 installed". The Reminder skill's result can be saved as a real
 * reminder (shown below the skills).
 */
@Composable
fun SkillsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(SkillsStore.installedIds(context)) }
    var runningSkillId by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var inputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reminders by remember {
        mutableStateOf(ReminderStore.loadReminders(context))
    }

    fun refreshInstalled() {
        installed = SkillsStore.installedIds(context)
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
                text = "Skills",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "${installed.size} / ${LiaSkills.ALL.size} installed — install karo, " +
                "phir apna input likh kar Run dabao. Har skill Lia ke free " +
                "engine par chalti hai, koi key nahi chahiye.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        for (skill in LiaSkills.ALL) {
            val isInstalled = skill.id in installed
            val isRunning = runningSkillId == skill.id
            val input = inputs[skill.id].orEmpty()
            val result = results[skill.id]
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = skill.tagline,
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                        NovaButton(
                            text = if (isInstalled) "Installed ✓" else "Install",
                            onClick = {
                                SkillsStore.setInstalled(context, skill.id, !isInstalled)
                                refreshInstalled()
                            },
                            type = if (isInstalled) NovaButtonType.SECONDARY
                            else NovaButtonType.PRIMARY
                        )
                    }
                    if (isInstalled) {
                        NovaTextField(
                            value = input,
                            onValueChange = {
                                inputs = inputs + (skill.id to it)
                            },
                            placeholder = skill.inputHint
                        )
                        NovaButton(
                            text = if (isRunning) "Chal rahi hai…" else "Run",
                            onClick = {
                                if (input.isBlank() || isRunning) return@NovaButton
                                runningSkillId = skill.id
                                results = results - skill.id
                                scope.launch(Dispatchers.IO) {
                                    val reply = SkillsStore.runSkill(context, skill, input)
                                    withContext(Dispatchers.Main) {
                                        runningSkillId = null
                                        results = results + (skill.id to when (reply) {
                                            is ChatReplyResult.Success -> reply.text
                                            is ChatReplyResult.Error -> reply.message
                                        })
                                    }
                                }
                            }
                        )
                        result?.let { text ->
                            NovaInfoCard(title = "Result", body = text)
                            if (skill.id == "reminder") {
                                NovaButton(
                                    text = "Save as reminder",
                                    onClick = {
                                        ReminderStore.saveReminder(
                                            context,
                                            LiaReminder(text = text)
                                        )
                                        reminders = ReminderStore.loadReminders(context)
                                    },
                                    type = NovaButtonType.SECONDARY
                                )
                            }
                        }
                    }
                }
            }
        }

        if (reminders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Saved reminders",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            for (reminder in reminders) {
                NovaInfoCard(
                    title = if (reminder.done) "✓ ${reminder.text}" else reminder.text,
                    body = "Tap to mark done, long list mein delete karo — " +
                        "reminders backup mein bhi jate hain.",
                    onClick = {
                        ReminderStore.toggleDone(context, reminder.id)
                        reminders = ReminderStore.loadReminders(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
