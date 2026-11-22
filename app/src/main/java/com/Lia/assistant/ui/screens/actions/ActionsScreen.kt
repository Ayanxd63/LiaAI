package com.Lia.assistant.ui.screens.actions

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
import androidx.compose.material3.AlertDialog
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
import com.Lia.assistant.action.DeviceActions
import com.Lia.assistant.action.DeviceCommand
import com.Lia.assistant.action.SettingsScreen
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V16 (P1-3) — the Actions screen: an honest catalogue of everything
 * Lia can actually do on this phone, taken from the real
 * [DeviceCommand] list — nothing invented, nothing faked.
 *
 * Every row is tappable: actions that need no input run at once and
 * show Lia's real reply; actions that need a name, query or number
 * ask for it first in a small dialog, then run. When something needs a
 * permission, the result says so plainly instead of pretending it
 * worked.
 */
private data class ActionDef(
    val name: String,
    val example: String,
    val needs: String? = null,
    val inputHint: String? = null,
    val build: (String) -> DeviceCommand
)

@Composable
fun ActionsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resultText by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ActionDef?>(null) }
    var pendingInput by remember { mutableStateOf("") }

    fun runAction(action: ActionDef, input: String) {
        scope.launch(Dispatchers.IO) {
            val reply = try {
                DeviceActions.execute(context, action.build(input))
            } catch (_: Exception) {
                "Ye kaam nahi ho saka — dobara try karo."
            }
            withContext(Dispatchers.Main) { resultText = reply }
        }
    }

    val searchAndOpen = listOf(
        ActionDef(
            "YouTube search", "\"YouTube par music search karo\"",
            inputHint = "Kya search karun? (e.g. music)"
        ) { DeviceCommand.YouTubeSearch(it.ifBlank { "music" }) },
        ActionDef(
            "Open app", "\"YouTube kholo\"",
            inputHint = "Konsi app? (e.g. YouTube)"
        ) { DeviceCommand.OpenApp(it.ifBlank { "YouTube" }) }
    )
    val callsAndMessages = listOf(
        ActionDef(
            "Dial a contact", "\"Ammi ko dial karo\"",
            needs = "Contacts permission (naam ke liye)",
            inputHint = "Naam ya number"
        ) { DeviceCommand.Dial(it) },
        ActionDef(
            "Direct call", "\"Ammi ko call karo\"",
            needs = "Call permission — warna dialer khulega",
            inputHint = "Naam ya number"
        ) { DeviceCommand.DirectCall(it) },
        ActionDef(
            "WhatsApp chat", "\"WhatsApp par Ammi ko message karo\"",
            needs = "Number ho to direct chat, naam ho to WhatsApp khulega",
            inputHint = "Number ya naam"
        ) { DeviceCommand.WhatsAppMessage(it) }
    )
    val phoneControls = listOf(
        ActionDef("Flashlight on", "\"Flashlight on karo\"", needs = "Camera permission") {
            DeviceCommand.Flashlight(true)
        },
        ActionDef("Flashlight off", "\"Flashlight band karo\"") {
            DeviceCommand.Flashlight(false)
        },
        ActionDef("Volume up", "\"Volume barhao\"") { DeviceCommand.VolumeUp },
        ActionDef("Volume down", "\"Volume kam karo\"") { DeviceCommand.VolumeDown },
        ActionDef(
            "Brightness", "\"Brightness 70% karo\"",
            needs = "Modify system settings",
            inputHint = "Kitni %? (0–100)"
        ) {
            val level = it.trim().toIntOrNull()
            DeviceCommand.Brightness(level = level)
        },
        ActionDef("Do Not Disturb on", "\"DND on karo\"", needs = "DND access") {
            DeviceCommand.DoNotDisturb(true)
        },
        ActionDef("Do Not Disturb off", "\"DND band karo\"", needs = "DND access") {
            DeviceCommand.DoNotDisturb(false)
        },
        ActionDef("Internet panel", "\"WiFi on karo\"", needs = "Android khud on/off nahi karne deta — panel khulta hai") {
            DeviceCommand.InternetPanel
        },
        ActionDef("Bluetooth on", "\"Bluetooth on karo\"", needs = "Bluetooth permission — Allow dabana hoga") {
            DeviceCommand.BluetoothToggle(true)
        },
        ActionDef("Set alarm", "\"Subah 7:30 ka alarm lagao\"", inputHint = "Time (e.g. 7:30)") {
            val parts = it.trim().split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull()
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (hour != null) DeviceCommand.SetAlarm(hour, minute, true)
            else DeviceCommand.SetAlarm(0, 0, false)
        }
    )
    val notificationsAndUsage = listOf(
        ActionDef(
            "Read notifications", "\"Mere notifications parho\"",
            needs = "Notification access"
        ) { DeviceCommand.ReadNotifications },
        ActionDef(
            "Reply to notification", "\"WhatsApp ko reply karo\"",
            needs = "Notification access",
            inputHint = "Reply message"
        ) { DeviceCommand.ReplyNotification(null, it) },
        ActionDef(
            "Current app", "\"Konsi app khuli hai?\"",
            needs = "Usage access"
        ) { DeviceCommand.CurrentApp },
        ActionDef(
            "Top apps today", "\"Aaj konsi apps zyada chali?\"",
            needs = "Usage access"
        ) { DeviceCommand.TopApps }
    )
    val settingsScreens = listOf(
        "WiFi settings" to SettingsScreen.WIFI,
        "Bluetooth settings" to SettingsScreen.BLUETOOTH,
        "Display settings" to SettingsScreen.DISPLAY,
        "Sound settings" to SettingsScreen.SOUND,
        "Location settings" to SettingsScreen.LOCATION,
        "Apps settings" to SettingsScreen.APPS,
        "Storage settings" to SettingsScreen.STORAGE,
        "Battery saver" to SettingsScreen.BATTERY_SAVER,
        "Date & time settings" to SettingsScreen.DATE_TIME
    )

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
                text = "Actions",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Jo kuch Lia phone par kar sakti hai — sab kuch yahan hai. " +
                "Tap karo to chalega; jo permission mangta hai, wo pehle batayega.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        resultText?.let { result ->
            NovaInfoCard(
                title = "Lia ka jawab",
                body = result,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        NovaSectionHeader(title = "Search & open")
        ActionGroup(rows = searchAndOpen, onTap = { action ->
            if (action.inputHint != null) {
                pendingInput = ""
                pendingAction = action
            } else {
                runAction(action, "")
            }
        })

        NovaSectionHeader(title = "Calls & messages")
        ActionGroup(rows = callsAndMessages, onTap = { action ->
            pendingInput = ""
            pendingAction = action
        })

        NovaSectionHeader(title = "Phone controls")
        ActionGroup(rows = phoneControls, onTap = { action ->
            if (action.inputHint != null) {
                pendingInput = ""
                pendingAction = action
            } else {
                runAction(action, "")
            }
        })

        NovaSectionHeader(title = "Notifications & usage")
        ActionGroup(rows = notificationsAndUsage, onTap = { action ->
            if (action.inputHint != null) {
                pendingInput = ""
                pendingAction = action
            } else {
                runAction(action, "")
            }
        })

        NovaSectionHeader(title = "Settings screens")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((name, screen) in settingsScreens) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Settings screen kholega",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        NovaButton(
                            text = "Open",
                            onClick = {
                                runAction(
                                    ActionDef(name, "") { DeviceCommand.OpenSettings(screen) },
                                    ""
                                )
                            },
                            type = NovaButtonType.SECONDARY
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Input dialog for actions that need a name, query or number.
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(text = action.name, color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        text = action.example,
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaTextField(
                        value = pendingInput,
                        onValueChange = { pendingInput = it },
                        placeholder = action.inputHint ?: ""
                    )
                }
            },
            confirmButton = {
                NovaButton(
                    text = "Run",
                    onClick = {
                        val input = pendingInput
                        pendingAction = null
                        runAction(action, input)
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { pendingAction = null },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}

@Composable
private fun ActionGroup(
    rows: List<ActionDef>,
    onTap: (ActionDef) -> Unit
) {
    val colors = LocalNovaColors.current
    NovaGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (action in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = action.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = colors.textPrimary
                        )
                        Text(
                            text = action.example,
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                        action.needs?.let { need ->
                            Text(
                                text = need,
                                fontSize = 12.sp,
                                color = colors.warning
                            )
                        }
                    }
                    NovaButton(
                        text = "Run",
                        onClick = { onTap(action) },
                        type = NovaButtonType.SECONDARY
                    )
                }
            }
        }
    }
}
