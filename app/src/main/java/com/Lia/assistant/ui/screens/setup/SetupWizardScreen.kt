package com.Lia.assistant.ui.screens.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.ChatEngine
import com.Lia.assistant.data.EnginePreferences
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.SetupWizardPreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaChip
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

/**
 * V16 (P0-3) — first-run setup wizard, 3 steps.
 *
 * Step 1: permissions, each ASKED in sequence (one system dialog per
 * permission — that is how Android works). Step 2: engine choice +
 * Fast/Warm/Smart preset. Step 3: persona + names. Skip is always
 * available, and the wizard is re-runnable from Settings.
 *
 * The honest line is on the screen itself: Android kisi app ko khud se
 * permissions on karne nahi deta — har ek pe user ko Allow dabana parta
 * hai. Lia can only ask; she can never grant anything herself.
 *
 * V19 polish (Maya pattern): one prominent "Sab allow karo" button
 * that fires the system prompts one by one; every permission is its
 * own card with a status chip ("Baaki hai" → "✓ Allowed"); ONLY the
 * microphone gates "Aage badho" — everything else is optional and
 * says so. Each card carries one plain WHY line.
 */
@Composable
fun SetupWizardScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var step by remember { mutableStateOf(0) }

    fun finish() {
        SetupWizardPreferences.setDone(context, true)
        navController.navigate("home") {
            popUpTo("setup_wizard") { inclusive = true }
            launchSingleTop = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top bar: step dots + Skip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Setup — step ${step + 1} of 3",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Skip",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                modifier = Modifier.clickable { finish() }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..2) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (i <= step) colors.accent else colors.surfaceRaised)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        when (step) {
            0 -> WizardPermissionsStep(
                onNext = { step = 1 },
                onOpenBackgroundSetup = { navController.navigate("permissions") }
            )
            1 -> WizardEngineStep(
                onBack = { step = 0 },
                onNext = { step = 2 }
            )
            else -> WizardPersonaStep(
                appState = appState,
                onBack = { step = 1 },
                onFinish = { finish() }
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ------------------------------------------------------------------ step 1

private data class WizardPermission(
    val permission: String,
    val name: String,
    val why: String,
    val applies: Boolean = true,
    /** Only the microphone gates "Aage badho" — the rest are optional. */
    val required: Boolean = false
)

@Composable
private fun WizardPermissionsStep(
    onNext: () -> Unit,
    onOpenBackgroundSetup: () -> Unit
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    // Re-read every permission when the screen resumes — the user may
    // have just granted something in a system dialog.
    var resumeCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = listOf(
        WizardPermission(
            Manifest.permission.RECORD_AUDIO,
            "Microphone",
            "Lia se baat karne ke liye — bina iske Lia sun nahi sakti.",
            required = true
        ),
        WizardPermission(
            Manifest.permission.POST_NOTIFICATIONS,
            "Notifications",
            "Lia ko background mein chalaye rakhne ke liye.",
            applies = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ),
        WizardPermission(
            Manifest.permission.READ_CONTACTS,
            "Contacts",
            "\"Ammi ko call karo\" — naam se number dhoondne ke liye."
        ),
        WizardPermission(
            Manifest.permission.CALL_PHONE,
            "Phone calls",
            "Tumhare liye call karne ke liye — dialer khole baghair direct call."
        ),
        WizardPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "Location (coarse)",
            "Sirf mausam ke liye — sheher ka mausam dikhane ke liye."
        ),
        WizardPermission(
            Manifest.permission.CAMERA,
            "Camera",
            "Jo tum dikhao, use dekhne ke liye."
        )
    ).filter { it.applies }

    fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resumeCount++ }
    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resumeCount++ }

    // Read live status on every resume (resumeCount is a read key).
    @Suppress("UNUSED_VARIABLE")
    val tick = resumeCount

    // Only the microphone gates the continue button.
    val micGranted = isGranted(Manifest.permission.RECORD_AUDIO)
    val allGranted = permissions.all { isGranted(it.permission) }

    Text(
        text = "Permissions",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = colors.textPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Android kisi app ko khud se permission on karne nahi deta — " +
            "har ek pe tumhe Allow dabana parta hai. Ye normal hai. Lia sirf " +
            "pooch sakti hai; kuch bhi khud on nahi kar sakti. Jo chahiye, " +
            "wo do — baaki Skip kar do, baad mein Settings se ho jayega.",
        fontSize = 14.sp,
        color = colors.textSecondary,
        lineHeight = 20.sp
    )
    Spacer(modifier = Modifier.height(16.dp))

    // V19: one prominent button — Android then shows the system
    // prompts one by one, in order. Tapping it twice asks only what
    // is still missing.
    NovaButton(
        text = "Sab allow karo",
        onClick = {
            val missing = permissions
                .map { it.permission }
                .filter { !isGranted(it) }
                .toTypedArray()
            if (missing.isNotEmpty()) multiLauncher.launch(missing)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !allGranted
    )
    Spacer(modifier = Modifier.height(16.dp))

    // One card per permission, with a status chip. Tapping the chip
    // asks for that one permission only.
    for (item in permissions) {
        val granted = isGranted(item.permission)
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (item.required) "Zaroori" else "Optional",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.required) colors.accent else colors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.why,
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                NovaChip(
                    text = if (granted) "✓ Allowed" else "Baaki hai",
                    selected = granted,
                    onClick = {
                        if (!granted) singleLauncher.launch(item.permission)
                    }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    NovaInfoCard(
        title = "Background setup (battery / autostart)",
        body = "Ye system settings se hota hai — wahan ja kar battery " +
            "\"No restrictions\" aur autostart on karo, warna phone Lia ko " +
            "background mein band kar deta hai.",
        onClick = onOpenBackgroundSetup
    )
    Spacer(modifier = Modifier.height(20.dp))
    NovaButton(
        text = "Aage badho",
        onClick = onNext,
        enabled = micGranted
    )
    if (!micGranted) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Aage badhne ke liye microphone chahiye — upar " +
                "\"Sab allow karo\" dabao, phir mic pe Allow karo.",
            fontSize = 13.sp,
            color = colors.textSecondary
        )
    }
}

// ------------------------------------------------------------------ step 2

@Composable
private fun WizardEngineStep(
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var engine by remember { mutableStateOf(EnginePreferences.getEngine(context)) }
    val hasKey = remember { ApiKeyStore.hasKey(context) }

    Text(
        text = "Engine",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = colors.textPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Kaun jawab de? Sab free hain — Pollinations ko koi key nahi " +
            "chahiye, Gemini ko tumhari apni key chahiye.",
        fontSize = 14.sp,
        color = colors.textSecondary,
        lineHeight = 20.sp
    )
    Spacer(modifier = Modifier.height(16.dp))

    val options = listOf(
        Triple(
            ChatEngine.POLLINATIONS,
            "Pollinations — free, no key",
            "Online, app khulte hi kaam karta hai."
        ),
        Triple(
            ChatEngine.GEMINI,
            "Gemini (your key)",
            if (hasKey) "Behtareen quality, tumhari free key."
            else "Key chahiye — Settings > Gemini API Key se add karo."
        )
    )
    for ((value, title, body) in options) {
        val selected = engine == value
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    EnginePreferences.setEngine(context, value)
                    engine = value
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.accent else colors.surfaceRaised),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Text(text = "✓", fontSize = 12.sp, color = colors.onAccent)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    Text(text = body, fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaButton(text = "Back", onClick = onBack, type = NovaButtonType.SECONDARY)
        NovaButton(text = "Next — persona", onClick = onNext)
    }
}

// ------------------------------------------------------------------ step 3

@Composable
private fun WizardPersonaStep(
    appState: NovaAppState,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var personality by remember { mutableStateOf(appState.personality) }
    var userName by remember { mutableStateOf(appState.userName) }
    var assistantName by remember { mutableStateOf(appState.assistantName) }

    Text(
        text = "Persona",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = colors.textPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Lia kaisi lage? Girlfriend default hai — kabhi bhi Settings " +
            "se badal sakte ho.",
        fontSize = 14.sp,
        color = colors.textSecondary,
        lineHeight = 20.sp
    )
    Spacer(modifier = Modifier.height(16.dp))

    for (p in Personality.values()) {
        val selected = personality == p
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { personality = p }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.accent else colors.surfaceRaised),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Text(text = "✓", fontSize = 12.sp, color = colors.onAccent)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "${p.icon} ${p.displayName}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = colors.textPrimary
                    )
                    Text(text = p.description, fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Tumhara naam",
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    NovaTextField(
        value = userName,
        onValueChange = { userName = it },
        placeholder = "Friend"
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Assistant ka naam",
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    NovaTextField(
        value = assistantName,
        onValueChange = { assistantName = it },
        placeholder = "Lia"
    )
    Spacer(modifier = Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaButton(text = "Back", onClick = onBack, type = NovaButtonType.SECONDARY)
        NovaButton(
            text = "Finish — start Lia",
            onClick = {
                appState.applyPersonality(context, personality)
                appState.applyUserName(context, userName)
                appState.applyAssistantName(context, assistantName)
                onFinish()
            }
        )
    }
}
