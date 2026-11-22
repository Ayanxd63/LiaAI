package com.Lia.assistant.ui.screens.huggingface

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import com.Lia.assistant.data.HuggingFaceStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VideoSpacesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Hugging Face (v11).
 *
 * One field: the user's own FREE Hugging Face access token, used ONLY
 * for video generation on public Spaces (Create Video). The token is
 * never logged and never leaves the phone except in the Authorization
 * header of Hugging Face calls.
 *
 * Why it is needed (verified live 2026-11-22, not assumed): without a
 * token, Hugging Face's ZeroGPU scheduler rejects video jobs outright —
 * the exact error is "You have exceeded your ZeroGPU quota (300s
 * requested vs. 0s left). Authenticate with a Hugging Face token for
 * more quota". A free account token gives about 5 GPU-minutes a day,
 * roughly 1 short video a day on the CogVideoX Spaces. The token costs
 * nothing and needs no payment: huggingface.co → Settings → Access
 * Tokens → New token (read).
 *
 * The Test button calls GET /api/whoami with the token and shows the
 * account name when it works, so the user knows immediately whether
 * the token is good — no guessing, no silent failure later.
 */
@Composable
fun HuggingFaceSettingsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tokenInput by remember { mutableStateOf(HuggingFaceStore.getToken(context)) }
    var tokenSaved by remember { mutableStateOf(HuggingFaceStore.hasToken(context)) }
    var tokenVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun openInBrowser(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            statusMessage = "Couldn't open the browser. Go to $url manually."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
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
                text = "Hugging Face",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = colors.textPrimary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- What it is for ------------------------------------------------
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                Text(
                    text = "Free video, using your own Hugging Face token.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create Video makes clips on free public Hugging Face " +
                        "Spaces (CogVideoX). Those Spaces need a token — without " +
                        "one, Hugging Face itself rejects the job. The token is " +
                        "free and needs no payment: make a free account on " +
                        "huggingface.co, then Settings → Access Tokens → " +
                        "New token (read), and paste it below. Your token stays " +
                        "on this phone only.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Free limit: about 5 GPU-minutes a day — roughly 1 short " +
                        "video a day. Videos are about 6 seconds and take a few " +
                        "minutes in the queue.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }

        // ---- Token ---------------------------------------------------------
        NovaSectionHeader(title = "Access Token")
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            elevation = 4.dp
        ) {
            Column {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = { Text("hf_...", color = colors.textTertiary) },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (tokenVisible) "Hide token" else "Show token",
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
                            HuggingFaceStore.saveToken(context, tokenInput)
                            tokenSaved = HuggingFaceStore.hasToken(context)
                            statusMessage = if (tokenSaved) {
                                "Token saved on this device."
                            } else {
                                "Token cleared."
                            }
                        }
                    )
                    NovaButton(
                        text = if (isTesting) "Testing…" else "Test",
                        onClick = {
                            if (tokenInput.isBlank()) {
                                statusMessage = "Paste your Hugging Face token first."
                                return@NovaButton
                            }
                            HuggingFaceStore.saveToken(context, tokenInput)
                            tokenSaved = true
                            isTesting = true
                            scope.launch(Dispatchers.IO) {
                                val result = VideoSpacesClient.validateToken(tokenInput)
                                withContext(Dispatchers.Main) {
                                    isTesting = false
                                    statusMessage = result.fold(
                                        onSuccess = { name ->
                                            "Token works — signed in as $name."
                                        },
                                        onFailure = { error ->
                                            error.message
                                                ?: "That token did not work. Check it and try again."
                                        }
                                    )
                                }
                            }
                        },
                        type = NovaButtonType.SECONDARY,
                        enabled = !isTesting
                    )
                }
                if (tokenSaved) {
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaButton(
                        text = "Remove token",
                        onClick = {
                            HuggingFaceStore.clearToken(context)
                            tokenInput = ""
                            tokenSaved = false
                            statusMessage = "Token removed. Video generation is off until you add one again."
                        },
                        type = NovaButtonType.TEXT
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(
                    text = "Get a token on huggingface.co",
                    onClick = { openInBrowser("https://huggingface.co/settings/tokens") },
                    type = NovaButtonType.TEXT
                )
                Text(
                    text = "On the site: sign in → Settings → Access Tokens → " +
                        "New token → type: Read → Create → copy the token " +
                        "(it starts with hf_) and paste it here.",
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

        Spacer(modifier = Modifier.height(32.dp))
    }
}
