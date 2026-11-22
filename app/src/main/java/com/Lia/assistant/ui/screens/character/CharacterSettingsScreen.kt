package com.Lia.assistant.ui.screens.character

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.CharacterPreferences
import com.Lia.assistant.ui.character.FloatingCharacterService
import com.Lia.assistant.ui.character.LiaCharacter
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky

/**
 * Standalone "Home character" settings screen (route
 * [CHARACTER_SETTINGS_ROUTE]). NOT wired into SettingsScreen.kt here —
 * the parent adds one row under Settings → Look & feel that navigates
 * here (see the v10-character report for the exact snippet).
 *
 * Honest wording throughout: the character is a 2D illustration whose
 * mouth opens and closes with Lia's Gemini voice. It is never called
 * 3D anywhere on this screen.
 */
const val CHARACTER_SETTINGS_ROUTE = "character_settings"

@Composable
fun CharacterSettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    val settings by CharacterPreferences.observe(context).collectAsState()
    val selected = LiaCharacter.fromId(settings.characterId)

    // Shown when the user refuses the "Draw over other apps" permission
    // that the floating bubble needs.
    var overlayDenied by remember { mutableStateOf(false) }

    // The overlay-permission screen returns RESULT_CANCELED even when
    // the permission was granted, so re-check it instead of trusting
    // the result code.
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            overlayDenied = false
            CharacterPreferences.setShowEverywhere(context, true)
            FloatingCharacterService.start(context)
        } else {
            overlayDenied = true
        }
    }

    fun onShowEverywhereChange(enabled: Boolean) {
        if (!enabled) {
            CharacterPreferences.setShowEverywhere(context, false)
            FloatingCharacterService.stop(context)
            return
        }
        if (Settings.canDrawOverlays(context)) {
            CharacterPreferences.setShowEverywhere(context, true)
            FloatingCharacterService.start(context)
            return
        }
        overlayDenied = false
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            // Some devices have no overlay settings screen to open.
            overlayDenied = true
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
                text = "Home character",
                fontFamily = SerifFontFamily,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }

        Text(
            text = "A 2D illustration — not a 3D model. Her mouth moves in time " +
                "with Lia's Gemini voice while she speaks.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Character picker: tap a portrait to select it immediately.
        NovaSectionHeader(title = "Choose Lia's look")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiaCharacter.values().forEach { character ->
                val isSelected = character == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) colors.accent else colors.surfaceRaised,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            CharacterPreferences.setSelectedCharacterId(context, character.id)
                        }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(character.closedRes),
                        contentDescription = character.displayName,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                        }
                        Text(
                            text = character.displayName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Where she appears.
        NovaSectionHeader(title = "Where she appears")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            elevation = 6.dp
        ) {
            Column {
                NovaSettingsRow(
                    title = "Show on home screen",
                    subtitle = "Replaces the orb on Home. Turn off to keep the orb.",
                    icon = Icons.Default.Home,
                    switch = settings.homeCharacterEnabled,
                    onCheckedChange = {
                        CharacterPreferences.setHomeCharacterEnabled(context, it)
                    }
                )
                NovaSettingsRow(
                    title = "Show her everywhere",
                    subtitle = "A small draggable bubble over other apps. " +
                        "Tap it to talk to Lia. Needs the \"Draw over other apps\" permission.",
                    icon = Icons.Default.Visibility,
                    switch = settings.showEverywhere,
                    onCheckedChange = { onShowEverywhereChange(it) }
                )
                if (overlayDenied) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = "The bubble needs the \"Draw over other apps\" " +
                                "permission, so it stays off. You can allow it any " +
                                "time from Settings → Apps → Lia → Draw over other apps, " +
                                "then turn this back on.",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }

        // Quick link to the overlay permission, for the denied case.
        if (overlayDenied) {
            Spacer(modifier = Modifier.height(8.dp))
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(intent)
                        } catch (_: Exception) {
                            // No overlay settings screen on this device.
                        }
                    },
                elevation = 4.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = "Open \"Draw over other apps\" settings",
                        fontSize = 14.sp,
                        color = colors.textPrimary
                    )
                }
            }
        }
    }
}
