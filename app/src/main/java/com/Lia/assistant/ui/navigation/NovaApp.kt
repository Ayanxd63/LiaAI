package com.Lia.assistant.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.Lia.assistant.data.CrashReportStore
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.SetupWizardPreferences
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.ui.screens.about.AboutScreen
import com.Lia.assistant.ui.screens.character.CHARACTER_SETTINGS_ROUTE
import com.Lia.assistant.ui.screens.character.CharacterSettingsScreen
import com.Lia.assistant.ui.screens.elevenlabs.ElevenLabsSettingsScreen
import com.Lia.assistant.ui.screens.keyboard.VoiceKeyboardSettingsScreen
import com.Lia.assistant.ui.screens.prompt.PromptGeneratorScreen
import com.Lia.assistant.ui.screens.voice.VoiceSettingsScreen
import com.Lia.assistant.ui.screens.actions.ActionsScreen
import com.Lia.assistant.ui.screens.backup.BackupScreen
import com.Lia.assistant.ui.screens.debug.CrashReportScreen
import com.Lia.assistant.ui.screens.setup.SetupWizardScreen
import com.Lia.assistant.ui.screens.skills.SkillsScreen
import com.Lia.assistant.ui.screens.subagents.SubAgentsScreen
import com.Lia.assistant.ui.screens.voice.GeminiVoicePickerScreen
import com.Lia.assistant.ui.screens.voice.VoiceEnrolmentScreen
import com.Lia.assistant.ui.screens.create.CreateImageScreen
import com.Lia.assistant.ui.screens.debug.DebugScreen
import com.Lia.assistant.ui.screens.forge.ForgeScreen
import com.Lia.assistant.ui.screens.history.HistoryScreen
import com.Lia.assistant.ui.screens.home.HomeScreen
import com.Lia.assistant.ui.screens.onboarding.OnboardingScreen
import com.Lia.assistant.ui.screens.orb.OrbStyleScreen
import com.Lia.assistant.ui.screens.permissions.PermissionsScreen
import com.Lia.assistant.ui.screens.personality.PersonalityScreen
import com.Lia.assistant.ui.screens.privacy.PrivacyScreen
import com.Lia.assistant.ui.screens.profile.ProfileScreen
import com.Lia.assistant.ui.screens.quickactions.QuickActionsScreen
import com.Lia.assistant.ui.screens.settings.MemoryScreen
import com.Lia.assistant.ui.screens.settings.OfflineModelScreen
import com.Lia.assistant.ui.screens.settings.OnDeviceVoiceScreen
import com.Lia.assistant.ui.screens.settings.SettingsScreen
import com.Lia.assistant.ui.screens.voice.VoiceScreen
import com.Lia.assistant.ui.screens.write.WriteScreen
import com.Lia.assistant.ui.theme.LocalBottomBarInset
import com.Lia.assistant.ui.theme.nightSky

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

    val bottomBarRoutes = setOf("home", "chat", "history", "settings")
    val showBottomBar = currentRoute in bottomBarRoutes

    // Reactively observe ForgeController.pendingOpen
    LaunchedEffect(ForgeController.pendingOpen) {
        if (ForgeController.consumePendingOpen()) {
            navController.navigate("forge") {
                launchSingleTop = true
            }
        }
    }

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
                    composable("quick_actions") { QuickActionsScreen(navController) }
                    composable("profile") { ProfileScreen(navController, appState) }
                    composable("personality") { PersonalityScreen(navController, appState) }
                    composable("orb_style") { OrbStyleScreen(navController, appState) }
                    composable("privacy") { PrivacyScreen(navController) }
                    composable("permissions") { PermissionsScreen(navController) }
                    composable("about") { AboutScreen(navController) }
                    composable("debug") { DebugScreen(navController) }
                    composable("forge") { ForgeScreen(navController) }
                    // v10: official LIA quick actions — Create Image + Write.
                    composable("create_image") { CreateImageScreen(navController) }
                    composable("write") { WriteScreen(navController) }
                    // v12: Prompt Generator replaces Create Video (video dropped).
                    composable("prompt_generator") { PromptGeneratorScreen(navController) }
                    // v10: settings screens for the new features.
                    composable(CHARACTER_SETTINGS_ROUTE) { CharacterSettingsScreen(navController) }
                    composable("elevenlabs_settings") { ElevenLabsSettingsScreen(navController) }
                    composable("voice_settings") { VoiceSettingsScreen(navController) }
                    composable("voice_keyboard") { VoiceKeyboardSettingsScreen(navController) }
                    // V15: offline AI model manager + long-term memory.
                    composable("offline_model") { OfflineModelScreen(navController) }
                    composable("on_device_voice") { OnDeviceVoiceScreen(navController) }
                    composable("memory") { MemoryScreen(navController) }
                    // V16: setup wizard, voice picker + enrolment, skills,
                    // actions, sub-agents, backup, crash reports.
                    composable("setup_wizard") { SetupWizardScreen(navController, appState) }
                    composable("voice_picker") { GeminiVoicePickerScreen(navController, appState) }
                    composable("voice_enrolment") { VoiceEnrolmentScreen(navController) }
                    composable("skills") { SkillsScreen(navController) }
                    composable("actions") { ActionsScreen(navController) }
                    composable("sub_agents") { SubAgentsScreen(navController) }
                    composable("backup") { BackupScreen(navController) }
                    composable("crash_report") { CrashReportScreen(navController) }

                    // Flavor-specific routes (e.g. accessibility disclosure in direct)
                    registerAccessibilityDisclosureRoute(navController)
                }
            }
        }
    }
}
