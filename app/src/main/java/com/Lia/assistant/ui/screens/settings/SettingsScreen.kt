package com.Lia.assistant.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.Lia.assistant.data.AlwaysListeningPreferences
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.ChatDisplayPreferences
import com.Lia.assistant.data.ChatEngine
import com.Lia.assistant.data.CrashReportStore
import com.Lia.assistant.data.ElevenLabsStore
import com.Lia.assistant.data.EnginePreferences
import com.Lia.assistant.data.EnginePreset
import com.Lia.assistant.data.EnginePresetPreferences
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.SkillsStore
import com.Lia.assistant.notification.WhatsAppArrivalAlerts
import com.Lia.assistant.ui.components.AccessibilitySettingsRow
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaChipRow
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.AlwaysListeningWatchdogWorker
import com.Lia.assistant.data.VoiceOutputEngine
import com.Lia.assistant.data.VoiceOutputPreferences
import com.Lia.assistant.voice.OnlineTtsEngine
import com.Lia.assistant.voice.VoiceForegroundService
import com.Lia.assistant.voice.WakeWordModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(ApiKeyStore.getKey(context)) }

    // V12: which engine answers. Local state mirrors the stored value so
    // the row subtitle updates the moment the user taps it.
    var engine by remember { mutableStateOf(EnginePreferences.getEngine(context)) }

    // V15: engine dialog. The fact count is refreshed on IO every time
    // this screen is entered, so returning from the memory screen shows
    // the new state.
    var showEngineDialog by remember { mutableStateOf(false) }
    var memoryFactCount by remember { mutableStateOf(0) }
    // V18: Lia's voice choice (ONLINE / SYSTEM) + online quota used
    // this hour, refreshed on entry.
    var voiceEngine by remember { mutableStateOf(VoiceOutputPreferences.getEngine(context)) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var onlineQuotaUsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            memoryFactCount = FactMemoryStore.loadFacts(context).size
            onlineQuotaUsed = OnlineTtsEngine.quotaUsed(context)
        }
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(appState.assistantName) }

    // V16: engine preset (Fast/Warm/Smart), human-paced typing,
    // WhatsApp arrival alerts, accent. Local state mirrors the stored
    // value so the row subtitle updates the moment the user taps it.
    var preset by remember { mutableStateOf(EnginePresetPreferences.getPreset(context)) }
    var humanPaced by remember {
        mutableStateOf(ChatDisplayPreferences.isHumanPacedTyping(context))
    }
    var whatsappAlertsOn by remember {
        mutableStateOf(WhatsAppArrivalAlerts.isEnabled(context))
    }

    // V14: always-listening ("Hey Lia") state. The toggle is OFF by
    // default; turning it on downloads the small on-device voice model
    // once (~40 MB) and starts the background service. Turning it off
    // stops everything. The privacy line under the toggle is always
    // visible — it is the plain-language promise from the V14 plan.
    val scope = rememberCoroutineScope()
    var alwaysListeningOn by remember {
        mutableStateOf(AlwaysListeningPreferences.isEnabled(context))
    }
    var modelReady by remember {
        mutableStateOf(WakeWordModelManager.isModelReady(context))
    }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadBusy by remember { mutableStateOf(false) }
    var alwaysListeningError by remember { mutableStateOf<String?>(null) }

    fun setAlwaysListening(enabled: Boolean) {
        if (!enabled) {
            VoiceForegroundService.stopListening(context)
            alwaysListeningOn = false
            alwaysListeningError = null
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            alwaysListeningError = "Microphone permission chahiye — Settings > " +
                "Permissions se Microphone on karo, phir dobara try karo."
            return
        }
        if (modelReady) {
            AlwaysListeningPreferences.setEnabled(context, true)
            AlwaysListeningWatchdogWorker.enqueue(context)
            VoiceForegroundService.startAlwaysListening(context)
            alwaysListeningOn = true
            alwaysListeningError = null
            return
        }
        if (downloadBusy) return
        downloadBusy = true
        alwaysListeningError = null
        scope.launch(Dispatchers.IO) {
            val result = WakeWordModelManager.downloadModel(context) { progress ->
                scope.launch { downloadProgress = progress }
            }
            withContext(Dispatchers.Main) {
                downloadBusy = false
                downloadProgress = null
                result.onSuccess {
                    modelReady = true
                    AlwaysListeningPreferences.setEnabled(context, true)
                    AlwaysListeningWatchdogWorker.enqueue(context)
                    VoiceForegroundService.startAlwaysListening(context)
                    alwaysListeningOn = true
                }.onFailure { e ->
                    alwaysListeningError = "Voice model download nahi ho saki — net " +
                        "check kar ke dobara try karo. " +
                        (e.localizedMessage ?: "unknown error")
                }
            }
        }
    }

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
                text = "Settings",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        // Profile hero card
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { navController.navigate("profile") },
            elevation = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(colors.accentGlow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Avatar",
                        tint = colors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = appState.userName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Assistant: ${appState.assistantName} (${appState.personality.displayName})",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // Section 1: AI & Voice Intelligence
        NovaSectionHeader(title = "AI & Voice Intelligence")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                // V12: the Gemini key is OPTIONAL now. Lia works free with
                // no key at all (Pollinations). The key only matters when
                // the user picks the Gemini engine below.
                NovaSettingsRow(
                    title = "Gemini API Key (optional)",
                    subtitle = if (ApiKeyStore.hasKey(context)) "Configured locally" else "Not needed — Lia works free without it",
                    icon = Icons.Default.Key,
                    onClick = { showApiKeyDialog = true }
                )
                // V12/V18: AI engine — Pollinations is free with no key and
                // is the default. Gemini needs the key above. Tapping
                // opens a 2-way picker instead of cycling, so the choice
                // is always visible.
                NovaSettingsRow(
                    title = "AI Engine",
                    subtitle = when {
                        engine == ChatEngine.GEMINI && ApiKeyStore.hasKey(context) ->
                            "Gemini (your key) — better quality"
                        else -> "Pollinations — free, no key"
                    },
                    icon = Icons.Default.Star,
                    onClick = { showEngineDialog = true }
                )
                // V16 (P0-6): engine preset — Fast / Warm / Smart. Tap
                // to cycle; the preset drives the fallback order and the
                // reply style (see EnginePresets.kt).
                NovaSettingsRow(
                    title = "Engine preset",
                    subtitle = "${preset.displayName} — ${preset.tagline}",
                    icon = Icons.Default.Speed,
                    onClick = {
                        val next = when (preset) {
                            EnginePreset.FAST -> EnginePreset.WARM
                            EnginePreset.WARM -> EnginePreset.SMART
                            EnginePreset.SMART -> EnginePreset.FAST
                        }
                        EnginePresetPreferences.setPreset(context, next)
                        preset = next
                    }
                )
                // V18: Lia's voice — ONLINE (Kokoro, human-like, ~20/hour)
                // or SYSTEM (device TTS). The on-device Pocket TTS voice
                // was removed in the V18 diet; ONLINE stays the default.
                NovaSettingsRow(
                    title = "Lia's voice",
                    subtitle = when (voiceEngine) {
                        VoiceOutputEngine.ONLINE ->
                            "Internet voice (Kokoro) — insano jaisi awaz · " +
                                "$onlineQuotaUsed/20 is ghantay mein"
                        VoiceOutputEngine.SYSTEM ->
                            "Device voice — hamesha available, bina internet"
                    },
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = { showVoiceDialog = true }
                )
                // V16 (P1-12): human-paced typing — replies type out word
                // by word at a human pace (90ms/word) instead of streaming
                // in live. Default OFF.
                NovaSettingsRow(
                    title = "Human-paced typing",
                    subtitle = if (humanPaced) {
                        "On — replies type out like a person"
                    } else {
                        "Off — replies stream in live"
                    },
                    icon = Icons.Default.Speed,
                    switch = humanPaced,
                    onCheckedChange = { checked ->
                        ChatDisplayPreferences.setHumanPacedTyping(context, checked)
                        humanPaced = checked
                    }
                )
                // V15: long-term memory — what Lia remembers about you.
                NovaSettingsRow(
                    title = "Memory",
                    subtitle = if (memoryFactCount > 0) {
                        "$memoryFactCount ${if (memoryFactCount == 1) "fact" else "facts"} remembered — tap to view or delete"
                    } else {
                        "Nothing remembered yet — say \"yaad rakhna ke …\""
                    },
                    icon = Icons.Default.Memory,
                    onClick = { navController.navigate("memory") }
                )
                NovaSettingsRow(
                    title = "Assistant Name",
                    subtitle = appState.assistantName,
                    icon = Icons.Default.Mic,
                    onClick = {
                        nameInput = appState.assistantName
                        showNameDialog = true
                    }
                )
                NovaSettingsRow(
                    title = "Personality",
                    subtitle = appState.personality.displayName,
                    icon = Icons.Default.Mood,
                    value = appState.personality.icon,
                    onClick = { navController.navigate("personality") }
                )
                NovaSettingsRow(
                    title = "Spoken Language",
                    subtitle = appState.language.displayName,
                    icon = Icons.Default.Language,
                    onClick = {
                        // Cycle languages
                        val nextLang = when (appState.language) {
                            LanguagePreference.AUTO -> LanguagePreference.HINDI
                            LanguagePreference.HINDI -> LanguagePreference.HINGLISH
                            LanguagePreference.HINGLISH -> LanguagePreference.ENGLISH
                            LanguagePreference.ENGLISH -> LanguagePreference.AUTO
                        }
                        appState.applyLanguage(context, nextLang)
                    }
                )
                // V16 (P0-5): the voice picker — device TTS voices
                // (real, previewable, no key) and Gemini Live voice names
                // (key-only). Replaces the old cycle-through-names row:
                // the Appetize tester could never see the list.
                NovaSettingsRow(
                    title = "Voice picker",
                    subtitle = "Device voices + Gemini Live voices — ${appState.voiceName}",
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = { navController.navigate("voice_picker") }
                )
                // v10: Voice & language — speech speed, voice volume, listen on open.
                NovaSettingsRow(
                    title = "Voice & language",
                    subtitle = "Speech speed, voice volume, listen on open",
                    icon = Icons.Default.Speed,
                    onClick = { navController.navigate("voice_settings") }
                )
                // v10: ElevenLabs — natural voices with your own API key.
                NovaSettingsRow(
                    title = "ElevenLabs",
                    subtitle = if (ElevenLabsStore.hasSelectedVoice(context)) {
                        ElevenLabsStore.selectedVoiceName(context)
                    } else if (ElevenLabsStore.hasKey(context)) {
                        "Connected — no voice picked"
                    } else {
                        "Not connected"
                    },
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = { navController.navigate("elevenlabs_settings") }
                )
            }
        }

        // Section 2: Background & Always Listening (V14)
        NovaSectionHeader(title = "Background & Always Listening")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Always listening (Hey Lia)",
                    subtitle = if (alwaysListeningOn) {
                        "On — Lia wakes when you say \"Hey Lia\""
                    } else {
                        "Off — Lia only listens while you talk to her"
                    },
                    icon = Icons.Default.Mic,
                    switch = alwaysListeningOn,
                    onCheckedChange = { checked -> setAlwaysListening(checked) }
                )
                Text(
                    text = "Lia listens on your phone only for \"Hey Lia\". Nothing is " +
                        "recorded or sent until she hears it.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (downloadBusy) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = "Voice model download ho rahi hai… " +
                                if (downloadProgress != null && downloadProgress!! >= 0f) {
                                    "${(downloadProgress!! * 100).toInt()}%"
                                } else {
                                    ""
                                },
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = downloadProgress ?: 0f,
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent
                        )
                    }
                }
                if (!modelReady && !downloadBusy) {
                    Text(
                        text = "Voice model abhi download nahi hua (lagbhag 40 MB, ek dafa). " +
                            "Toggle on karte hi download hoga.",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                alwaysListeningError?.let { message ->
                    Text(
                        text = message,
                        fontSize = 13.sp,
                        color = colors.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                NovaSettingsRow(
                    title = "Background setup",
                    subtitle = "Battery, autostart and recents — keeps Lia alive",
                    icon = Icons.Default.Security,
                    onClick = { navController.navigate("permissions") }
                )
            }
        }

        // Section 2: Look & Visuals
        NovaSectionHeader(title = "Look & Visuals")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 6.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "3D Orb Style",
                    subtitle = appState.orbStyle,
                    icon = Icons.Default.ColorLens,
                    onClick = { navController.navigate("orb_style") }
                )
                // v10: Home character — saree or headphones girl (2D illustration, not a 3D model).
                NovaSettingsRow(
                    title = "Home character",
                    subtitle = "Choose Lia's look — saree or headphones",
                    icon = Icons.Default.Face,
                    onClick = { navController.navigate("character_settings") }
                )
                NovaSettingsRow(
                    title = "Theme Mode",
                    subtitle = appState.themeMode,
                    icon = Icons.Default.DarkMode,
                    onClick = {
                        val nextMode = when (appState.themeMode) {
                            "DARK" -> "LIGHT"
                            "LIGHT" -> "SYSTEM"
                            else -> "DARK"
                        }
                        appState.applyThemeMode(context, nextMode)
                    }
                )
                NovaSettingsRow(
                    title = "Reduced Motion",
                    subtitle = "Minimize particle and parallax motion",
                    icon = Icons.Default.Animation,
                    switch = appState.reducedMotion,
                    onCheckedChange = { appState.applyReducedMotion(context, it) }
                )
                // V16 (P1-11): accent picker — Rose is the default (the
                // V13 warm theme); Blue / Purple recolour the accent.
                Text(
                    text = "Accent colour",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                NovaChipRow(
                    chips = listOf("Rose", "Blue", "Purple").map {
                        it to (appState.accent == it.uppercase())
                    },
                    onChipClick = { index ->
                        val picked = listOf("ROSE", "BLUE", "PURPLE")[index]
                        appState.applyAccent(context, picked)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Section 3: Device & System
        NovaSectionHeader(title = "System & Permissions")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 8.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Permissions",
                    subtitle = "Microphone, Contacts, Calls, Background",
                    icon = Icons.Default.Security,
                    onClick = { navController.navigate("permissions") }
                )
                // v10: Voice Keyboard — speak to type in any app (Hindi / English).
                NovaSettingsRow(
                    title = "Voice Keyboard",
                    subtitle = "Speak to type in any app — Hindi / English",
                    icon = Icons.Default.Keyboard,
                    onClick = { navController.navigate("voice_keyboard") }
                )
                // Flavor-specific Accessibility Row (active in direct, stub in play)
                AccessibilitySettingsRow()
            }
        }

        // V16: Lia's extras — the new screens, one hub section.
        NovaSectionHeader(title = "Lia's extras (V16)")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Setup wizard",
                    subtitle = "Permissions, engine, persona — dobara chalao",
                    icon = Icons.Default.Build,
                    onClick = { navController.navigate("setup_wizard") }
                )
                NovaSettingsRow(
                    title = "Skills",
                    subtitle = "${SkillsStore.installedCount(context)} / 6 installed — Morning Briefing, Study Helper, Music DJ, Journal, Reminder, Translator",
                    icon = Icons.Default.Star,
                    onClick = { navController.navigate("skills") }
                )
                NovaSettingsRow(
                    title = "Actions",
                    subtitle = "Phone par Lia kya kar sakti hai — poori list, tap karo to chalega",
                    icon = Icons.Default.List,
                    onClick = { navController.navigate("actions") }
                )
                NovaSettingsRow(
                    title = "Sub-agents",
                    subtitle = "Research, Story, Song list — dated files banate hain",
                    icon = Icons.Default.Info,
                    onClick = { navController.navigate("sub_agents") }
                )
                NovaSettingsRow(
                    title = "Backup",
                    subtitle = "Ek JSON file: chats + facts + reminders (koi key nahi)",
                    icon = Icons.Default.Backup,
                    onClick = { navController.navigate("backup") }
                )
                NovaSettingsRow(
                    title = "Crash reports",
                    subtitle = if (CrashReportStore.all(context).isEmpty()) {
                        "Lia abhi tak kabhi crash nahi hui"
                    } else {
                        "${CrashReportStore.all(context).size} report(s) — tap to view or share"
                    },
                    icon = Icons.Default.BugReport,
                    onClick = { navController.navigate("crash_report") }
                )
                NovaSettingsRow(
                    title = "Voice enrolment (Hey Lia)",
                    subtitle = "3 phrases bol kar apni awaz enrol karo — biometric NAHI hai",
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = { navController.navigate("voice_enrolment") }
                )
                NovaSettingsRow(
                    title = "WhatsApp arrival alerts",
                    subtitle = if (whatsappAlertsOn) {
                        "On — WhatsApp message aane par ek line bolegi (reply nahi)"
                    } else {
                        "Off — sirf alert, kabhi auto-reply nahi"
                    },
                    icon = Icons.Default.Message,
                    switch = whatsappAlertsOn,
                    onCheckedChange = { checked ->
                        WhatsAppArrivalAlerts.setEnabled(context, checked)
                        whatsappAlertsOn = checked
                    }
                )
                Text(
                    text = "Alerts ke liye notification access chahiye — Settings > " +
                        "Permissions mein 'Notifications' on karo. Alerts sirf tab " +
                        "bolti hain jab Lia idle ho — conversation ke beech mein kabhi nahi.",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // V16 (P0-2): Advanced — grouped rows, each with a one-line "why".
        NovaSectionHeader(title = "Advanced")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Voice pipeline debugger",
                    subtitle = "Live voice session ki details — latency, reconnects",
                    icon = Icons.Default.Speed,
                    onClick = { navController.navigate("debug") }
                )
                NovaSettingsRow(
                    title = "Privacy",
                    subtitle = "Kya data kahan jata hai — aur kya kabhi nahi jata",
                    icon = Icons.Default.Security,
                    onClick = { navController.navigate("privacy") }
                )
                NovaSettingsRow(
                    title = "About",
                    subtitle = "Version, credits aur app ke baare mein",
                    icon = Icons.Default.Info,
                    onClick = { navController.navigate("about") }
                )
            }
        }
    }

    // V15: AI engine picker — Pollinations / Gemini, with the
    // state of each shown plainly. Gemini without a key opens the key
    // dialog.
    if (showEngineDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = { Text(text = "AI Engine", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val optionModifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                    // Pollinations — always available, no key.
                    Row(
                        modifier = optionModifier.clickable {
                            EnginePreferences.setEngine(context, ChatEngine.POLLINATIONS)
                            engine = ChatEngine.POLLINATIONS
                            showEngineDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pollinations — free, no key",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Online, works the moment the app opens",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (engine == ChatEngine.POLLINATIONS) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
                    // Gemini — needs the user's key.
                    Row(
                        modifier = optionModifier.clickable {
                            if (!ApiKeyStore.hasKey(context)) {
                                showEngineDialog = false
                                showApiKeyDialog = true
                            } else {
                                EnginePreferences.setEngine(context, ChatEngine.GEMINI)
                                engine = ChatEngine.GEMINI
                                showEngineDialog = false
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini (your key)",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (ApiKeyStore.hasKey(context)) {
                                    "Better quality, your free key"
                                } else {
                                    "Needs a Gemini key — tap to add one"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (engine == ChatEngine.GEMINI) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
                }
            },
            confirmButton = {
                NovaButton(
                    text = "Close",
                    onClick = { showEngineDialog = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }

    // V18: Lia's voice picker — ONLINE (Kokoro) / SYSTEM (device TTS).
    // ONLINE is the default; a quota-exhausted or offline ONLINE
    // reply falls back to SYSTEM automatically.
    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text(text = "Lia's voice", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val optionModifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                    // ONLINE — human-like internet voice, ~20/hour.
                    Row(
                        modifier = optionModifier.clickable {
                            VoiceOutputPreferences.setEngine(context, VoiceOutputEngine.ONLINE)
                            voiceEngine = VoiceOutputEngine.ONLINE
                            showVoiceDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Internet voice (Kokoro)",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Insano jaisi awaz, bina key · ~20 ghantay mein, phir device voice",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (voiceEngine == VoiceOutputEngine.ONLINE) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
                    // SYSTEM — device TTS, always available.
                    Row(
                        modifier = optionModifier.clickable {
                            VoiceOutputPreferences.setEngine(context, VoiceOutputEngine.SYSTEM)
                            voiceEngine = VoiceOutputEngine.SYSTEM
                            showVoiceDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device voice",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Hamesha available — bina internet, bina limit",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (voiceEngine == VoiceOutputEngine.SYSTEM) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
                }
            },
            confirmButton = {
                NovaButton(
                    text = "Close",
                    onClick = { showVoiceDialog = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text(text = "Gemini API Key", color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        text = "Optional. Lia works free without any key (Pollinations). " +
                            "Add your own free Gemini key only if you want Gemini's better " +
                            "quality — then pick the Gemini engine in Settings. The key is " +
                            "stored only on this phone.",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        placeholder = "AIzaSy..."
                    )
                }
            },
            confirmButton = {
                NovaButton(
                    text = "Save Key",
                    onClick = {
                        ApiKeyStore.saveKey(context, apiKeyInput)
                        showApiKeyDialog = false
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showApiKeyDialog = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }

    // Assistant Name Dialog
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(text = "Rename Assistant", color = colors.textPrimary) },
            text = {
                NovaTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = "Lia"
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Save",
                    onClick = {
                        appState.applyAssistantName(context, nameInput)
                        showNameDialog = false
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showNameDialog = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}
