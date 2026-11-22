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
import androidx.compose.material.icons.filled.OfflineBolt
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
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.OfflineLlmClient
import com.Lia.assistant.voice.OfflineModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V15 — offline AI model manager (Settings → Offline AI).
 *
 * The on-device model is NEVER bundled in the APK. It downloads once,
 * on Wi-Fi only, with resume + SHA-256 verification, into the app's
 * external files dir. First model: Qwen2.5-0.5B-Instruct Q4_K_M
 * (~0.46 GB, Apache-2.0). The model auto-unloads after ~5 min idle
 * and on voice stop, so it does not hold RAM or battery hostage.
 *
 * Battery note (honest): on-device inference uses the CPU hard — a
 * long offline chat drains the battery faster than an online one.
 * Short replies are quick; that is what the offline engine is for.
 */
@Composable
fun OfflineModelScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val model = OfflineModelManager.MODEL_QWEN_0_5B

    var isReady by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(OfflineModelManager.downloadedBytes(context)) }
    var isWifi by remember { mutableStateOf(OfflineModelManager.isWifiConnected(context)) }
    var freeRamBytes by remember { mutableStateOf(OfflineModelManager.freeRamBytes(context)) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        downloadedBytes = OfflineModelManager.downloadedBytes(context)
        isWifi = OfflineModelManager.isWifiConnected(context)
        freeRamBytes = OfflineModelManager.freeRamBytes(context)
        // The ready check hashes ~0.5 GB — always on IO, never Main.
        scope.launch(Dispatchers.IO) {
            isReady = OfflineModelManager.isModelReady(context)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val sizeMb = model.sizeBytes / (1024 * 1024)
    val downloadedMb = downloadedBytes / (1024 * 1024)
    val blockedReason = OfflineModelManager.loadBlockedReason(context, model)
    val engineAvailable = OfflineLlmClient.isAvailable(context)

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
                text = "Offline AI",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Internet na ho to bhi Lia jawab de — phone ke andar chhota AI model. " +
                "Pehli dafa Wi-Fi par download hota hai (~$sizeMb MB), phir hamesha phone par rehta hai.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

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
                        isReady -> "Downloaded — ready to use"
                        downloadedBytes > 0L -> "Paused at $downloadedMb MB / $sizeMb MB — tap Download to resume"
                        else -> "Not downloaded — $sizeMb MB, Wi-Fi only"
                    },
                    icon = Icons.Default.OfflineBolt,
                    onClick = null
                )

                if (downloading) {
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

                if (!engineAvailable) {
                    Text(
                        text = "Offline AI is phone par nahi chal sakta — ye phone ka processor " +
                            "support nahi karta. Online engine use karo.",
                        fontSize = 13.sp,
                        color = colors.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (blockedReason != null) {
                    Text(
                        text = blockedReason,
                        fontSize = 13.sp,
                        color = colors.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (!isReady && engineAvailable) {
                        NovaButton(
                            text = if (downloadedBytes > 0L) "Resume download" else "Download on Wi-Fi",
                            onClick = {
                                if (downloading) return@NovaButton
                                if (!OfflineModelManager.isWifiConnected(context)) {
                                    error = "Offline model sirf Wi-Fi par download hota hai — " +
                                        "Wi-Fi on kar ke dobara try karo."
                                    return@NovaButton
                                }
                                error = null
                                downloading = true
                                progress = if (model.sizeBytes > 0) {
                                    downloadedBytes.toFloat() / model.sizeBytes
                                } else {
                                    0f
                                }
                                scope.launch(Dispatchers.IO) {
                                    val result = OfflineModelManager.downloadModel(context, model) { p ->
                                        scope.launch { progress = p }
                                    }
                                    withContext(Dispatchers.Main) {
                                        downloading = false
                                        progress = 0f
                                        result.onSuccess {
                                            refresh()
                                        }.onFailure { e ->
                                            error = e.localizedMessage
                                                ?: "Download nahi ho saka — dobara try karo."
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
                            onClick = { showDeleteConfirm = true },
                            type = NovaButtonType.SECONDARY
                        )
                    }
                }
            }
        }

        Text(
            text = "Free RAM abhi: ${freeRamBytes / (1024 * 1024)} MB. " +
                "Offline model load hote waqt ~0.7 GB chahiye; 5 minute use na ho " +
                "to khud unload ho jata hai. Battery: lambi offline baatein battery " +
                "zyada khati hain — chhote jawab ke liye best hai.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Text(
            text = "Image banane ke liye internet chahiye — offline sirf chat aur voice " +
                "ke jawab deta hai.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = "Delete offline model?", color = colors.textPrimary) },
            text = {
                Text(
                    text = "Model delete ho jayega (~$sizeMb MB free). Dobara use karne ke " +
                        "liye Wi-Fi par dobara download karna hoga.",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Delete",
                    onClick = {
                        OfflineModelManager.deleteModel(context, model)
                        OfflineLlmClient.unloadNow()
                        refresh()
                        showDeleteConfirm = false
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showDeleteConfirm = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}
