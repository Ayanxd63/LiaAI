package com.Lia.assistant.ui.screens.settings

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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.LaughDetectionPreferences
import com.Lia.assistant.data.MemoryEmbeddingPreferences
import com.Lia.assistant.data.OnDeviceSttPreferences
import com.Lia.assistant.data.PocketTtsVoicePreferences
import com.Lia.assistant.data.SpeakerVerifyPreferences
import com.Lia.assistant.data.VadGatePreferences
import com.Lia.assistant.data.VoiceOutputEngine
import com.Lia.assistant.data.VoiceOutputPreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.PocketTtsEngine
import com.Lia.assistant.voice.SherpaRuntime
import com.Lia.assistant.voice.VoiceModel
import com.Lia.assistant.voice.VoiceModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V17 — on-device voice manager (Settings → On-device voice).
 *
 * Every V17 engine lives here: its model download (Wi-Fi only,
 * resumable, SHA-256 verified — same rules as V15's offline LLM),
 * its on/off toggle, and its licence. Nothing downloads until the
 * user taps Download for that engine; toggles alone never fetch
 * anything. The whole screen degrades gracefully on phones whose
 * CPU cannot run sherpa-onnx (see [SherpaRuntime]).
 *
 * Sizes: Lia voice ~191 MB, speech recognition ~118 MB, VAD ~2 MB,
 * speaker voice-match ~38 MB, laugh detection ~65 MB, memory
 * embeddings ~22 MB. Total ~455 MB if everything is enabled — but
 * each engine is independent, so the user only downloads what they
 * turn on.
 */
@Composable
fun OnDeviceVoiceScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engineChoice by remember {
        mutableStateOf(VoiceOutputPreferences.getEngine(context))
    }
    var pocketVoice by remember {
        mutableStateOf(PocketTtsVoicePreferences.getVoiceId(context))
    }
    var sttOn by remember { mutableStateOf(OnDeviceSttPreferences.isEnabled(context)) }
    var vadOn by remember { mutableStateOf(VadGatePreferences.isEnabled(context)) }
    var laughOn by remember { mutableStateOf(LaughDetectionPreferences.isEnabled(context)) }
    var speakerOn by remember { mutableStateOf(SpeakerVerifyPreferences.isEnabled(context)) }
    var embeddingsOn by remember {
        mutableStateOf(MemoryEmbeddingPreferences.isEnabled(context))
    }

    var readyById by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var downloadedById by remember { mutableStateOf(mapOf<String, Long>()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var errorById by remember { mutableStateOf(mapOf<String, String>()) }
    var showDeleteId by remember { mutableStateOf<String?>(null) }
    var testingVoice by remember { mutableStateOf(false) }
    val sherpaAvailable = remember { SherpaRuntime.isAvailable() }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val ready = mutableMapOf<String, Boolean>()
            val downloaded = mutableMapOf<String, Long>()
            for (model in VoiceModelManager.MODELS) {
                ready[model.id] = VoiceModelManager.isModelReady(context, model)
                downloaded[model.id] = VoiceModelManager.downloadedBytes(context, model)
            }
            withContext(Dispatchers.Main) {
                readyById = ready
                downloadedById = downloaded
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun toggleFor(model: VoiceModel): Pair<Boolean, (Boolean) -> Unit>? {
        return when (model.id) {
            VoiceModelManager.MODEL_MOONSHINE.id -> sttOn to { checked ->
                OnDeviceSttPreferences.setEnabled(context, checked)
                sttOn = checked
            }
            VoiceModelManager.MODEL_VAD.id -> vadOn to { checked ->
                VadGatePreferences.setEnabled(context, checked)
                vadOn = checked
            }
            VoiceModelManager.MODEL_AUDIO_TAGGING.id -> laughOn to { checked ->
                LaughDetectionPreferences.setEnabled(context, checked)
                laughOn = checked
            }
            VoiceModelManager.MODEL_SPEAKER.id -> speakerOn to { checked ->
                SpeakerVerifyPreferences.setEnabled(context, checked)
                speakerOn = checked
            }
            VoiceModelManager.MODEL_MINILM.id -> embeddingsOn to { checked ->
                MemoryEmbeddingPreferences.setEnabled(context, checked)
                embeddingsOn = checked
            }
            else -> null // Pocket TTS uses the engine choice above
        }
    }

    fun iconFor(model: VoiceModel) = when (model.id) {
        VoiceModelManager.MODEL_POCKET_TTS.id -> Icons.Default.RecordVoiceOver
        VoiceModelManager.MODEL_MOONSHINE.id -> Icons.Default.Mic
        VoiceModelManager.MODEL_VAD.id -> Icons.Default.GraphicEq
        VoiceModelManager.MODEL_SPEAKER.id -> Icons.Default.Face
        VoiceModelManager.MODEL_AUDIO_TAGGING.id -> Icons.Default.MusicNote
        else -> Icons.Default.Psychology
    }

    fun hintFor(model: VoiceModel): String = when (model.id) {
        VoiceModelManager.MODEL_POCKET_TTS.id ->
            "Lia ki apni awaz — bina key, bina internet. English bolti hai; Roman Urdu English lehje mein parhti hai."
        VoiceModelManager.MODEL_MOONSHINE.id ->
            "Phone par sunna — bina internet. Sirf English; Urdu/Hindi ke liye system recognizer hi rahega."
        VoiceModelManager.MODEL_VAD.id ->
            "\"Hey Lia\" sunne se pehle check karta hai ke koi bol raha hai ya nahi — battery bachata hai."
        VoiceModelManager.MODEL_SPEAKER.id ->
            "\"Hey Lia\" par awaz match karta hai (enrolment screen). Ye biometric verification NAHI hai."
        VoiceModelManager.MODEL_AUDIO_TAGGING.id ->
            "Hansi pehchanta hai — Lia ek dafa warmly notice karegi. Sirf detection, koi recording nahi."
        else ->
            "Saved facts ko matlab se dhoondhta hai — \"meri ammi ka naam\" par sahi fact milta hai."
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
                text = "On-device voice",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Lia ki awaz aur sunna — sab phone ke andar, bina key, bina " +
                "internet. Har engine apna model Wi-Fi par download karta hai; " +
                "kuch bhi APK ke andar nahi aata.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (!sherpaAvailable) {
            Text(
                text = SherpaRuntime.unavailableReason()
                    ?: "On-device voice is phone par nahi chal sakta.",
                fontSize = 13.sp,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Voice output choice: system TTS or the on-device Lia voice.
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                NovaSettingsRow(
                    title = "Lia ki awaz",
                    subtitle = when (engineChoice) {
                        VoiceOutputEngine.POCKET -> if (readyById[VoiceModelManager.MODEL_POCKET_TTS.id] == true) {
                            "On-device Lia voice — bina key, bina internet"
                        } else {
                            "On-device Lia voice — model abhi download nahi hua (neeche se karo)"
                        }
                        VoiceOutputEngine.SYSTEM -> "System voice (cute voice) — har phone par chalti hai"
                    },
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = {
                        val next = if (engineChoice == VoiceOutputEngine.SYSTEM) {
                            VoiceOutputEngine.POCKET
                        } else {
                            VoiceOutputEngine.SYSTEM
                        }
                        VoiceOutputPreferences.setEngine(context, next)
                        engineChoice = next
                    }
                )
                if (engineChoice == VoiceOutputEngine.POCKET) {
                    NovaSettingsRow(
                        title = "Voice",
                        subtitle = if (pocketVoice == PocketTtsVoicePreferences.VOICE_LOONA) {
                            "Loona — dusri awaz"
                        } else {
                            "Lia — default awaz"
                        },
                        icon = Icons.Default.RecordVoiceOver,
                        onClick = {
                            val next = if (pocketVoice == PocketTtsVoicePreferences.VOICE_LOONA) {
                                PocketTtsVoicePreferences.VOICE_LIA
                            } else {
                                PocketTtsVoicePreferences.VOICE_LOONA
                            }
                            PocketTtsVoicePreferences.setVoiceId(context, next)
                            pocketVoice = next
                        }
                    )
                }
            }
        }

        for (model in VoiceModelManager.MODELS) {
            val isReady = readyById[model.id] == true
            val downloaded = downloadedById[model.id] ?: 0L
            val sizeMb = model.totalBytes / (1024 * 1024)
            val downloadedMb = downloaded / (1024 * 1024)
            val isDownloading = downloadingId == model.id
            val error = errorById[model.id]
            val toggle = toggleFor(model)

            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                elevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    NovaSettingsRow(
                        title = model.displayName,
                        subtitle = when {
                            isReady -> "Downloaded — ready ($sizeMb MB)"
                            downloaded > 0L -> "Paused at $downloadedMb MB / $sizeMb MB — tap Download to resume"
                            else -> "Not downloaded — $sizeMb MB, Wi-Fi only"
                        },
                        icon = iconFor(model),
                        onClick = null
                    )
                    Text(
                        text = hintFor(model),
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "Licence: ${model.licence}",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )

                    if (isDownloading) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = "Download ho raha hai… ${(progress * 100).toInt()}%",
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.accent
                            )
                        }
                    }

                    error?.let { message ->
                        Text(
                            text = message,
                            fontSize = 13.sp,
                            color = colors.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (!isReady && sherpaAvailable) {
                            NovaButton(
                                text = if (downloaded > 0L) "Resume download" else "Download on Wi-Fi",
                                onClick = {
                                    if (downloadingId != null) return@NovaButton
                                    if (!com.Lia.assistant.voice.OfflineModelManager.isWifiConnected(context)) {
                                        errorById = errorById + (model.id to
                                            "Sirf Wi-Fi par download hota hai — Wi-Fi on kar ke dobara try karo.")
                                        return@NovaButton
                                    }
                                    errorById = errorById - model.id
                                    downloadingId = model.id
                                    progress = if (model.totalBytes > 0) {
                                        downloaded.toFloat() / model.totalBytes
                                    } else {
                                        0f
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        val result = VoiceModelManager.downloadModel(context, model) { p ->
                                            scope.launch { progress = p }
                                        }
                                        withContext(Dispatchers.Main) {
                                            downloadingId = null
                                            progress = 0f
                                            result.onSuccess {
                                                refresh()
                                            }.onFailure { e ->
                                                errorById = errorById + (model.id to
                                                    (e.localizedMessage ?: "Download nahi ho saka — dobara try karo."))
                                                refresh()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        if (isReady) {
                            NovaButton(
                                text = "Delete model",
                                onClick = { showDeleteId = model.id },
                                type = NovaButtonType.SECONDARY
                            )
                        }
                        if (isReady && model.id == VoiceModelManager.MODEL_POCKET_TTS.id) {
                            NovaButton(
                                text = if (testingVoice) "Bol rahi hai…" else "Test voice",
                                onClick = {
                                    if (testingVoice) return@NovaButton
                                    testingVoice = true
                                    scope.launch(Dispatchers.IO) {
                                        val engine = PocketTtsEngine.create(context, pocketVoice)
                                        if (engine == null) {
                                            withContext(Dispatchers.Main) {
                                                testingVoice = false
                                                errorById = errorById + (model.id to
                                                    "Voice engine start nahi ho saka.")
                                            }
                                            return@launch
                                        }
                                        engine.speak(
                                            "Assalam-o-Alaikum! Main Lia hun — tumhari apni awaz, bina internet, bina key ke.",
                                            onDone = {
                                                engine.release()
                                                testingVoice = false
                                            },
                                            onError = {
                                                engine.release()
                                                testingVoice = false
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }

                    if (isReady && toggle != null) {
                        val (on, setOn) = toggle
                        NovaSettingsRow(
                            title = when (model.id) {
                                VoiceModelManager.MODEL_MOONSHINE.id -> "On-device sunna"
                                VoiceModelManager.MODEL_VAD.id -> "VAD gate (wake word)"
                                VoiceModelManager.MODEL_AUDIO_TAGGING.id -> "Hansi pehchanna"
                                VoiceModelManager.MODEL_SPEAKER.id -> "Voice-match gating"
                                else -> "Semantic memory search"
                            },
                            subtitle = if (on) "On" else "Off — model downloaded hai, feature band hai",
                            icon = iconFor(model),
                            switch = on,
                            onCheckedChange = { checked -> setOn(checked) }
                        )
                    }
                }
            }
        }

        Text(
            text = "Battery: on-device engine phone ka CPU use karte hain — " +
                "lambi baatein battery zyada khati hain. Models sirf tab load " +
                "hote hain jab unki feature on ho; voice session khatam hote hi " +
                "unload ho jate hain.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = "Attribution: Lia voice — Kyutai Pocket TTS (CC-BY-4.0) via " +
                "sherpa-onnx (Apache-2.0). Moonshine STT — MIT. Silero VAD — " +
                "MIT. ERes2Net speaker embedding — Apache-2.0. Zipformer audio " +
                "tagging — Apache-2.0. MiniLM embeddings — Apache-2.0.",
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    val deleteModel = showDeleteId?.let { id ->
        VoiceModelManager.MODELS.firstOrNull { it.id == id }
    }
    if (deleteModel != null) {
        AlertDialog(
            onDismissRequest = { showDeleteId = null },
            title = { Text(text = "Delete ${deleteModel.displayName}?", color = colors.textPrimary) },
            text = {
                Text(
                    text = "Model delete ho jayega (~${deleteModel.totalBytes / (1024 * 1024)} MB free). " +
                        "Dobara use karne ke liye Wi-Fi par dobara download karna hoga.",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Delete",
                    onClick = {
                        VoiceModelManager.deleteModel(context, deleteModel)
                        showDeleteId = null
                        refresh()
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showDeleteId = null },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}
