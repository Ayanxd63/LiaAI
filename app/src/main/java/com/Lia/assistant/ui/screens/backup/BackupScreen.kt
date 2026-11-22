package com.Lia.assistant.ui.screens.backup

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.BackupManager
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * V16 (P1-10) — one-JSON backup: chat + facts + reminders.
 *
 * One tap writes a single JSON file to Downloads
 * (`lia-backup-yyyyMMdd-HHmmss.json`) and offers Share. The file holds
 * every saved chat, every memory fact, and every reminder.
 *
 * What is NEVER in the backup: API keys, tokens, passwords, or any
 * credential — the exporter never reads the credential stores. Import
 * is P2 and deliberately NOT built in V16; the screen says so plainly
 * instead of showing a dead button.
 */
@Composable
fun BackupScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lastFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun shareFile(file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, BackupManager.shareUri(context, file))
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
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
                text = "Backup",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        NovaInfoCard(
            title = "Kya backup hota hai?",
            body = "Ek JSON file: saari saved chats, saari memory facts, aur " +
                "saare reminders. Downloads folder mein save hoti hai.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        NovaInfoCard(
            title = "Kya backup NAHI hota?",
            body = "API keys, tokens ya passwords — kabhi nahi. Backup mein " +
                "sirf tumhari chats aur yaadein hoti hain, koi credential nahi.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        NovaInfoCard(
            title = "Import?",
            body = "Import V16 mein nahi hai — backup file ko safe jagah " +
                "save kar lo, import baad ke version mein aayega.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NovaButton(
                text = if (busy) "Bana rahi hun…" else "Export backup",
                onClick = {
                    if (busy) return@NovaButton
                    busy = true
                    error = null
                    scope.launch(Dispatchers.IO) {
                        val file = BackupManager.export(context)
                        withContext(Dispatchers.Main) {
                            busy = false
                            if (file != null) {
                                lastFile = file
                            } else {
                                error = "Backup nahi ban saka — storage check kar " +
                                    "ke dobara try karo."
                            }
                        }
                    }
                }
            )
            lastFile?.let { file ->
                NovaButton(
                    text = "Share",
                    onClick = { shareFile(file) },
                    type = NovaButtonType.SECONDARY
                )
            }
        }
        lastFile?.let { file ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Backup ban gaya: ${file.name}\nDownloads folder mein save hai.",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        error?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
