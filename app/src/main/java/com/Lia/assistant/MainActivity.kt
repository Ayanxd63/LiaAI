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
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.NovaAppState
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
                reducedMotion = appState.reducedMotion
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
            // VoiceScreen — mic permission, API key, no active session.
            // If any guard fails, just open the app: the user can tap
            // Talk, and VoiceScreen will ask for what's missing.
            val hasMic = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic && ApiKeyStore.hasKey(this) && !VoiceSessionManager.isActive.value) {
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
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
        AppVisibility.setForeground(false)
        if (!VoiceSessionManager.isActive.value) return

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
