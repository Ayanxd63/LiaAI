package com.Lia.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.Lia.assistant.action.AppContext
import com.Lia.assistant.data.AlwaysListeningPreferences
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.Personality
import com.Lia.assistant.data.VoicePlaybackSettings
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.license.AccessKeyManager
import com.Lia.assistant.ui.character.AppVisibility
import com.Lia.assistant.ui.character.FloatingCharacterService
import com.Lia.assistant.ui.fx.ListeningEdgeGlow
import com.Lia.assistant.ui.navigation.NovaApp
import com.Lia.assistant.ui.screens.splash.LiaSplash3D
import com.Lia.assistant.ui.screens.splash.SplashSession
import com.Lia.assistant.ui.theme.NovaTheme
import com.Lia.assistant.voice.VoiceForegroundService
import com.Lia.assistant.voice.VoiceSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * v10 listen-on-open guard. Process-level (a companion field, NOT
         * an instance field) so activity re-creation on rotation cannot
         * auto-start a second session — the flag survives rotation in the
         * same process. Set the first time onResume evaluates the setting,
         * whether or not a session starts, so a user who stops the session
         * manually is never auto-restarted just by returning to the app;
         * the next app launch (new process) evaluates it again.
         */
        private var listenOnOpenAttempted = false
    }

    private lateinit var appState: NovaAppState

    /** Delayed stop when Lia herself opened another app (see onStop). */
    private var backgroundStopJob: Job? = null
    private var isInForeground: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        appState = NovaAppState(this)
        handleIntent(intent)

        setContent {
            var showSplash by remember { mutableStateOf(!SplashSession.played) }

            NovaTheme(
                mode = appState.themeMode,
                reducedMotion = appState.reducedMotion,
                // V13: warm rose theme while the Girlfriend is active.
                // appState.personality is Compose state, so switching
                // personality re-themes the app live, no restart needed.
                romantic = appState.personality == Personality.GF_MODE,
                // V16 (P1-11): accent picker — rose default.
                accent = appState.accent
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NovaApp(appState = appState)

                    if (showSplash) {
                        LiaSplash3D(onDismiss = { showSplash = false })
                    }

                    ListeningEdgeGlow()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_OPEN_FORGE", false) == true) {
            ForgeController.requestOpen()
        }
        if (intent?.getBooleanExtra(FloatingCharacterService.EXTRA_START_VOICE, false) == true) {
            // Floating bubble tapped: start voice talk. Same guards as
            // VoiceScreen — mic permission, no active session. V12: no
            // key is needed any more (no-key voice works on its own).
            // If any guard fails, just open the app: the user can tap
            // Talk, and VoiceScreen will ask for what's missing.
            val hasMic = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic && !VoiceSessionManager.isActive.value) {
                VoiceForegroundService.start(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        AppVisibility.setForeground(true)
        // Back in front: cancel any pending background stop.
        backgroundStopJob?.cancel()
        backgroundStopJob = null
        lifecycleScope.launch {
            val isAllowed = AccessKeyManager.liveCheck(this@MainActivity)
            if (!isAllowed) {
                VoiceForegroundService.stop(this@MainActivity)
            }
        }
        maybeStartListeningOnOpen()
    }

    /**
     * v10 "Listen when the app opens" (VoicePlaybackSettings.listenOnOpen).
     * Runs on every onResume — which covers the app opening — but only
     * ever acts once per process (see [listenOnOpenAttempted]).
     *
     * Starts a session only when ALL of these hold:
     * - listen-on-open is enabled in Voice & language settings, and
     * - no voice session is already active, and
     * - microphone permission is already granted.
     * V12: no API key is needed any more — the no-key voice path works
     * on its own, and a saved key just switches to Gemini Live.
     *
     * If microphone permission is NOT granted, does nothing at all: no
     * crash, no permission dialog here — the existing permission flow in
     * VoiceScreen / PermissionsScreen handles granting.
     *
     * Reuses the Talk button's start path exactly: the Talk button
     * navigates to the Voice screen, whose LaunchedEffect calls
     * VoiceForegroundService.start(context); that service calls
     * VoiceSessionManager.start(). No new session mechanism is invented.
     * The session runs in the foreground service behind the home screen;
     * the home orb already observes VoiceSessionManager.state, and the
     * user can tap the orb / Talk button to open the Voice screen.
     * It does NOT navigate anywhere by itself (MainActivity owns no
     * NavController — navigation lives in NovaApp).
     */
    private fun maybeStartListeningOnOpen() {
        if (listenOnOpenAttempted) return
        listenOnOpenAttempted = true
        if (!VoicePlaybackSettings.isListenOnOpen(this)) return
        if (VoiceSessionManager.isActive.value) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        VoiceForegroundService.start(this)
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
        AppVisibility.setForeground(false)
        if (!VoiceSessionManager.isActive.value) return

        // V14: always-listening is ON — the user left the app on purpose
        // and Lia is MEANT to keep working in the background. Do not stop
        // the session, do not start the 60s timer: the foreground service
        // owns the mic now, and the notification's "Stop listening" is
        // the way to turn it off. Any pending background stop from an
        // earlier onStop is cancelled, so the session is never cut short.
        if (AlwaysListeningPreferences.isEnabled(this)) {
            backgroundStopJob?.cancel()
            backgroundStopJob = null
            return
        }

        // Lia herself just launched an external app (YouTube, WhatsApp,
        // Settings...): the activity goes to background BECAUSE of her own
        // action. Do NOT kill the voice session — it must stay alive in the
        // foreground service so she can keep talking and listening while
        // the other app is in front. Otherwise she goes completely silent
        // the moment she opens another app (the v7 bug).
        // Safety net: if there is no interaction for 60s in background,
        // stop the session then, so the mic is still released eventually
        // and other apps (e.g. WhatsApp voice notes) get it back.
        if (AppContext.wasExternalLaunchRecent()) {
            backgroundStopJob?.cancel()
            backgroundStopJob = lifecycleScope.launch {
                delay(AppContext.BACKGROUND_STAY_ALIVE_MS)
                if (!isInForeground && VoiceSessionManager.isActive.value) {
                    VoiceForegroundService.stop(this@MainActivity)
                }
            }
            return
        }

        // The user left the app himself (Home button, switched apps,
        // incoming call, screen locked): stop the voice session right away
        // so the mic is fully released for the other app. Otherwise Lia
        // keeps capturing in the background and WhatsApp voice notes
        // record empty/silent.
        VoiceForegroundService.stop(this)
    }
}
