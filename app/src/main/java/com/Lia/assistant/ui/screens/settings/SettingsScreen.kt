package com.Lia.assistant.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.Lia.assistant.data.AlwaysListeningPreferences
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.ChatEngine
import com.Lia.assistant.data.EnginePreferences
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.VoiceOutputEngine
import com.Lia.assistant.data.VoiceOutputPreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.AlwaysListeningWatchdogWorker
import com.Lia.assistant.voice.HindiTtsEngine
import com.Lia.assistant.voice.OnlineTtsEngine
import com.Lia.assistant.voice.PocketTtsEngine
import com.Lia.assistant.voice.VoiceForegroundService
import com.Lia.assistant.voice.WakeWordModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V20 — Settings, simplified to 12 rows only. Everything that is
 * not what the app actually does was removed, so its row is gone
 * too. What is left is what the app actually does: you, Lia's
 * voice, memory, listening, look, key, character, image, prompts.
 * V21 adds exactly ONE row back: "AI Engine" (Pollinations free
 * no-key / Gemini with your own free key) — 13 rows total.
 */
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

    var showFriendDialog by remember { mutableStateOf(false) }
    var friendInput by remember { mutableStateOf(appState.userName) }

    var memoryFactCount by remember { mutableStateOf(0) }
    // V18/V19: Lia's voice choice + online quota used this hour,
    // refreshed on entry. Pocket-ready tracks whether the ~191 MB
    // model is downloaded (cached SHA check, on IO). V21: Hindi-ready
    // tracks the ~139 MB Hindi voice model the same way, and
    // chatEngine is the Settings "AI Engine" choice (Pollinations
    // free no-key, or Gemini with the user's own free key).
    var voiceEngine by remember { mutableStateOf(VoiceOutputPreferences.getEngine(context)) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var onlineQuotaUsed by remember { mutableStateOf(0) }
    var pocketReady by remember { mutableStateOf(false) }
    var hindiReady by remember { mutableStateOf(false) }
    var chatEngine by remember { mutableStateOf(EnginePreferences.getEngine(context)) }
    var showEngineDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            memoryFactCount = FactMemoryStore.loadFacts(context).size
            onlineQuotaUsed = OnlineTtsEngine.quotaUsed(context)
            pocketReady = PocketTtsEngine.isReady(context)
            hindiReady = HindiTtsEngine.isReady(context)
        }
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

        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                // 1. Friend — your name (Lia calls you this).
                NovaSettingsRow(
                    title = "Friend",
                    subtitle = appState.userName,
                    icon = Icons.Default.Person,
                    onClick = {
                        friendInput = appState.userName
                        showFriendDialog = true
                    }
                )
                // 2. Assistant — Lia's name + personality.
                NovaSettingsRow(
                    title = "Assistant",
                    subtitle = "${appState.assistantName} — ${appState.personality.displayName}",
                    icon = Icons.Default.Mood,
                    onClick = { navController.navigate("personality") }
                )
                // 3. Lia's voice — HINDI (on-device Hindi voice, V21
                // default) / POCKET (on-device English) / ONLINE
                // (Kokoro) / SYSTEM (device TTS). The chain is HINDI →
                // POCKET → ONLINE → SYSTEM, so a dead engine falls
                // back at once.
                NovaSettingsRow(
                    title = "Lia's voice",
                    subtitle = when (voiceEngine) {
                        VoiceOutputEngine.HINDI ->
                            if (hindiReady) {
                                "Hindi voice — desi lehja, bina internet, tez awaz"
                            } else {
                                "Hindi voice — model download nahi hua (neeche se karo)"
                            }
                        VoiceOutputEngine.POCKET ->
                            if (pocketReady) {
                                "On-device Lia voice — bina internet, tez awaz"
                            } else {
                                "On-device Lia voice — model download nahi hua (neeche se karo)"
                            }
                        VoiceOutputEngine.ONLINE ->
                            "Internet voice (Kokoro) — insano jaisi awaz · " +
                                "$onlineQuotaUsed/20 is ghantay mein"
                        VoiceOutputEngine.SYSTEM ->
                            "Device voice — hamesha available, bina internet"
                    },
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = { showVoiceDialog = true }
                )
                // 4. On-device voice — Hindi + Pocket TTS model
                // downloads + voice pickers.
                NovaSettingsRow(
                    title = "On-device voice",
                    subtitle = if (hindiReady) {
                        "Hindi voice downloaded — ready (~139 MB)"
                    } else if (pocketReady) {
                        "Lia voice downloaded — ready (~191 MB)"
                    } else {
                        "Hindi + Lia voice download karo — Wi-Fi, ek dafa"
                    },
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = { navController.navigate("on_device_voice") }
                )
                // 5. Memory — what Lia remembers about you.
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
                // 6. Spoken Language — tap to cycle.
                NovaSettingsRow(
                    title = "Spoken Language",
                    subtitle = appState.language.displayName,
                    icon = Icons.Default.Language,
                    onClick = {
                        val nextLang = when (appState.language) {
                            LanguagePreference.AUTO -> LanguagePreference.HINDI
                            LanguagePreference.HINDI -> LanguagePreference.HINGLISH
                            LanguagePreference.HINGLISH -> LanguagePreference.ENGLISH
                            LanguagePreference.ENGLISH -> LanguagePreference.AUTO
                        }
                        appState.applyLanguage(context, nextLang)
                    }
                )
                // 7. Always listening ("Hey Lia").
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
                // 8. Theme Mode — tap to cycle DARK / LIGHT / SYSTEM.
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
                // 9. Gemini API Key (optional) — Lia works free without it.
                NovaSettingsRow(
                    title = "Gemini API Key (optional)",
                    subtitle = if (ApiKeyStore.hasKey(context)) "Configured locally" else "Not needed — Lia works free without it",
                    icon = Icons.Default.Key,
                    onClick = { showApiKeyDialog = true }
                )
                // 10. AI Engine (V21) — Pollinations (free, no key,
                // default) or Gemini (your own free key: faster,
                // understands Roman Urdu better). Needs a key only
                // when Gemini is picked.
                NovaSettingsRow(
                    title = "AI Engine",
                    subtitle = when (chatEngine) {
                        ChatEngine.GEMINI ->
                            if (ApiKeyStore.hasKey(context)) {
                                "Gemini — tez, behtar samajh (apni key)"
                            } else {
                                "Gemini — key chahiye, neeche se daalo"
                            }
                        ChatEngine.POLLINATIONS ->
                            "Pollinations — free, bina key"
                    },
                    icon = Icons.Default.Psychology,
                    onClick = { showEngineDialog = true }
                )
                // 11. Home character — saree or headphones girl.
                NovaSettingsRow(
                    title = "Home character",
                    subtitle = "Choose Lia's look — saree or headphones",
                    icon = Icons.Default.Face,
                    onClick = { navController.navigate("character_settings") }
                )
                // 12. Create Image — describe it, Lia draws it.
                NovaSettingsRow(
                    title = "Create Image",
                    subtitle = "Describe it — Lia draws it, free",
                    icon = Icons.Default.Image,
                    onClick = { navController.navigate("create_image") }
                )
                // 13. Prompt Generator — better prompts, free.
                NovaSettingsRow(
                    title = "Prompt Generator",
                    subtitle = "Better prompts for chat & images — free",
                    icon = Icons.Default.Lightbulb,
                    onClick = { navController.navigate("prompt_generator") }
                )
            }
        }
    }

    // Lia's voice picker — HINDI (on-device Hindi voice, V21
    // default when downloaded) / POCKET (on-device English) /
    // ONLINE (Kokoro) / SYSTEM (device TTS).
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
                    // HINDI — on-device Hindi voice, the V21 fix for
                    // the foreign accent. Roman Urdu is transliterated
                    // to Devanagari inside the engine.
                    Row(
                        modifier = optionModifier.clickable {
                            VoiceOutputPreferences.setEngine(context, VoiceOutputEngine.HINDI)
                            voiceEngine = VoiceOutputEngine.HINDI
                            showVoiceDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hindi voice (on-device)",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (hindiReady) {
                                    "Desi lehja, bina internet, tez awaz — downloaded, ready"
                                } else {
                                    "Desi lehja, bina internet — pehle On-device voice se download karo (~139 MB)"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (voiceEngine == VoiceOutputEngine.HINDI) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
                    // POCKET — on-device Lia voice, fast, no internet.
                    Row(
                        modifier = optionModifier.clickable {
                            VoiceOutputPreferences.setEngine(context, VoiceOutputEngine.POCKET)
                            voiceEngine = VoiceOutputEngine.POCKET
                            showVoiceDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "On-device Lia voice (Pocket)",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (pocketReady) {
                                    "Bina internet, tez awaz — downloaded, ready"
                                } else {
                                    "Bina internet, tez awaz — pehle On-device voice se download karo"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (voiceEngine == VoiceOutputEngine.POCKET) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
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
                                text = "Insano jaisi awaz, bina key · ~20 ghantay mein, phir on-device / device voice",
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

    // AI Engine picker (V21) — Pollinations (free, no key, default)
    // or Gemini (your own free key: faster, understands Roman Urdu
    // better). Picking Gemini without a key shows a plain error in
    // voice chat instead of failing silently.
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
                    // POLLINATIONS — free, no key.
                    Row(
                        modifier = optionModifier.clickable {
                            EnginePreferences.setEngine(context, ChatEngine.POLLINATIONS)
                            chatEngine = ChatEngine.POLLINATIONS
                            showEngineDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pollinations (free)",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Bina key, bina login — thoda slow, 1 reply / 45 sec limit",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (chatEngine == ChatEngine.POLLINATIONS) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = colors.accent
                            )
                        }
                    }
                    // GEMINI — your own free key.
                    Row(
                        modifier = optionModifier.clickable {
                            EnginePreferences.setEngine(context, ChatEngine.GEMINI)
                            chatEngine = ChatEngine.GEMINI
                            showEngineDialog = false
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini (apni free key)",
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (ApiKeyStore.hasKey(context)) {
                                    "Tez, behtar samajh — key daal di hai"
                                } else {
                                    "Tez, behtar samajh — pehle Gemini API Key se key daalo"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        if (chatEngine == ChatEngine.GEMINI) {
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
                            "quality. The key is stored only on this phone.",
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

    // Friend Name Dialog — what Lia calls you.
    if (showFriendDialog) {
        AlertDialog(
            onDismissRequest = { showFriendDialog = false },
            title = { Text(text = "Your name", color = colors.textPrimary) },
            text = {
                NovaTextField(
                    value = friendInput,
                    onValueChange = { friendInput = it },
                    placeholder = "Friend"
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Save",
                    onClick = {
                        appState.applyUserName(context, friendInput)
                        showFriendDialog = false
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showFriendDialog = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}
