package com.Lia.assistant.ui.screens.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.DeviceTtsVoicePreferences
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.CuteVoice

/**
 * V16 (P0-5) — the voice picker, with Maya-style honesty about what
 * each voice really is.
 *
 * Section (a) — device TTS voices: REAL voices on this phone, listed
 * from the system TextToSpeech engine. Tap one to hear a preview, tap
 * "Use this voice" to make it Lia's speaking voice (no key needed).
 *
 * Section (b) — Gemini Live voice names: the names the app already
 * uses (Aoede, Puck, Charon, Kore, Fenrir — the old Settings cycle —
 * plus Sulafat, the Girlfriend's Live voice). These are NAMES only:
 * the real Live voice is heard only in a Gemini voice chat, when a key
 * is saved. The preview here is always the device TTS voice — the
 * footnote says so plainly. No Live voice ID is invented: every name
 * listed already exists in the app's code.
 */
@Composable
fun GeminiVoicePickerScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val hasKey = remember { ApiKeyStore.hasKey(context) }

    var ttsReady by remember { mutableStateOf(false) }
    var deviceVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var chosenDeviceVoice by remember {
        mutableStateOf(DeviceTtsVoicePreferences.getVoiceName(context))
    }
    var previewingVoice by remember { mutableStateOf<String?>(null) }

    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    DisposableEffect(Unit) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val voices = try {
                    ttsInstance?.voices?.toList() ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                // English + Urdu voices only — the languages Lia speaks.
                deviceVoices = voices.filter {
                    it.locale.language == "en" || it.locale.language == "ur"
                }.sortedWith(
                    compareByDescending<Voice> {
                        it.name.contains("female", ignoreCase = true)
                    }.thenBy { it.name }
                )
            }
        }
        tts = ttsInstance
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    fun previewWithVoice(voice: Voice?) {
        val engine = tts ?: return
        if (!ttsReady) return
        try {
            if (voice != null) {
                engine.voice = voice
                engine.setPitch(CuteVoice.PITCH)
                engine.setSpeechRate(CuteVoice.RATE)
            } else {
                CuteVoice.applyCuteVoice(engine)
            }
            previewingVoice = voice?.name ?: "auto"
            engine.speak(
                "Hello! Main Lia hun, tumhari apni dost.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "lia-voice-preview"
            )
        } catch (_: Exception) {
        }
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
                text = "Voice picker",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        // ---- Section (a): device TTS voices (real, previewable, no key)
        NovaSectionHeader(title = "On this phone — device voices (no key)")
        Text(
            text = "Ye tumhare phone ki asli awazein hain — tap karo to suno, " +
                "\"Use this voice\" dabao to Lia isi awaz mein bole.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Auto: Lia's cute voice (best female system voice).
                DeviceVoiceRow(
                    name = "Lia's cute voice (auto)",
                    detail = "Best female system voice, pitch 1.2 — default",
                    selected = chosenDeviceVoice.isBlank(),
                    isPreviewing = previewingVoice == "auto",
                    onPreview = { previewWithVoice(null) },
                    onSelect = {
                        DeviceTtsVoicePreferences.setVoiceName(context, "")
                        chosenDeviceVoice = ""
                    }
                )
                if (!ttsReady) {
                    Text(
                        text = "Voices load ho rahi hain…",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                } else if (deviceVoices.isEmpty()) {
                    Text(
                        text = "Is phone mein koi English/Urdu TTS voice nahi mili — " +
                            "auto voice hi use hogi.",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
                for (voice in deviceVoices.take(12)) {
                    DeviceVoiceRow(
                        name = voice.name,
                        detail = "${voice.locale.displayName} · " +
                            if (voice.quality >= Voice.QUALITY_HIGH) "high quality" else "normal",
                        selected = chosenDeviceVoice == voice.name,
                        isPreviewing = previewingVoice == voice.name,
                        onPreview = { previewWithVoice(voice) },
                        onSelect = {
                            DeviceTtsVoicePreferences.setVoiceName(context, voice.name)
                            chosenDeviceVoice = voice.name
                        }
                    )
                }
            }
        }

        // ---- Section (b): Gemini Live voice names (key-only)
        NovaSectionHeader(title = "Gemini Live voices (key needed)")
        Text(
            text = "Ye sirf naam hain — asli Gemini Live awaz sirf Gemini key ke " +
                "saath voice chat mein sunai deti hai. Preview hamesha device " +
                "TTS se hota hai, Live voice se nahi.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasKey) {
                    NovaInfoCard(
                        title = "Gemini key nahi hai",
                        body = "Live voices ke liye key chahiye — Settings > Gemini API " +
                            "Key se apni free key add karo. Naam phir bhi chun sakte ho; " +
                            "awaz key lagte hi badal jayegi."
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                for (voiceName in GEMINI_LIVE_VOICES) {
                    val selected = appState.voiceName == voiceName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { appState.applyVoiceName(context, voiceName) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = voiceName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (voiceName == "Sulafat") {
                                    "Girlfriend's Live voice — warm female"
                                } else {
                                    "Gemini Live voice — key ke saath"
                                },
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                        Text(
                            text = if (selected) "✓ Selected" else "Select",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) colors.lagoon else colors.accent
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private val GEMINI_LIVE_VOICES = listOf(
    "Aoede", "Puck", "Charon", "Kore", "Fenrir", "Sulafat"
)

@Composable
private fun DeviceVoiceRow(
    name: String,
    detail: String,
    selected: Boolean,
    isPreviewing: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit
) {
    val colors = LocalNovaColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = colors.textPrimary
            )
            Text(
                text = detail,
                fontSize = 12.sp,
                color = colors.textSecondary
            )
        }
        Text(
            text = if (isPreviewing) "Playing…" else "Preview",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier
                .clickable { onPreview() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            text = if (selected) "✓ Using" else "Use",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) colors.lagoon else colors.accent,
            modifier = Modifier
                .clickable { onSelect() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
