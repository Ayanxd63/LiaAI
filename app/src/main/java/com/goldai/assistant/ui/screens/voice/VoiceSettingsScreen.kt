package com.goldai.assistant.ui.screens.voice

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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.goldai.assistant.data.VoicePlaybackSettings
import com.goldai.assistant.ui.components.NovaGlassCard
import com.goldai.assistant.ui.components.NovaSectionHeader
import com.goldai.assistant.ui.components.NovaSettingsRow
import com.goldai.assistant.ui.theme.LocalNovaColors
import com.goldai.assistant.ui.theme.nightSky
import kotlin.math.roundToInt

/**
 * "Voice & language" settings screen (v10, mirrors official LIA v1.12):
 * speech speed, voice volume, and "Listen when the app opens".
 *
 * Every change persists immediately via [VoicePlaybackSettings] — there is
 * no Save button. A live voice session picks the new volume/speed up on
 * the next audio chunk (LiveAudioIO re-reads these settings at playback
 * time), so the user hears the change without restarting the session.
 *
 * Wiring (NOT done here — SettingsScreen.kt was not edited in v10):
 * add a "Voice & language" NovaSettingsRow in SettingsScreen that navigates
 * to route "voice_settings", and register
 * `composable("voice_settings") { VoiceSettingsScreen(navController) }`
 * in NovaApp.kt. See the v10 integration notes in the commit message.
 */
@Composable
fun VoiceSettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    // Load the stored values into the StateFlow once, then observe them.
    LaunchedEffect(Unit) {
        VoicePlaybackSettings.refresh(context)
    }
    val settings by VoicePlaybackSettings.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp)
    ) {
        // Top Bar
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
                text = "Voice & language",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        NovaSectionHeader(title = "Voice playback")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                // Speech speed: 0.5x–1.5x in 0.05 steps, default 0.85x.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speech speed",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = VoicePlaybackSettings.formatSpeed(settings.speechSpeed),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent
                    )
                }
                Slider(
                    value = settings.speechSpeed,
                    onValueChange = { VoicePlaybackSettings.setSpeechSpeed(context, it) },
                    valueRange = VoicePlaybackSettings.MIN_SPEECH_SPEED..
                        VoicePlaybackSettings.MAX_SPEECH_SPEED,
                    // 20 intervals of 0.05 across 0.5x–1.5x -> 19 steps.
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.surfaceRaised
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0.50x",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "1.50x",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Voice volume: 0–100% in 1% steps, default 65%.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voice volume",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = VoicePlaybackSettings.formatVolume(settings.voiceVolume),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent
                    )
                }
                Slider(
                    value = settings.voiceVolume.toFloat(),
                    onValueChange = {
                        VoicePlaybackSettings.setVoiceVolume(context, it.roundToInt())
                    },
                    valueRange = VoicePlaybackSettings.MIN_VOICE_VOLUME.toFloat()..
                        VoicePlaybackSettings.MAX_VOICE_VOLUME.toFloat(),
                    steps = 99,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.surfaceRaised
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0%",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "100%",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        NovaSectionHeader(title = "Listening")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Listen when the app opens",
                    subtitle = "Start listening as soon as the app opens.",
                    switch = settings.listenOnOpen,
                    onCheckedChange = {
                        VoicePlaybackSettings.setListenOnOpen(context, it)
                    }
                )
            }
        }
    }
}
