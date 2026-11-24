package com.goldai.assistant.ui.screens.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.goldai.assistant.action.UsageStatsHelper
import com.goldai.assistant.notification.NotificationStore
import com.goldai.assistant.ui.components.NovaButton
import com.goldai.assistant.ui.components.NovaButtonType
import com.goldai.assistant.ui.components.NovaGlassCard
import com.goldai.assistant.ui.theme.LocalNovaColors
import com.goldai.assistant.ui.theme.nightSky

/**
 * V14 — the setup wizard: permissions + background survival + device
 * control, all in one place.
 *
 * Every row re-reads its real state when the screen resumes (the user
 * just came back from a system settings screen), so "Granted" is never
 * stale. Steps that Android cannot check (Infinix autostart) say so
 * plainly instead of faking a green tick.
 *
 * Notification access is special: the prominent in-app disclosure
 * (what Lia reads, why, and that nothing leaves the phone) is shown
 * FIRST, and the system settings screen opens only after the user taps
 * "I understand". That order is a Play Store requirement, not a nicety.
 */
@Composable
fun PermissionsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    // Re-read every permission when the screen resumes — the user may
    // have just granted something in a system settings screen.
    var resumeCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun hasRuntimePermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    val hasMic by remember(resumeCount) {
        mutableStateOf(hasRuntimePermission(Manifest.permission.RECORD_AUDIO))
    }
    val hasContacts by remember(resumeCount) {
        mutableStateOf(hasRuntimePermission(Manifest.permission.READ_CONTACTS))
    }
    val hasCall by remember(resumeCount) {
        mutableStateOf(hasRuntimePermission(Manifest.permission.CALL_PHONE))
    }
    val batteryOptimizedIgnored by remember(resumeCount) {
        mutableStateOf(
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
    }
    val hasNotificationAccess by remember(resumeCount) {
        mutableStateOf(NotificationStore.isEnabled(context))
    }
    val hasDndAccess by remember(resumeCount) {
        mutableStateOf(
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .isNotificationPolicyAccessGranted
        )
    }
    val canWriteSettings by remember(resumeCount) {
        mutableStateOf(Settings.System.canWrite(context))
    }
    val hasUsageAccess by remember(resumeCount) {
        mutableStateOf(UsageStatsHelper.isGranted(context))
    }

    var showNotificationDisclosure by remember { mutableStateOf(false) }

    val reqMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val reqContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val reqCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun openSystemSettings(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).apply {
            if (withPackage) data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                text = "Permissions",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PermissionItem(
                    name = "Microphone",
                    description = "Required for real-time full-duplex voice conversation",
                    isGranted = hasMic,
                    onClick = { reqMic.launch(Manifest.permission.RECORD_AUDIO) }
                )

                PermissionItem(
                    name = "Contacts",
                    description = "Enables voice-driven contact resolution and messaging",
                    isGranted = hasContacts,
                    onClick = { reqContacts.launch(Manifest.permission.READ_CONTACTS) }
                )

                PermissionItem(
                    name = "Direct Phone Call",
                    description = "Allows calling contacts directly without opening dialer",
                    isGranted = hasCall,
                    onClick = { reqCall.launch(Manifest.permission.CALL_PHONE) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // V14 — background survival: without these, the phone kills Lia
        // in the background no matter how good the code is.
        Text(
            text = "Background (always listening)",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PermissionItem(
                    name = "Battery optimization",
                    description = "Let Lia run in the background — pick Lia in the list " +
                        "and choose 'Don't optimize' (Play-safe settings list).",
                    isGranted = batteryOptimizedIgnored,
                    onClick = {
                        openSystemSettings(
                            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        )
                    }
                )

                PermissionItem(
                    name = "Autostart & battery (Infinix / XOS)",
                    description = "Autostart ON karo, Battery: 'No restrictions', aur " +
                        "recents mein Lia ko lock karo — warna phone usko band " +
                        "kar deta hai. (Ye step check nahi ho sakta — khud karna hota hai.)",
                    isGranted = false,
                    onClick = {
                        openSystemSettings(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            withPackage = true
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // V14 — device control: each row explains itself and opens the
        // exact system screen that grants it.
        Text(
            text = "Device control",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PermissionItem(
                    name = "Notifications (read + reply)",
                    description = "Lia tumhari notifications parh sake aur jab tum bolo " +
                        "to reply kar sake. Pehle ek explanation dikhegi.",
                    isGranted = hasNotificationAccess,
                    onClick = {
                        if (!hasNotificationAccess) {
                            showNotificationDisclosure = true
                        }
                    }
                )

                PermissionItem(
                    name = "Do Not Disturb access",
                    description = "Lia Do Not Disturb on/off kar sake.",
                    isGranted = hasDndAccess,
                    onClick = {
                        openSystemSettings(
                            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                        )
                    }
                )

                PermissionItem(
                    name = "Modify system settings (brightness)",
                    description = "Lia screen brightness set kar sake.",
                    isGranted = canWriteSettings,
                    onClick = {
                        openSystemSettings(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            withPackage = true
                        )
                    }
                )

                PermissionItem(
                    name = "Usage access (screen time)",
                    description = "Lia bata sake konsi app khuli hai aur aaj kitni der chali.",
                    isGranted = hasUsageAccess,
                    onClick = {
                        openSystemSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    openSystemSettings(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        withPackage = true
                    )
                }
        ) {
            Text(
                text = "Open System App Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = colors.accent
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // V14 — prominent disclosure for notification access (Play Store
    // requirement). Shown BEFORE the system settings screen opens, and
    // only on the user's explicit "I understand".
    if (showNotificationDisclosure) {
        AlertDialog(
            onDismissRequest = { showNotificationDisclosure = false },
            title = {
                Text(
                    text = "Notifications parhna",
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Lia tumhari notifications is liye parhna chahti hai:\n\n" +
                        "• 'Mere notifications parho' — jo aaya hai, wo suna sake.\n" +
                        "• 'WhatsApp ko reply karo' — tumhare bole hue words usi app " +
                        "ke reply button se bhej sake.\n\n" +
                        "Notifications sirf is phone ki memory mein rehti hain — " +
                        "kahi save nahi hoti, kahi upload nahi hoti. Tum kabhi bhi " +
                        "Settings se ye access band kar sakte ho.",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                NovaButton(
                    text = "I understand — continue",
                    onClick = {
                        showNotificationDisclosure = false
                        openSystemSettings(
                            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                        )
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showNotificationDisclosure = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}

@Composable
private fun PermissionItem(
    name: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalNovaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (isGranted) colors.lagoon else colors.error)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = colors.textSecondary
            )
        }
        Text(
            text = if (isGranted) "Granted" else "Grant",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isGranted) colors.lagoon else colors.accent
        )
    }
}
