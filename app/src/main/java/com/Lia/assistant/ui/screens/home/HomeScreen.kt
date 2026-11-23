package com.Lia.assistant.ui.screens.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.CharacterPreferences
import com.Lia.assistant.data.ChatPrefill
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.Personality
import com.Lia.assistant.ui.character.CharacterView
import com.Lia.assistant.ui.character.LiaCharacter
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState
import com.Lia.assistant.voice.SpeechLevelTracker
import com.Lia.assistant.voice.VoiceSessionManager
import com.Lia.assistant.voice.WelcomeSpeaker

/**
 * V19 — Home, simplified ("Maya se seekho": orb + mic, bas).
 *
 * The user tested V18 and said the V16 Home had become difficult to
 * use: everything was piled onto the start screen, when all he ever
 * asked for was the first-run permissions wizard. So Home now shows
 * ONLY three things, and a first-time user understands it in 2 seconds:
 *
 *   (a) Lia's character — the headphones girl (the default character),
 *       with the pulsing orb badge showing the live voice state;
 *   (b) tap the character to talk (voice overlay);
 *   (c) an ask bar that sends straight to Chat.
 *
 * Everything that used to live here moved, it was NOT deleted:
 *   - The three info cards (forecast / remembered facts / voice) →
 *     Settings (the forecast is a new row in "Lia's extras"; the other
 *     two were already rows there).
 *   - Create Image / Write / Prompt Generator → Settings, "Lia's extras"
 *     (Prompt Generator is ALSO still a chip in Chat).
 *   - Talk / Type / Image quick cards → gone: Talk is the hero tap,
 *     Type is the ask bar, Image is in Settings.
 *   - The example-suggestion cards row → gone: the ask bar is the entry.
 *   - Top header (logo / name / profile) → gone: Profile is a row in
 *     Settings. The access-key banner is gone too — activation stays
 *     reachable via Settings → Profile.
 *   - Greeting + date line → gone.
 *
 * Unchanged: bottom navigation, the first-run permissions wizard, the
 * GF rose theme, the headphones-girl default, and all V18 behaviour
 * (online Kokoro voice, system TTS fallback, Vosk "Hey Lia", Pollinations
 * chat/image). No new permissions, no new dependencies, no manifest
 * changes. The Home-only quick-actions file was deleted — it was
 * Home-only UI and is now unused.
 */
@Composable
fun HomeScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()

    // Home character: which 2D illustration to show, and whether the
    // character replaces the orb at all. Lip-sync level comes from the
    // Gemini-voice-only SpeechLevelTracker, so the mouth moves while
    // Lia speaks in voice mode. (Text replies read aloud with the
    // system TTS voice do NOT feed this flow — different voice, not
    // Gemini — so the character stays still for those.)
    val characterSettings by CharacterPreferences.observe(context).collectAsState()
    val speechLevel by SpeechLevelTracker.speechLevel.collectAsState()

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val orbSize = screenWidth * 0.55f

    // The pulsing orb badge — colour follows the live voice state.
    val badgeColor = when (orbState) {
        NovaOrbState.LISTENING, NovaOrbState.CONNECTING -> colors.lagoon
        NovaOrbState.THINKING -> colors.warning
        NovaOrbState.SPEAKING -> colors.accent
        NovaOrbState.ERROR -> colors.error
        NovaOrbState.IDLE -> colors.textTertiary
    }
    val pulseTransition = rememberInfiniteTransition(label = "orbBadge")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbBadgeScale"
    )

    // Ask bar text — type here, Ask goes straight to Chat.
    var askText by remember { mutableStateOf("") }

    // V19: spoken first-run welcome — Lia greets out loud once, on
    // first launch after onboarding (Pocket TTS first when downloaded,
    // else Online → System; no key needed). The card shows the same
    // greeting as text with a visible Skip; speaking never blocks the
    // UI, and tapping the hero / Ask dismisses it first so it never
    // overlaps a voice session. It never auto-plays again — the flag
    // is set on speak-done OR skip.
    var showWelcome by remember { mutableStateOf(!WelcomeSpeaker.hasSpoken(context)) }

    fun dismissWelcome() {
        WelcomeSpeaker.stop()
        WelcomeSpeaker.markSpoken(context)
        showWelcome = false
    }

    LaunchedEffect(showWelcome) {
        if (!showWelcome) return@LaunchedEffect
        // Never speak over a live voice session.
        if (VoiceSessionManager.isActive.value) {
            dismissWelcome()
            return@LaunchedEffect
        }
        WelcomeSpeaker.speak(
            context,
            WelcomeSpeaker.welcomeText(appState.personality, appState.assistantName)
        ) {
            WelcomeSpeaker.markSpoken(context)
            showWelcome = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .padding(bottom = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // First-run welcome card — text + Skip, while Lia speaks it.
        if (showWelcome) {
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = WelcomeSpeaker.welcomeDisplayText(
                            appState.personality,
                            appState.assistantName
                        ),
                        fontFamily = SerifFontFamily,
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bol rahi hai…",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                        NovaButton(
                            text = "Skip",
                            onClick = { dismissWelcome() },
                            type = NovaButtonType.TEXT
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Hero: the home character (2D illustration with lip-sync) when
        // enabled, otherwise the 3D orb exactly as before. The orb code
        // is kept as the fallback — it is not removed. The pulsing orb
        // badge sits at the hero's corner and shows the live voice
        // state at a glance. Tap the hero to talk.
        Box(
            modifier = Modifier
                .size(orbSize)
                .clickable {
                    dismissWelcome()
                    navController.navigate("voice")
                },
            contentAlignment = Alignment.Center
        ) {
            if (characterSettings.homeCharacterEnabled) {
                CharacterView(
                    character = LiaCharacter.fromId(characterSettings.characterId),
                    speechLevel = speechLevel,
                    size = orbSize,
                    animate = !appState.reducedMotion,
                    contentDescription = "Lia"
                )
            } else {
                LiaOrb3D(
                    state = orbState,
                    size = orbSize,
                    amplitude = amplitude,
                    style = NovaOrbStyle.fromString(appState.orbStyle),
                    assistantName = appState.assistantName
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .graphicsLayer {
                        val scale = if (orbState == NovaOrbState.IDLE) 1f else pulseScale
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(badgeColor)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Tap me to talk",
            fontFamily = SerifFontFamily,
            fontSize = 15.sp,
            color = colors.textSecondary
        )

        // V13: a warm line under the hero when the Girlfriend is active —
        // the app should feel like hers from the first screen.
        if (appState.personality == Personality.GF_MODE) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tumhari apni Lia — hamesha yahin hun 💖",
                fontFamily = SerifFontFamily,
                fontSize = 14.sp,
                color = colors.lotus
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Ask bar — type here, Ask goes straight to Chat.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NovaTextField(
                value = askText,
                onValueChange = { askText = it },
                placeholder = "Lia se poochho…",
                modifier = Modifier.weight(1f)
            )
            NovaButton(
                text = "Ask",
                onClick = {
                    if (askText.isNotBlank()) {
                        dismissWelcome()
                        ChatPrefill.set(askText)
                        askText = ""
                        navController.navigate("chat")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}
