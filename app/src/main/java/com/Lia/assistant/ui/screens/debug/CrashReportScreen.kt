package com.Lia.assistant.ui.screens.debug

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.CrashReportStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

/**
 * V16 (P1-1) — the warm next-launch crash screen.
 *
 * Shown automatically when Lia crashed last time (see NovaApp's start
 * destination). No stack trace thrown at the user: one warm line, the
 * newest report's short summary (already redacted at write time — no
 * URLs, no key-like values), Share to send it, and "Clear and open Lia"
 * to wipe the reports and carry on. Also reachable from Settings, so
 * the user can always see whether Lia ever crashed.
 */
@Composable
fun CrashReportScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var crashes by remember { mutableStateOf(CrashReportStore.all(context)) }
    val latest = crashes.firstOrNull()

    LaunchedEffect(Unit) {
        CrashReportStore.markSeen(context)
    }

    fun openHome() {
        navController.navigate("home") {
            popUpTo("crash_report") { inclusive = true }
            launchSingleTop = true
        }
    }

    fun shareLatest() {
        val file = latest ?: return
        val text = runCatching { file.readText() }.getOrDefault("(report unreadable)")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Lia AI crash report — ${file.name}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share crash report"))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Oops — Lia last time crash ho gayi thi",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (latest != null) {
                "Koi baat nahi — ye report phone par hi save hui hai, kahi " +
                    "upload nahi hui. Share karo to maira dekh sakti hai ke kya " +
                    "hua tha, ya clear karke aage badho."
            } else {
                "Lia abhi tak kabhi crash nahi hui — sab theek hai. 🎉"
            },
            fontSize = 14.sp,
            color = colors.textSecondary,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (latest != null) {
            NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = latest.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = CrashReportStore.summary(latest),
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
            if (crashes.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Aur ${crashes.size - 1} purani report(s) bhi hain — " +
                        "clear karne se sab delete ho jayengi.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovaButton(text = "Share report", onClick = { shareLatest() })
                NovaButton(
                    text = "Clear and open Lia",
                    onClick = {
                        CrashReportStore.clearAll(context)
                        crashes = emptyList()
                        openHome()
                    },
                    type = NovaButtonType.SECONDARY
                )
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            NovaButton(text = "Open Lia", onClick = { openHome() })
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}
