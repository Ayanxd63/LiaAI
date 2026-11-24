package com.goldai.assistant.input

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.goldai.assistant.MainActivity
import com.goldai.assistant.ui.components.NovaButton
import com.goldai.assistant.ui.components.NovaButtonType
import com.goldai.assistant.ui.theme.LocalNovaColors
import com.goldai.assistant.ui.theme.NovaTheme
import java.util.Locale

/**
 * Lia Voice Keyboard — a real speak-to-type input method (IME).
 *
 * The user switches to this keyboard in any app, taps the mic, speaks in
 * Hindi or English, and the recognised text lands in the app's text field.
 * It uses Android's on-device/Google [SpeechRecognizer] — no Gemini, no API
 * key, and nothing from the voice-chat session is involved.
 *
 * Behaviour (one clear rule, chosen for v10):
 * - Tap the mic once to start listening. Partial results appear live in the
 *   preview as you speak.
 * - On a FINAL result the text is AUTO-COMMITTED to the current input field
 *   immediately, the preview is cleared, and listening stops. (One tap, one
 *   utterance, straight into the field — no second tap needed.)
 * - The "Insert" button is for the preview that is still on screen: it
 *   commits whatever text is showing (for example a partial result after
 *   stopping early) and clears the preview. It is disabled when the preview
 *   is empty, so it can never insert nothing.
 * - Space / Delete / Enter act on the field. Delete first erases the
 *   uncommitted preview, then deletes from the field itself.
 *
 * Language: Hindi ("hi-IN") is tried first, English ("en-IN") second, and
 * the device default locale last. Android offers no public API to list the
 * languages a recogniser supports, so a language is discovered by trying it:
 * if the recogniser reports LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE we
 * step to the next language automatically and say so in the status line.
 * If the device has no speech recognition at all, the keyboard says so
 * honestly instead of crashing.
 *
 * Permissions: RECORD_AUDIO is declared in the manifest. An IME service has
 * no Activity to run a runtime-permission prompt from, so if the permission
 * is missing the keyboard shows a message and offers a button that opens
 * Lia so the user can grant the microphone there.
 *
 * Manifest wiring is NOT in this file — the parent merges the service
 * declaration (see the v10-keyboard report). Until then this service is
 * inert: Android will not list it as a keyboard.
 */
class LiaKeyboardService : InputMethodService() {

    companion object {
        private const val LANGUAGE_HINDI = "hi-IN"
        private const val LANGUAGE_ENGLISH = "en-IN"
    }

    // --- UI state (Compose reads these; the service owns them) ---
    private var isListening by mutableStateOf(false)
    private var previewText by mutableStateOf("")
    private var statusText by mutableStateOf("Tap the mic and speak — Hindi or English")
    private var activeLanguage by mutableStateOf(LANGUAGE_HINDI)
    private var recognitionAvailable by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)

    // --- Speech recognition state ---
    private var recognizer: SpeechRecognizer? = null
    /** True while the user wants us listening (mic tapped, not yet stopped). */
    private var wantListening = false
    /** 0 = hi-IN, 1 = en-IN, 2 = device default locale. */
    private var languageStage = 0

    override fun onCreate() {
        super.onCreate()
        hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        if (recognitionAvailable) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(LiaRecognitionListener())
                }
            } catch (_: Exception) {
                // Some devices advertise recognition but fail to create one.
                recognizer = null
                recognitionAvailable = false
            }
        }
        if (!recognitionAvailable) {
            statusText = "Speech recognition is not available on this device."
        } else if (!hasMicPermission) {
            statusText = "Microphone permission is needed — open Lia and allow the microphone, then come back here."
        }
    }

    override fun onCreateInputView(): View {
        return ComposeView(this).apply {
            setContent {
                NovaTheme(mode = "DARK") {
                    LiaKeyboardView()
                }
            }
        }
    }

    /** Keep the keyboard compact: never take over the screen with the extracted-text view. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        // A new field gets a clean slate — never carry an uncommitted preview into it.
        previewText = ""
        hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!recognitionAvailable) {
            statusText = "Speech recognition is not available on this device."
        } else if (!hasMicPermission) {
            statusText = "Microphone permission is needed — open Lia and allow the microphone, then come back here."
        } else if (!isListening) {
            statusText = "Tap the mic and speak — Hindi or English"
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopListening()
        previewText = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // Best effort only — we are shutting down.
        }
        recognizer?.destroy()
        recognizer = null
    }

    // --- Listening control ---

    private fun toggleListening() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (!recognitionAvailable || recognizer == null) {
            statusText = "Speech recognition is not available on this device."
            return
        }
        if (!hasMicPermission) {
            statusText = "Microphone permission is needed — open Lia and allow the microphone, then come back here."
            return
        }
        wantListening = true
        languageStage = 0
        startListeningWithCurrentLanguage()
    }

    private fun startListeningWithCurrentLanguage() {
        val language = when (languageStage) {
            0 -> LANGUAGE_HINDI
            1 -> LANGUAGE_ENGLISH
            else -> Locale.getDefault().toLanguageTag()
        }
        activeLanguage = language
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            wantListening = false
            isListening = false
            statusText = "Could not start listening — please try again."
        }
    }

    private fun stopListening() {
        wantListening = false
        if (isListening) {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
                // Already stopped or destroyed; nothing to do.
            }
        }
        isListening = false
        if (recognitionAvailable && hasMicPermission) {
            statusText = "Tap the mic and speak — Hindi or English"
        }
    }

    /** Step to the next fallback language and retry, once per language. */
    private fun fallbackToNextLanguage() {
        if (languageStage < 2) {
            languageStage++
            val note = when (languageStage) {
                1 -> "Hindi recognition is not available on this device — using English."
                else -> "English recognition is not available either — using the device language."
            }
            statusText = note
            startListeningWithCurrentLanguage()
        } else {
            wantListening = false
            isListening = false
            statusText = "Speech recognition is not available in Hindi, English or the device language on this device."
        }
    }

    // --- Committing text to the field ---

    private fun currentConnection(): InputConnection? = currentInputConnection

    /** Commits [text] into the field the user is typing in. Returns true if it went in. */
    private fun commitToField(text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            currentConnection()?.commitText(text, 1) == true
        } catch (_: Exception) {
            false
        }
    }

    /** "Insert": commits whatever is in the preview and clears it. */
    private fun insertPreview() {
        val text = previewText
        if (text.isBlank()) return
        if (commitToField(text)) {
            previewText = ""
            statusText = "Inserted. Tap the mic to speak again."
        } else {
            statusText = "Could not insert — tap into a text field first."
        }
    }

    private fun insertSpace() {
        commitToField(" ")
    }

    private fun deleteLast() {
        // Uncommitted preview goes first — it is ours, not the app's text.
        if (previewText.isNotEmpty()) {
            previewText = previewText.dropLast(1)
            return
        }
        try {
            currentConnection()?.deleteSurroundingText(1, 0)
        } catch (_: Exception) {
            // No field focused or already empty; nothing to delete.
        }
    }

    private fun pressEnter() {
        val connection = currentConnection() ?: return
        try {
            // Send a real Enter key event so the app handles it (newline / send / search)
            // exactly as it would from a hardware keyboard.
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } catch (_: Exception) {
            commitToField("\n")
        }
    }

    private fun openLiaApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // If Lia cannot be opened, the status message already explains the fix.
        }
    }

    // --- Recognition callbacks ---

    private inner class LiaRecognitionListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            val languageName = when (activeLanguage) {
                LANGUAGE_HINDI -> "Hindi"
                LANGUAGE_ENGLISH -> "English"
                else -> "device language ($activeLanguage)"
            }
            statusText = "Listening in $languageName… speak now"
        }

        override fun onBeginningOfSpeech() {
            isListening = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Amplitude is available here if a future version wants a level meter.
            // Deliberately unused in v10 — keep the keyboard simple and robust.
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // The recogniser stopped capturing; the final result (or an error)
            // follows and decides what happens next.
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                previewText = text
                statusText = "Listening…"
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            // Single-shot behaviour: a final result goes straight into the field.
            wantListening = false
            isListening = false
            previewText = ""
            if (text.isNotBlank()) {
                if (commitToField(text)) {
                    statusText = "Inserted. Tap the mic to speak again."
                } else {
                    // No field to commit into — keep the text visible so it is not lost.
                    previewText = text
                    statusText = "Tap into a text field, then tap Insert."
                }
            } else {
                statusText = "Didn't catch that — tap the mic and try again."
            }
        }

        override fun onError(error: Int) {
            isListening = false
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // The current language is not installed/supported — step down
                    // the fallback chain (hi-IN -> en-IN -> device default).
                    if (wantListening) fallbackToNextLanguage()
                    return
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    wantListening = false
                    statusText = "Didn't catch that — tap the mic and try again."
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wantListening = false
                    hasMicPermission = false
                    statusText = "Microphone permission is needed — open Lia and allow the microphone, then come back here."
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Another app is using the recogniser; retry once after it frees up.
                    wantListening = false
                    statusText = "Speech recognition is busy — try again in a moment."
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    wantListening = false
                    statusText = "Speech recognition needs a network connection — check the internet and try again."
                }
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_AUDIO -> {
                    wantListening = false
                    statusText = "Microphone error — try again."
                }
                else -> {
                    wantListening = false
                    statusText = "Listening stopped — tap the mic to try again."
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    // --- Keyboard UI ---

    @Composable
    private fun LiaKeyboardView() {
        val colors = LocalNovaColors.current
        val languageLabel = when (activeLanguage) {
            LANGUAGE_HINDI -> "Hindi (hi-IN)"
            LANGUAGE_ENGLISH -> "English (en-IN)"
            else -> "Device language ($activeLanguage)"
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Top row: language + close.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lia Voice Keyboard · $languageLabel",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { requestHideSelf(0) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close keyboard",
                        tint = colors.textPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Preview: recognised speech, live while listening.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceRaised)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = previewText.ifBlank { "Tap the mic and speak…" },
                    fontSize = 16.sp,
                    color = if (previewText.isBlank()) colors.textTertiary else colors.textPrimary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusText,
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Mic + Insert row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { toggleListening() },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (isListening) colors.error else colors.accent),
                    enabled = recognitionAvailable
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Start listening",
                        tint = colors.onAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = if (isListening) "Listening… tap to stop" else "Tap mic to speak",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                NovaButton(
                    text = "Insert",
                    onClick = { insertPreview() },
                    enabled = previewText.isNotBlank()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Space / Delete / Enter row + keyboard switcher.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NovaButton(
                    text = "Space",
                    onClick = { insertSpace() },
                    type = NovaButtonType.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
                NovaButton(
                    text = "Delete",
                    onClick = { deleteLast() },
                    type = NovaButtonType.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
                NovaButton(
                    text = "Enter",
                    onClick = { pressEnter() },
                    type = NovaButtonType.SECONDARY,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { VoiceKeyboardStore.showInputMethodPicker(this@LiaKeyboardService) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = "Switch keyboard",
                        tint = colors.textPrimary
                    )
                }
            }

            // No-mic-permission shortcut: open Lia so the user can grant it.
            if (recognitionAvailable && !hasMicPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                NovaButton(
                    text = "Open Lia to allow microphone",
                    onClick = { openLiaApp() },
                    type = NovaButtonType.SECONDARY,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
