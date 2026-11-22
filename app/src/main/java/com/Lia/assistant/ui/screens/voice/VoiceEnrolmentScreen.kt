package com.Lia.assistant.ui.screens.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaChipRow
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.MicRecorder
import com.Lia.assistant.voice.SpeakerVerifier
import com.Lia.assistant.voice.VoiceEnrolmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V16 (P1-5) — Voice Guardian-style enrolment for "Hey Lia".
 *
 * 3 spoken phrases, each confirmed by on-device speech recognition
 * (the same Android SpeechRecognizer the no-key voice path uses):
 * say the phrase, Lia checks she heard "Lia" in it, and the phrase
 * ticks ✓. A role tag (Owner / Family / Friend) is stored with it,
 * and "Test my voice" re-checks one phrase on demand.
 *
 * The disclosure is on the screen itself: this is wake-word gating,
 * NOT biometric verification. The on-device spotter matches the WORDS
 * "Hey Lia" — it cannot tell whose voice said them, so anyone saying
 * "Hey Lia" can still wake Lia. Enrolment is practice + a role tag,
 * not a lock.
 */
@Composable
fun VoiceEnrolmentScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    var confirmed by remember {
        mutableStateOf(VoiceEnrolmentStore.confirmedCount(context))
    }
    var role by remember { mutableStateOf(VoiceEnrolmentStore.getRole(context)) }
    var enrolled by remember { mutableStateOf(VoiceEnrolmentStore.isEnrolled(context)) }
    var listeningFor by remember { mutableStateOf<Int?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // V17 item 3 — speaker voice-match (ERes2Net embedding). The model
    // check hashes ~38 MB, so it runs on IO, never Main.
    val enrolScope = rememberCoroutineScope()
    var speakerReady by remember { mutableStateOf(false) }
    var voiceEnrolled by remember { mutableStateOf(SpeakerVerifier.isEnrolled(context)) }
    var voiceMatchResult by remember { mutableStateOf<String?>(null) }
    var recordingVoice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        speakerReady = withContext(Dispatchers.IO) {
            SpeakerVerifier.isReady(context)
        }
    }

    /**
     * Record a 3-second voice sample and either enrol it (save the
     * embedding as the profile) or compare it with the profile.
     * Only the embedding vector is stored — the recording itself is
     * never saved.
     */
    fun recordVoiceSample(forEnrol: Boolean) {
        if (!hasMic) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (recordingVoice) return
        recordingVoice = true
        voiceMatchResult = null
        errorText = null
        enrolScope.launch(Dispatchers.IO) {
            val samples = MicRecorder.captureSeconds(context, 3)
            if (samples == null) {
                withContext(Dispatchers.Main) {
                    recordingVoice = false
                    errorText = "Mic se awaz nahi aayi — dobara try karo."
                }
                return@launch
            }
            val verifier = SpeakerVerifier.create(context)
            if (verifier == null) {
                withContext(Dispatchers.Main) {
                    recordingVoice = false
                    errorText = "Voice-match engine start nahi ho saka."
                }
                return@launch
            }
            try {
                val embedding = verifier.embed(samples)
                if (embedding == null) {
                    withContext(Dispatchers.Main) {
                        recordingVoice = false
                        errorText = "Awaz saaf nahi aayi — 3 second saaf bolo, dobara try karo."
                    }
                    return@launch
                }
                if (forEnrol) {
                    verifier.saveProfile(context, embedding)
                    withContext(Dispatchers.Main) {
                        recordingVoice = false
                        voiceEnrolled = true
                        voiceMatchResult = "Voice sample save ho gaya ✓ — ab \"Test voice-match\" se check karo."
                    }
                } else {
                    val profile = SpeakerVerifier.loadProfile(context)
                    val score = if (profile != null) {
                        SpeakerVerifier.cosineSimilarity(profile, embedding)
                    } else {
                        -1f
                    }
                    withContext(Dispatchers.Main) {
                        recordingVoice = false
                        voiceMatchResult = when {
                            profile == null -> "Pehle voice sample enrol karo — koi profile nahi hai."
                            score < 0f -> "Score nahi nikal saka — dobara try karo."
                            score >= SpeakerVerifier.MATCH_THRESHOLD ->
                                "Match score: ${"%.2f".format(score)} — ye tumhari awaz lagti hai ✓"
                            else ->
                                "Match score: ${"%.2f".format(score)} — ye enrolled awaz nahi lagti."
                        }
                    }
                }
            } finally {
                verifier.release()
            }
        }
    }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    var recognizer: SpeechRecognizer? by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun stopListening() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listeningFor = null
    }

    /**
     * Listen once for [phraseIndex] (0–2 = the enrolment phrases,
     * -1 = the free "Test my voice" check). Success = the recognised
     * text contains "lia".
     */
    fun listenOnce(phraseIndex: Int) {
        if (!hasMic) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            errorText = "Is phone mein speech recognition nahi hai."
            return
        }
        errorText = null
        testResult = null
        // Stop any previous recognizer FIRST — stopListening clears
        // listeningFor, so the new value is set after it.
        stopListening()
        listeningFor = phraseIndex
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                val matched = heard.lowercase().contains("lia")
                if (phraseIndex >= 0) {
                    if (matched && phraseIndex == confirmed) {
                        val next = (confirmed + 1)
                            .coerceAtMost(VoiceEnrolmentStore.PHRASES.size)
                        confirmed = next
                        VoiceEnrolmentStore.setConfirmedCount(context, next)
                        if (next >= VoiceEnrolmentStore.PHRASES.size) {
                            enrolled = true
                            VoiceEnrolmentStore.setEnrolled(context, true)
                        }
                    } else if (!matched) {
                        errorText = "Suna nahi — \"$heard\" suna, \"Lia\" nahi. Dobara bolo."
                    }
                } else {
                    testResult = if (matched) {
                        "Suna! ✓ Lia ne \"$heard\" suna — wake word kaam karega."
                    } else {
                        "Nahi suna — \"$heard\" suna. Saaf bolo: \"Hey Lia\"."
                    }
                }
                stopListening()
            }

            override fun onError(error: Int) {
                errorText = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Kuch suna nahi — dobara bolo."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Time khatam — jaldi bolo."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission chahiye."
                    else -> "Sun nahi saki — dobara try karo."
                }
                stopListening()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { rec.startListening(intent) }
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
                text = "Voice enrolment",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Lia ko apni awaz se \"Hey Lia\" bolna sikhao — 3 phrases, " +
                "ek ek kar ke.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        NovaInfoCard(
            title = "Ye biometric verification NAHI hai",
            body = "Lia sirf WORDS sunti hai — \"Hey Lia\" kis ne bola, ye wo " +
                "nahi pehchan sakti. Koi bhi \"Hey Lia\" bol kar use jaga " +
                "sakta hai. Enrolment practice + role tag hai, lock nahi.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Progress: $confirmed / ${VoiceEnrolmentStore.PHRASES.size}" +
                if (enrolled) " — Enrolled ✓" else "",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        VoiceEnrolmentStore.PHRASES.forEachIndexed { index, phrase ->
            val done = index < confirmed
            val isNext = index == confirmed && !enrolled
            NovaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "\"$phrase\"",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = colors.textPrimary
                        )
                        Text(
                            text = when {
                                done -> "Confirmed ✓"
                                isNext -> "Ab ye bolo"
                                else -> "Pehle upar wali karo"
                            },
                            fontSize = 13.sp,
                            color = if (done) colors.lagoon else colors.textSecondary
                        )
                    }
                    NovaButton(
                        text = if (listeningFor == index) "Sun rahi hun…" else "Record",
                        onClick = { if (isNext) listenOnce(index) },
                        type = if (isNext) NovaButtonType.PRIMARY else NovaButtonType.SECONDARY
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Role tag — tum kaun ho?",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        NovaChipRow(
            chips = listOf("Owner", "Family", "Friend").map { it to (role == it) },
            onChipClick = { index ->
                val picked = listOf("Owner", "Family", "Friend")[index]
                role = picked
                VoiceEnrolmentStore.setRole(context, picked)
            },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Voice-match (on-device)",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Text(
            text = "Apni awaz ka 3-second sample do — Lia ek voice score " +
                "banayegi. Ye score sirf \"Hey Lia\" ko pehchanne mein madad " +
                "karta hai; ye biometric verification NAHI hai, aur recording " +
                "kabhi save nahi hoti — sirf ek number vector phone par rehta hai.",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                if (!speakerReady) {
                    Text(
                        text = "Speaker model abhi download nahi hua — Settings → " +
                            "On-device voice se \"Speaker voice-match\" download karo (Wi-Fi, ~38 MB).",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    NovaButton(
                        text = "On-device voice kholo",
                        onClick = { navController.navigate("on_device_voice") },
                        type = NovaButtonType.SECONDARY,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                } else {
                    Text(
                        text = if (voiceEnrolled) {
                            "Voice sample enrolled ✓"
                        } else {
                            "Voice sample abhi enrol nahi hua"
                        },
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NovaButton(
                            text = if (recordingVoice) "Sun rahi hun…" else "Enrol voice sample",
                            onClick = { recordVoiceSample(true) },
                            type = NovaButtonType.PRIMARY
                        )
                        if (voiceEnrolled) {
                            NovaButton(
                                text = "Test voice-match",
                                onClick = { recordVoiceSample(false) },
                                type = NovaButtonType.SECONDARY
                            )
                        }
                    }
                    if (voiceEnrolled) {
                        NovaButton(
                            text = "Delete voice profile",
                            onClick = {
                                SpeakerVerifier.clearProfile(context)
                                voiceEnrolled = false
                                voiceMatchResult = "Voice profile delete ho gaya."
                            },
                            type = NovaButtonType.TEXT,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                voiceMatchResult?.let { result ->
                    Text(
                        text = result,
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NovaButton(
                text = "Test my voice",
                onClick = { listenOnce(-1) },
                type = NovaButtonType.SECONDARY
            )
            if (enrolled) {
                NovaButton(
                    text = "Reset",
                    onClick = {
                        VoiceEnrolmentStore.reset(context)
                        SpeakerVerifier.clearProfile(context)
                        confirmed = 0
                        enrolled = false
                        voiceEnrolled = false
                        testResult = null
                    },
                    type = NovaButtonType.TEXT
                )
            }
        }
        testResult?.let { result ->
            Text(
                text = result,
                fontSize = 14.sp,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        errorText?.let { message ->
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        if (!hasMic) {
            NovaButton(
                text = "Allow microphone",
                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
