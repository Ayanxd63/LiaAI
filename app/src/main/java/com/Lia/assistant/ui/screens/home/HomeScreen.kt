package com.Lia.assistant.ui.screens.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.Lia.assistant.data.CharacterPreferences
import com.Lia.assistant.data.ChatPrefill
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.Personality
import com.Lia.assistant.ui.character.CharacterView
import com.Lia.assistant.ui.character.LiaCharacter
import com.Lia.assistant.data.VoiceOutputEngine
import com.Lia.assistant.data.VoiceOutputPreferences
import com.Lia.assistant.ui.components.AccessKeyBanner
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState
import com.Lia.assistant.voice.SpeechLevelTracker
import com.Lia.assistant.voice.VoiceSessionManager
import com.Lia.assistant.voice.WeatherClient
import com.Lia.assistant.voice.WeatherNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * V16 (P0-1) — Home redesign: "someone is home".
 *
 * Time-of-day greeting + name and date, the headphones-girl art with a
 * pulsing orb badge (the badge colour follows the live voice state —
 * listening green, thinking amber, speaking rose), 3 quick cards
 * (Talk / Type / Image), 3 info cards (Weather / Memory / Voice),
 * and an ask bar that sends straight to Chat. The v10 Create Image +
 * Write actions and the "TRY SAYING" row stay where they were.
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
    val scope = rememberCoroutineScope()

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

    val calendar = Calendar.getInstance()
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val greetingPrefix = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Welcome back"
    }
    val dateLine = SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date())

    // V16: info cards — memory facts, read on IO.
    var factCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            factCount = FactMemoryStore.loadFacts(context).size
        }
    }

    // V16 (P1-6): weather — cached result first, fresh fetch on tap
    // (location permission is asked only from the card, never on open).
    var weather by remember { mutableStateOf<WeatherNow?>(WeatherClient.loadCached(context)) }
    var weatherNote by remember { mutableStateOf<String?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshWeather(context, scope) { result ->
            result.onSuccess { weather = it; weatherNote = null }
                .onFailure { weatherNote = "Mausam nahi mila — net check karo" }
        } else {
            weatherNote = "Location allow karo to mausam dikhaun"
        }
    }

    fun onWeatherCardTap() {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            weatherNote = "Location allow karo to mausam dikhaun"
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        refreshWeather(context, scope) { result ->
            result.onSuccess { weather = it; weatherNote = null }
                .onFailure { weatherNote = "Mausam nahi mila — net check karo" }
        }
    }

    // V16: ask bar text.
    var askText by remember { mutableStateOf("") }

    // V16: the pulsing orb badge — colour follows the live voice state.
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

    val trySayingQueries = listOf(
        "\"Lia, ek cafe ki website banao\"",
        "\"Call Mummy\"",
        "\"Open YouTube and search music\"",
        "\"What's the weather like today?\""
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceGlass),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "Logo",
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = appState.assistantName,
                    fontFamily = SerifFontFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceGlass)
                    .clickable { navController.navigate("profile") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Direct Access Key Banner (if not active in direct flavor)
        AccessKeyBanner(
            onActivateClick = { navController.navigate("profile") },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Time-based greeting in Serif font + date line.
        Text(
            text = "$greetingPrefix, ${appState.userName}",
            fontFamily = SerifFontFamily,
            fontSize = 28.sp,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = dateLine,
            fontSize = 14.sp,
            color = colors.textSecondary
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Hero: the home character (2D illustration with lip-sync) when
        // enabled, otherwise the 3D orb exactly as before. The orb code
        // is kept as the fallback — it is not removed. V16: the pulsing
        // orb badge sits at the hero's corner and shows the live voice
        // state at a glance.
        Box(
            modifier = Modifier
                .size(orbSize)
                .clickable { navController.navigate("voice") },
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

        // V16: 3 quick cards — Talk / Type / Image.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeQuickCard(
                title = "Talk",
                icon = Icons.Default.Mic,
                onClick = { navController.navigate("voice") },
                modifier = Modifier.weight(1f)
            )
            HomeQuickCard(
                title = "Type",
                icon = Icons.Default.Keyboard,
                onClick = { navController.navigate("chat") },
                modifier = Modifier.weight(1f)
            )
            HomeQuickCard(
                title = "Image",
                icon = Icons.Default.Image,
                onClick = { navController.navigate("create_image") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // V16: ask bar — type here, Send goes straight to Chat.
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
                        ChatPrefill.set(askText)
                        askText = ""
                        navController.navigate("chat")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // V16: 3 info cards — Weather / Memory / Voice.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NovaInfoCard(
                title = "Weather",
                body = weather?.let {
                    "${it.tempC.toInt()}°C — ${it.description}" +
                        (it.humidity?.let { h -> " · $h% humidity" } ?: "")
                } ?: (weatherNote ?: "Tap karo — mausam dekho"),
                icon = Icons.Default.Cloud,
                onClick = { onWeatherCardTap() },
                modifier = Modifier.weight(1f)
            )
            NovaInfoCard(
                title = "Memory",
                body = if (factCount > 0) {
                    "$factCount ${if (factCount == 1) "fact" else "facts"} yaad hain"
                } else {
                    "Abhi kuch yaad nahi — \"yaad rakhna ke…\" bolo"
                },
                icon = Icons.Default.Memory,
                onClick = { navController.navigate("memory") },
                modifier = Modifier.weight(1f)
            )
            NovaInfoCard(
                title = "Voice",
                body = when (VoiceOutputPreferences.getEngine(context)) {
                    VoiceOutputEngine.ONLINE -> "Internet voice (Kokoro)"
                    VoiceOutputEngine.SYSTEM -> "Device voice"
                },
                icon = Icons.Default.RecordVoiceOver,
                onClick = { navController.navigate("settings") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        // v10: Create Image + Write quick actions (official LIA home actions)
        CreateWriteQuickActions(
            navController = navController,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(36.dp))

        // "Try saying" 3D tilted cards row
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "TRY SAYING",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = colors.accent,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                trySayingQueries.forEachIndexed { idx, query ->
                    NovaGlassCard(
                        modifier = Modifier
                            .width(220.dp)
                            .graphicsLayer {
                                rotationY = if (idx % 2 == 0) -4f else 4f
                                cameraDistance = 12f * density
                            }
                            .clickable {
                                navController.navigate("chat")
                            }
                    ) {
                        Text(
                            text = query,
                            fontSize = 14.sp,
                            color = colors.textPrimary,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeQuickCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceGlass)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = colors.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = colors.textPrimary
            )
        }
    }
}

/** Fetch fresh weather for the last-known location. Never throws. */
private fun refreshWeather(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (Result<WeatherNow>) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val location = WeatherClient.lastKnownLocation(context)
        if (location == null) {
            withContext(Dispatchers.Main) {
                onResult(Result.failure(IllegalStateException("No location")))
            }
            return@launch
        }
        val result = WeatherClient.fetch(context, location.first, location.second)
        withContext(Dispatchers.Main) { onResult(result) }
    }
}
