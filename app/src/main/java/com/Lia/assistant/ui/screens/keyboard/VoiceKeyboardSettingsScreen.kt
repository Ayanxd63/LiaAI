package com.Lia.assistant.ui.screens.keyboard

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.Lia.assistant.input.VoiceKeyboardStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

/**
 * Voice Keyboard settings — how to turn on "Lia Voice Keyboard" and use it
 * to speak-to-type in any app (Hindi first, English fallback).
 *
 * Navigation wiring is NOT done here — the parent adds the route
 * "voice_keyboard" to NovaApp and a "Voice Keyboard" row in SettingsScreen.
 *
 * Honest limits, shown on this screen:
 * - Android does not let an app enable or select its own keyboard. The
 *   toggle below only records the user's own preference (in
 *   [VoiceKeyboardStore]'s own prefs file); the user must still enable the
 *   keyboard in system Settings and then pick it with the switcher.
 * - The keyboard needs the microphone permission and the device's speech
 *   recognition (Google). If the device has no Hindi recognition, the
 *   keyboard falls back to English, then to the device language, and says
 *   so on the keyboard itself.
 */
@Composable
fun VoiceKeyboardSettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var useVoiceKeyboard by remember { mutableStateOf(VoiceKeyboardStore.isEnabled(context)) }
    var enabledInSystem by remember { mutableStateOf(false) }
    var isCurrentKeyboard by remember { mutableStateOf(false) }
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    fun refreshStatus() {
        enabledInSystem = VoiceKeyboardStore.isEnabledInSystem(context)
        isCurrentKeyboard = VoiceKeyboardStore.isCurrentInputMethod(context)
        hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Status is live system state, so re-read it every time this screen appears.
    LaunchedEffect(Unit) { refreshStatus() }

    val setupSteps = listOf(
        "Turn on the toggle above — this records that you want to use the voice keyboard.",
        "Tap \"Open keyboard settings\" below. In system Settings go to System → Languages & input → On-screen keyboard.",
        "Turn on \"Lia Voice Keyboard\" in that list.",
        "Open any app with a text field, then tap \"Switch keyboard\" below (or the keyboard-switch icon) and choose \"Lia Voice Keyboard\".",
        "Tap the mic on the keyboard, speak in Hindi or English, and the text appears in the field."
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp)
    ) {
        // Top bar — same pattern as SettingsScreen / PermissionsScreen.
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
                text = "Voice Keyboard",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        NovaSectionHeader(title = "Speak to type in any app")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                Text(
                    text = "Switch to the Lia Voice Keyboard in any app, tap the mic, and speak in Hindi or English. Your words are typed into the text field. Final results are inserted automatically; the Insert button inserts the preview text.",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                NovaSettingsRow(
                    title = "Use voice keyboard",
                    subtitle = "I want to type by speaking with Lia",
                    icon = Icons.Default.Keyboard,
                    switch = useVoiceKeyboard,
                    onCheckedChange = {
                        useVoiceKeyboard = it
                        VoiceKeyboardStore.setEnabled(context, it)
                    }
                )
            }
        }

        NovaSectionHeader(title = "Status")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                StatusLine(
                    label = "Enabled in system settings",
                    value = if (enabledInSystem) "Yes" else "Not yet"
                )
                StatusLine(
                    label = "Currently selected keyboard",
                    value = if (isCurrentKeyboard) "Lia Voice Keyboard" else "Another keyboard"
                )
                StatusLine(
                    label = "Microphone permission",
                    value = if (hasMic) "Granted" else "Not granted — allow it in Permissions"
                )
                Spacer(modifier = Modifier.height(8.dp))
                NovaButton(
                    text = "Check again",
                    onClick = { refreshStatus() },
                    type = NovaButtonType.SECONDARY
                )
            }
        }

        NovaSectionHeader(title = "How to set it up")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                setupSteps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${index + 1}.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.accent,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = step,
                            fontSize = 14.sp,
                            color = colors.textPrimary,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        NovaSectionHeader(title = "Actions")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                NovaButton(
                    text = "Open keyboard settings",
                    onClick = { VoiceKeyboardStore.openInputMethodSettings(context) },
                    icon = Icons.Default.Keyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(
                    text = "Switch keyboard",
                    onClick = { VoiceKeyboardStore.showInputMethodPicker(context) },
                    type = NovaButtonType.SECONDARY,
                    icon = Icons.Default.Mic,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Note: Lia cannot turn the keyboard on by itself — Android only lets you enable it in system Settings. If your device has no Hindi speech recognition, the keyboard uses English instead and tells you on the keyboard.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    val colors = LocalNovaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent
        )
    }
}
