package com.Lia.assistant.ui.screens.elevenlabs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.ElevenLabsStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.ElevenLabsAudioPlayer
import com.Lia.assistant.voice.ElevenLabsBuiltInPicks
import com.Lia.assistant.voice.ElevenLabsClient
import com.Lia.assistant.voice.ElevenLabsModels
import com.Lia.assistant.voice.ElevenLabsResult
import com.Lia.assistant.voice.ElevenLabsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Connections → ElevenLabs (v10).
 *
 * Mirrors the official LIA v1.12 flow from the walkthrough video:
 *  1. Paste your own ElevenLabs API key (created on elevenlabs.io with a
 *     Gmail login — free tier available; ElevenLabs is a paid service and
 *     Lia never pays for it or embeds any key).
 *  2. Pick a voice under "Your voices" or "ElevenLabs picks", with a
 *     play-sample button ("Hi, I am Lia, this is how I sound").
 *  3. Pick one of the 5 models (shown after a voice is selected).
 *  4. Optionally add a voice you cloned on ElevenLabs' site (Voice ID + name).
 *  5. "Use Gemini instead" clears the ElevenLabs voice back to Gemini.
 *
 * Honest limitation, same as the official app: ElevenLabs audio does NOT
 * lip-sync Lia's 3D character — only Gemini voices animate her lips.
 *
 * WIRING (not done here — SettingsScreen.kt was not edited):
 * add `composable("elevenlabs_settings") { ElevenLabsSettingsScreen(navController) }`
 * to NovaApp.kt, a "Connections → ElevenLabs" row in SettingsScreen.kt, and
 * an "ElevenLabs voice" row in the Voice & language section that navigates
 * to the same route.
 */
@Composable
fun ElevenLabsSettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKeyInput by remember { mutableStateOf(ElevenLabsStore.getKey(context)) }
    var keySaved by remember { mutableStateOf(ElevenLabsStore.hasKey(context)) }
    var keyVisible by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isLoadingVoices by remember { mutableStateOf(false) }
    var apiVoices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
    var selectedVoiceId by remember { mutableStateOf(ElevenLabsStore.selectedVoiceId(context)) }
    var selectedVoiceName by remember { mutableStateOf(ElevenLabsStore.selectedVoiceName(context)) }
    var selectedModelId by remember { mutableStateOf(ElevenLabsStore.selectedModel(context)) }
    var customVoices by remember { mutableStateOf(ElevenLabsStore.getCustomVoices(context)) }
    var cloneIdInput by remember { mutableStateOf("") }
    var cloneNameInput by remember { mutableStateOf("") }
    var samplePlayingId by remember { mutableStateOf<String?>(null) }

    fun openInBrowser(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            statusMessage = "Couldn't open the browser. Go to $url manually."
        }
    }

    fun loadVoices(onDone: ((ElevenLabsResult<List<ElevenLabsVoice>>) -> Unit)? = null) {
        val key = ElevenLabsStore.getKey(context)
        if (key.isBlank()) {
            statusMessage = "Add your ElevenLabs API key first."
            return
        }
        isLoadingVoices = true
        scope.launch(Dispatchers.IO) {
            val result = ElevenLabsClient.fetchVoices(key)
            withContext(Dispatchers.Main) {
                isLoadingVoices = false
                when (result) {
                    is ElevenLabsResult.Success -> {
                        apiVoices = result.data
                        statusMessage = "Found ${result.data.size} voices."
                    }
                    is ElevenLabsResult.Error -> {
                        statusMessage = result.message
                    }
                }
                onDone?.invoke(result)
            }
        }
    }

    // Load the voice list automatically when the screen opens with a saved key.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ElevenLabsAudioPlayer.onPlaybackFinished = { samplePlayingId = null }
        if (keySaved) loadVoices()
    }

    fun playSample(voice: ElevenLabsVoice) {
        if (samplePlayingId == voice.id) {
            ElevenLabsAudioPlayer.stop()
            samplePlayingId = null
            return
        }
        val key = ElevenLabsStore.getKey(context)
        samplePlayingId = voice.id
        scope.launch(Dispatchers.IO) {
            val result = ElevenLabsClient.synthesize(
                apiKey = key,
                voiceId = voice.id,
                modelId = selectedModelId,
                text = "Hi, I am Lia, this is how I sound"
            )
            withContext(Dispatchers.Main) {
                when (result) {
                    is ElevenLabsResult.Success -> {
                        scope.launch {
                            val started = ElevenLabsAudioPlayer.play(context, result.data)
                            if (!started) {
                                samplePlayingId = null
                                statusMessage = "Couldn't play the voice sample."
                            }
                        }
                    }
                    is ElevenLabsResult.Error -> {
                        samplePlayingId = null
                        statusMessage = result.message
                    }
                }
            }
        }
    }

    // Split the API list the way the official picker does. Custom cloned
    // voices the user added manually always live under "Your voices".
    val pickNamesLower = ElevenLabsBuiltInPicks.NAMES.map { it.lowercase() }
    val apiPickVoices = apiVoices.filter { it.name.lowercase() in pickNamesLower }
    val apiOwnVoices = apiVoices.filter { it.name.lowercase() !in pickNamesLower }
    val missingPicks = ElevenLabsBuiltInPicks.NAMES.filter { pickName ->
        apiPickVoices.none { it.name.equals(pickName, ignoreCase = true) }
    }.mapNotNull { ElevenLabsBuiltInPicks.fallbackVoice(it) }
    val yourVoices = apiOwnVoices + customVoices.map { ElevenLabsVoice(it.voiceId, it.name) }
    val elevenLabsPicks = apiPickVoices + missingPicks

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp)
    ) {
        // Top bar
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
                text = "ElevenLabs",
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
                Text(
                    text = "Natural voices from ElevenLabs, using your own API key.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ElevenLabs is a paid service with a free tier. Create a free account on elevenlabs.io with your Gmail, make an API key, and paste it below. Your key stays on this phone only — Lia never sends it anywhere except to ElevenLabs.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Note: ElevenLabs voices sound natural, but they do not move Lia's lips. Only Gemini voices lip-sync her 3D character.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }

        // ---- API key -------------------------------------------------------
        NovaSectionHeader(title = "API Key")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    placeholder = { Text("sk_...", color = colors.textTertiary) },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (keyVisible) "Hide key" else "Show key",
                                tint = colors.textSecondary
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedContainerColor = colors.surfaceGlass,
                        unfocusedContainerColor = colors.surfaceGlass,
                        focusedIndicatorColor = colors.accent,
                        unfocusedIndicatorColor = colors.surfaceBorder,
                        cursorColor = colors.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovaButton(
                        text = "Save",
                        onClick = {
                            ElevenLabsStore.saveKey(context, apiKeyInput)
                            keySaved = ElevenLabsStore.hasKey(context)
                            statusMessage = if (keySaved) {
                                "API key saved on this device."
                            } else {
                                "API key cleared."
                            }
                            if (keySaved) loadVoices()
                        }
                    )
                    NovaButton(
                        text = if (isTesting) "Testing…" else "Test",
                        onClick = {
                            if (apiKeyInput.isBlank()) {
                                statusMessage = "Paste your ElevenLabs API key first."
                                return@NovaButton
                            }
                            ElevenLabsStore.saveKey(context, apiKeyInput)
                            keySaved = true
                            isTesting = true
                            loadVoices { result ->
                                isTesting = false
                                if (result is ElevenLabsResult.Success) {
                                    statusMessage = "Key works — found ${result.data.size} voices."
                                }
                            }
                        },
                        type = NovaButtonType.SECONDARY,
                        enabled = !isTesting && !isLoadingVoices
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(
                    text = "Get an API key on elevenlabs.io",
                    onClick = { openInBrowser("https://elevenlabs.io/app/api-keys") },
                    type = NovaButtonType.TEXT
                )
                Text(
                    text = "On the site: sign in with Gmail → Create key → give it any name → copy the key and paste it here.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusMessage!!,
                        fontSize = 13.sp,
                        color = colors.accent
                    )
                }
            }
        }

        // ---- Voices (only once a key is saved) ----------------------------
        if (keySaved) {
            NovaSectionHeader(title = "Voice")
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                elevation = 4.dp
            ) {
                Column {
                    if (isLoadingVoices) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = colors.accent
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Loading voices…", fontSize = 14.sp, color = colors.textSecondary)
                        }
                    } else if (apiVoices.isEmpty() && customVoices.isEmpty()) {
                        Text(
                            text = "No voices loaded yet. Tap Refresh, or add a cloned voice below.",
                            fontSize = 14.sp,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        NovaButton(
                            text = "Refresh voices",
                            onClick = { loadVoices() },
                            type = NovaButtonType.SECONDARY
                        )
                    } else {
                        if (selectedVoiceId.isNotEmpty()) {
                            Text(
                                text = "Selected: $selectedVoiceName",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        VoiceGroup(
                            title = "Your voices",
                            voices = yourVoices,
                            selectedVoiceId = selectedVoiceId,
                            samplePlayingId = samplePlayingId,
                            onSelect = { voice ->
                                ElevenLabsStore.saveSelectedVoice(context, voice.id, voice.name)
                                selectedVoiceId = voice.id
                                selectedVoiceName = voice.name
                            },
                            onPlaySample = { voice -> playSample(voice) }
                        )
                        VoiceGroup(
                            title = "ElevenLabs picks",
                            voices = elevenLabsPicks,
                            selectedVoiceId = selectedVoiceId,
                            samplePlayingId = samplePlayingId,
                            onSelect = { voice ->
                                ElevenLabsStore.saveSelectedVoice(context, voice.id, voice.name)
                                selectedVoiceId = voice.id
                                selectedVoiceName = voice.name
                            },
                            onPlaySample = { voice -> playSample(voice) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        NovaButton(
                            text = "Refresh voices",
                            onClick = { loadVoices() },
                            type = NovaButtonType.SECONDARY
                        )
                    }
                }
            }

            // ---- Model picker (shown after a voice is selected, like official) --
            if (selectedVoiceId.isNotEmpty()) {
                NovaSectionHeader(title = "Model")
                NovaGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    elevation = 4.dp
                ) {
                    Column {
                        for (model in ElevenLabsModels.ALL) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ElevenLabsStore.saveSelectedModel(context, model.id)
                                        selectedModelId = model.id
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedModelId == model.id,
                                    onClick = {
                                        ElevenLabsStore.saveSelectedModel(context, model.id)
                                        selectedModelId = model.id
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = model.displayName,
                                    fontSize = 15.sp,
                                    color = colors.textPrimary
                                )
                            }
                        }
                        Text(
                            text = "Eleven v4 and Eleven v4 Turbo sound the most natural.",
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            // ---- Add a cloned voice -----------------------------------------
            NovaSectionHeader(title = "Add a cloned voice")
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                elevation = 4.dp
            ) {
                Column {
                    Text(
                        text = "Clone your own voice on ElevenLabs' site first, then add it here with its Voice ID and a name. Cloning itself does not happen inside Lia.",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NovaButton(
                        text = "Clone a voice on elevenlabs.io",
                        onClick = { openInBrowser("https://elevenlabs.io/app/voice-lab") },
                        type = NovaButtonType.TEXT
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NovaTextField(
                        value = cloneIdInput,
                        onValueChange = { cloneIdInput = it },
                        placeholder = "Voice ID"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NovaTextField(
                        value = cloneNameInput,
                        onValueChange = { cloneNameInput = it },
                        placeholder = "Voice name"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaButton(
                        text = "Add voice",
                        onClick = {
                            val added = ElevenLabsStore.addCustomVoice(
                                context,
                                cloneIdInput,
                                cloneNameInput
                            )
                            if (added) {
                                customVoices = ElevenLabsStore.getCustomVoices(context)
                                cloneIdInput = ""
                                cloneNameInput = ""
                                statusMessage = "Cloned voice added under “Your voices”."
                            } else {
                                statusMessage = "Enter both a Voice ID and a name."
                            }
                        }
                    )
                    if (customVoices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        for (custom in customVoices) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = custom.name,
                                    fontSize = 14.sp,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    ElevenLabsStore.removeCustomVoice(context, custom.voiceId)
                                    customVoices = ElevenLabsStore.getCustomVoices(context)
                                    if (selectedVoiceId == custom.voiceId) {
                                        ElevenLabsStore.clearSelectedVoice(context)
                                        selectedVoiceId = ""
                                        selectedVoiceName = ""
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove ${custom.name}",
                                        tint = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Back to Gemini ----------------------------------------------
            NovaSectionHeader(title = "Voice engine")
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                elevation = 4.dp
            ) {
                Column {
                    NovaButton(
                        text = "Use Gemini instead",
                        onClick = {
                            ElevenLabsAudioPlayer.stop()
                            samplePlayingId = null
                            ElevenLabsStore.clearSelectedVoice(context)
                            selectedVoiceId = ""
                            selectedVoiceName = ""
                            statusMessage = "Back to Gemini voice (Sulafat) — with lip sync."
                        },
                        type = NovaButtonType.SECONDARY
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your ElevenLabs key stays saved — only the voice choice is cleared. Pick a voice above any time to switch back.",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceGroup(
    title: String,
    voices: List<ElevenLabsVoice>,
    selectedVoiceId: String,
    samplePlayingId: String?,
    onSelect: (ElevenLabsVoice) -> Unit,
    onPlaySample: (ElevenLabsVoice) -> Unit
) {
    val colors = LocalNovaColors.current
    if (voices.isEmpty()) return
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = colors.accent,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    for (voice in voices) {
        val isSelected = selectedVoiceId == voice.id
        val isSampling = samplePlayingId == voice.id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(voice) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(20.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = voice.name,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onPlaySample(voice) }) {
                Icon(
                    imageVector = if (isSampling) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isSampling) {
                        "Stop sample"
                    } else {
                        "Play sample of ${voice.name}"
                    },
                    tint = colors.accent
                )
            }
        }
    }
}
