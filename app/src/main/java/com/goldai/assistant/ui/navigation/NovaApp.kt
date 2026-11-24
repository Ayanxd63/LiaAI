package com.goldai.assistant.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goldai.assistant.data.CrashReportStore
import com.goldai.assistant.data.NovaAppState
import com.goldai.assistant.data.NovaPreferences
import com.goldai.assistant.data.SetupWizardPreferences
import com.goldai.assistant.ui.screens.about.AboutScreen
import com.goldai.assistant.ui.screens.character.CHARACTER_SETTINGS_ROUTE
import com.goldai.assistant.ui.screens.character.CharacterSettingsScreen
import com.goldai.assistant.ui.screens.chat.ChatScreen
import com.goldai.assistant.ui.screens.prompt.PromptGeneratorScreen
import com.goldai.assistant.ui.screens.voice.VoiceSettingsScreen
import com.goldai.assistant.ui.screens.debug.CrashReportScreen
import com.goldai.assistant.ui.screens.setup.SetupWizardScreen
import com.goldai.assistant.ui.screens.voice.VoiceEnrolmentScreen
import com.goldai.assistant.ui.screens.create.CreateImageScreen
import com.goldai.assistant.ui.screens.debug.DebugScreen
import com.goldai.assistant.ui.screens.history.HistoryScreen
import com.goldai.assistant.ui.screens.home.HomeScreen
import com.goldai.assistant.ui.screens.onboarding.OnboardingScreen
import com.goldai.assistant.ui.screens.permissions.PermissionsScreen
import com.goldai.assistant.ui.screens.personality.PersonalityScreen
import com.goldai.assistant.ui.screens.privacy.PrivacyScreen
import com.goldai.assistant.ui.screens.settings.MemoryScreen
import com.goldai.assistant.ui.screens.settings.OnDeviceVoiceScreen
import com.goldai.assistant.ui.screens.settings.SettingsScreen
import com.goldai.assistant.ui.screens.voice.VoiceScreen
import com.goldai.assistant.ui.theme.LocalBottomBarInset
import com.goldai.assistant.ui.theme.nightSky

@Composable
fun NovaApp(appState: NovaAppState) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val hasOnboarded = NovaPreferences.getBoolean(context, NovaPreferences.Keys.HAS_ONBOARDED, false)
    // V16: start destination — a crash report comes first (warm
    // next-launch screen), then the first-run setup wizard, then
    // onboarding/home as before. The wizard never blocks a returning
    // user: it only shows when it was never completed.
    val startDestination = when {
        CrashReportStore.hasUnseen(context) -> "crash_report"
        hasOnboarded && !SetupWizardPreferences.isDone(context) -> "setup_wizard"
        hasOnboarded -> "home"
        else -> "onboarding"
    }

    val bottomBarRoutes = setOf("home", "chat", "settings")
    val showBottomBar = currentRoute in bottomBarRoutes

    CompositionLocalProvider(LocalBottomBarInset provides if (showBottomBar) 80.dp else 0.dp) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nightSky(),
            bottomBar = {
                if (showBottomBar) {
                    LiaBottomNavigation(
                        navController = navController,
                        currentRoute = currentRoute
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("onboarding") { OnboardingScreen(navController) }
                    composable("home") { HomeScreen(navController, appState) }
                    composable("chat") { ChatScreen(navController, appState) }
                    // V13: reopen a saved chat from History and continue it.
                    composable(
                        route = "chat/{sessionId}",
                        arguments = listOf(
                            navArgument("sessionId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        ChatScreen(
                            navController = navController,
                            appState = appState,
                            sessionId = backStackEntry.arguments?.getString("sessionId")
                        )
                    }
                    composable("history") { HistoryScreen(navController, appState) }
                    composable("settings") { SettingsScreen(navController, appState) }
                    composable("voice") { VoiceScreen(navController, appState) }
                    composable("personality") { PersonalityScreen(navController, appState) }
                    composable("privacy") { PrivacyScreen(navController) }
                    composable("permissions") { PermissionsScreen(navController) }
                    composable("about") { AboutScreen(navController) }
                    composable("debug") { DebugScreen(navController) }
                    // v10: Create Image (Write removed in V20).
                    composable("create_image") { CreateImageScreen(navController) }
                    // v12: Prompt Generator replaces Create Video (video dropped).
                    composable("prompt_generator") { PromptGeneratorScreen(navController) }
                    // v10: settings screens for the new features.
                    composable(CHARACTER_SETTINGS_ROUTE) { CharacterSettingsScreen(navController) }
                    composable("voice_settings") { VoiceSettingsScreen(navController) }
                    // V15: long-term memory screen. (V18: the offline
                    // model manager + on-device voice screens were
                    // removed with the engines they served. V19: the
                    // on-device voice screen is back — Pocket TTS only.)
                    composable("on_device_voice") { OnDeviceVoiceScreen(navController) }
                    composable("memory") { MemoryScreen(navController) }
                    // V16: setup wizard, voice enrolment, crash reports.
                    // (V20: skills, actions, sub-agents, backup and the
                    // Gemini voice picker were removed.)
                    composable("setup_wizard") { SetupWizardScreen(navController, appState) }
                    composable("voice_enrolment") { VoiceEnrolmentScreen(navController) }
                    composable("crash_report") { CrashReportScreen(navController) }

                    // Flavor-specific routes (e.g. accessibility disclosure in direct)
                    registerAccessibilityDisclosureRoute(navController)
                }
            }
        }
    }
}
