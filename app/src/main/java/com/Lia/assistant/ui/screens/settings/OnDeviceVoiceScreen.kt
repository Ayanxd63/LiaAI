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
import androidx.compose.material.icons.filled.GraphicEq
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
import com.Lia.assistant.data.OnlineTtsQuality
import com.Lia.assistant.data.OnlineTtsQualityPreferences
import com.Lia.assistant.data.OnlineTtsVoicePreferences
import com.Lia.assistant.data.PocketTtsVoicePreferences
import com.Lia.assistant.data.VoiceOutputEngine
import com.Lia.assistant.data.VoiceOutputPreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.HindiTtsEngine
import com.Lia.assistant.voice.OnlineTtsEngine
import com.Lia.assistant.voice.PocketTtsEngine
import com.Lia.assistant.voice.SherpaRuntime
import com.Lia.assistant.voice.VoiceModel
import com.Lia.assistant.voice.VoiceModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V21 — on-device voice manager (Settings → On-device voice).
 *
 * The screen holds the on-device engines: the Hindi voice
 * (Supertonic 3, ~139 MB — the V21 fix for the foreign accent),
 * the Lia voice (Pocket TTS, ~191 MB), and the Urdu/Hindi
 * listening models (Shruti, ~51 MB each). Each model downloads on
 * Wi-Fi only, resumable, SHA-256 verified. Nothing downloads
 * until the user taps Download; the toggle alone never fetches
 * anything. The whole screen degrades gracefully on phones whose
 * CPU cannot run sherpa-onnx (see [SherpaRuntime]).
 *
 * Moonshine STT (English), the VAD gate, speaker voice-match,
 * laugh detection and memory embeddings stay OUT (V18 diet) —
 * their rows, toggles and preferences were deleted here.
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
    var onlineVoice by remember {
        mutableStateOf(OnlineTtsVoicePreferences.getVoiceId(context))
    }
    var onlineQuality by remember {
        mutableStateOf(OnlineTtsQualityPreferences.getQuality(context))
    }
    var onlineQuota by remember { mutableStateOf(OnlineTtsEngine.quotaUsed(context)) }
    var testingOnlineVoice by remember { mutableStateOf(false) }
    var onlineTestError by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) {
        refresh()
        onlineQuota = OnlineTtsEngine.quotaUsed(context)
    }

    fun iconFor(model: VoiceModel) = Icons.Default.RecordVoiceOver

    fun hintFor(model: VoiceModel): String = when (model.id) {
        VoiceModelManager.MODEL_SUPERTONIC_TTS.id ->
            "Hindi bolne wali on-device awaz — Roman Urdu ko Devanagari mein badal kar desi lehje mein bolti hai. Yehi V21 ki awaz hai."
        VoiceModelManager.MODEL_SHRUTI_URDU.id ->
            "Urdu sunne wala on-device model — Lia tumhari Urdu behtar sunegi, bina internet."
        VoiceModelManager.MODEL_SHRUTI_HINDI.id ->
            "Hindi sunne wala on-device model — Urdu model na ho to ye istemal hota hai."
        else ->
            "Lia ki apni awaz — bina key, bina internet. English bolti hai; Roman Urdu English lehje mein parhti hai."
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
            text = "Lia ki apni awaz — on-device engine phone ke andar " +
                "chalta hai (bina key, bina internet); internet voice ko " +
                "internet chahiye. Model Wi-Fi par download hota hai; " +
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
                        VoiceOutputEngine.HINDI -> if (readyById[VoiceModelManager.MODEL_SUPERTONIC_TTS.id] == true) {
                            "Hindi voice — desi lehja, bina key, bina internet"
                        } else {
                            "Hindi voice — model abhi download nahi hua (neeche se karo)"
                        }
                        VoiceOutputEngine.ONLINE -> "Internet voice (Kokoro) — insano jaisi awaz, bina key · ~20 ghantay mein, phir offline voice"
                        VoiceOutputEngine.POCKET -> if (readyById[VoiceModelManager.MODEL_POCKET_TTS.id] == true) {
                            "On-device Lia voice — bina key, bina internet"
                        } else {
                            "On-device Lia voice — model abhi download nahi hua (neeche se karo)"
                        }
                        VoiceOutputEngine.SYSTEM -> "System voice (cute voice) — har phone par chalti hai"
                    },
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = {
                        // Tap cycles: internet → Hindi → on-device → system.
                        val next = when (engineChoice) {
                            VoiceOutputEngine.ONLINE -> VoiceOutputEngine.HINDI
                            VoiceOutputEngine.HINDI -> VoiceOutputEngine.POCKET
                            VoiceOutputEngine.POCKET -> VoiceOutputEngine.SYSTEM
                            VoiceOutputEngine.SYSTEM -> VoiceOutputEngine.ONLINE
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
                if (engineChoice == VoiceOutputEngine.ONLINE) {
                    NovaSettingsRow(
                        title = "Online voice",
                        subtitle = if (onlineVoice == OnlineTtsVoicePreferences.VOICE_HINDI) {
                            "Hindi (hf_alpha) — Hindi text ke liye"
                        } else {
                            "Bella (af_bella) — warm female, default"
                        },
                        icon = Icons.Default.RecordVoiceOver,
                        onClick = {
                            val next = if (onlineVoice == OnlineTtsVoicePreferences.VOICE_HINDI) {
                                OnlineTtsVoicePreferences.VOICE_BELLA
                            } else {
                                OnlineTtsVoicePreferences.VOICE_HINDI
                            }
                            OnlineTtsVoicePreferences.setVoiceId(context, next)
                            onlineVoice = next
                        }
                    )
                    NovaSettingsRow(
                        title = "Quality",
                        subtitle = if (onlineQuality == OnlineTtsQuality.HIGH.apiValue) {
                            "High (Azure narrator) — aksar fail hota hai, phir Kokoro hi bolta hai"
                        } else {
                            "Low (Kokoro) — tez aur reliable, default"
                        },
                        icon = Icons.Default.GraphicEq,
                        onClick = {
                            val next = if (onlineQuality == OnlineTtsQuality.HIGH.apiValue) {
                                OnlineTtsQuality.LOW.apiValue
                            } else {
                                OnlineTtsQuality.HIGH.apiValue
                            }
                            OnlineTtsQualityPreferences.setQuality(context, next)
                            onlineQuality = next
                        }
                    )
                    Text(
                        text = "Is ghantay mein $onlineQuota/${OnlineTtsEngine.HOURLY_LIMIT} online awazein use hui hain — " +
                            "limit khatam hone par offline voice bolti hai. Dobara sunne gaye jumlay dobara count nahi hote.",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    OnlineTtsEngine.lastFallback?.let { reason ->
                        Text(
                            text = "Aakhri baar high quality nahi mili: $reason",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        NovaButton(
                            text = if (testingOnlineVoice) "Bol rahi hai…" else "Test online voice",
                            onClick = {
                                if (testingOnlineVoice) return@NovaButton
                                if (!OnlineTtsEngine.isOnline(context)) {
                                    onlineTestError = "Internet nahi hai — Wi-Fi ya mobile data on karo."
                                    return@NovaButton
                                }
                                onlineTestError = null
                                testingOnlineVoice = true
                                OnlineTtsEngine.speak(
                                    context,
                                    scope,
                                    "Assalam-o-Alaikum! Main Lia hun — internet wali awaz, insano jaisi, bina key ke.",
                                    onDone = {
                                        testingOnlineVoice = false
                                        onlineQuota = OnlineTtsEngine.quotaUsed(context)
                                    },
                                    onError = { error ->
                                        testingOnlineVoice = false
                                        onlineTestError = error.message
                                            ?: "Online voice nahi bol saki."
                                        onlineQuota = OnlineTtsEngine.quotaUsed(context)
                                    }
                                )
                            }
                        )
                    }
                    onlineTestError?.let { message ->
                        Text(
                            text = message,
                            fontSize = 13.sp,
                            color = colors.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
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
                                    if (!VoiceModelManager.isWifiConnected(context)) {
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
                        if (isReady && (model.id == VoiceModelManager.MODEL_POCKET_TTS.id ||
                                model.id == VoiceModelManager.MODEL_SUPERTONIC_TTS.id)
                        ) {
                            val isHindiModel = model.id == VoiceModelManager.MODEL_SUPERTONIC_TTS.id
                            NovaButton(
                                text = if (testingVoice) "Bol rahi hai…" else "Test voice",
                                onClick = {
                                    if (testingVoice) return@NovaButton
                                    testingVoice = true
                                    scope.launch(Dispatchers.IO) {
                                        if (isHindiModel) {
                                            // V21: Hindi voice test — Roman Urdu
                                            // sentence, transliterated inside
                                            // the engine to Devanagari.
                                            val engine = HindiTtsEngine.create(context)
                                            if (engine == null) {
                                                withContext(Dispatchers.Main) {
                                                    testingVoice = false
                                                    errorById = errorById + (model.id to
                                                        "Voice engine start nahi ho saka.")
                                                }
                                                return@launch
                                            }
                                            engine.speak(
                                                "Assalam-o-Alaikum! Main Lia hun — tumhari apni Hindi awaz, bina internet, bina key ke.",
                                                onDone = {
                                                    engine.release()
                                                    testingVoice = false
                                                },
                                                onError = {
                                                    engine.release()
                                                    testingVoice = false
                                                }
                                            )
                                            return@launch
                                        }
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

                }
            }
        }

        Text(
            text = "Battery: on-device engine phone ka CPU use karta hai — " +
                "lambi baatein battery zyada khati hain. Model sirf tab load " +
                "hota hai jab Lia bol rahi ho; voice session khatam hote hi " +
                "unload ho jata hai.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            text = "Attribution: Hindi voice — Supertonic 3 (MIT, Supertone Inc.) via " +
                "sherpa-onnx (Apache-2.0). Urdu/Hindi sunne wala — Shruti (Apache-2.0, " +
                "UDGAM Labs). Lia voice — Kyutai Pocket TTS (CC-BY-4.0) via " +
                "sherpa-onnx (Apache-2.0). " +
                "Online voice — Kokoro-82M via mesmer.tools (free, no key, 20/hour).",
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
