package com.goldai.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun NovaTheme(
    mode: String = "DARK",
    reducedMotion: Boolean = false,
    // V13: when the Girlfriend personality is active, the dark theme
    // uses the warm rose variant (see DarkRoseNovaColors). Light theme
    // is untouched — romance stays on the night background.
    romantic: Boolean = false,
    // V16 (P1-11): accent colour — ROSE is the default (preserves the
    // V13 theme exactly: rose when romantic, amber otherwise). BLUE /
    // PURPLE override the accent, secondary and glow in any theme.
    accent: String = "ROSE",
    content: @Composable () -> Unit
) {
    val isDark = when (mode.uppercase()) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }

    val baseColors = if (isDark) {
        if (romantic) DarkRoseNovaColors else DarkNovaColors
    } else {
        LightNovaColors
    }

    val novaColors = when (accent.uppercase()) {
        "BLUE" -> baseColors.copy(
            accent = Color(0xFF5B8DEF),
            accentSecondary = Color(0xFF7FB3FF),
            accentGlow = Color(0x4D5B8DEF)
        )
        "PURPLE" -> baseColors.copy(
            accent = Color(0xFFB388FF),
            accentSecondary = Color(0xFFCE93D8),
            accentGlow = Color(0x4DB388FF)
        )
        else -> baseColors
    }

    val materialColors = if (isDark) {
        darkColorScheme(
            primary = novaColors.accent,
            onPrimary = novaColors.onAccent,
            primaryContainer = novaColors.surfaceRaised,
            onPrimaryContainer = novaColors.textPrimary,
            secondary = novaColors.accentSecondary,
            background = novaColors.background,
            onBackground = novaColors.textPrimary,
            surface = novaColors.surface,
            onSurface = novaColors.textPrimary,
            error = novaColors.error
        )
    } else {
        lightColorScheme(
            primary = novaColors.accent,
            onPrimary = novaColors.onAccent,
            primaryContainer = novaColors.surfaceRaised,
            onPrimaryContainer = novaColors.textPrimary,
            secondary = novaColors.accentSecondary,
            background = novaColors.background,
            onBackground = novaColors.textPrimary,
            surface = novaColors.surface,
            onSurface = novaColors.textPrimary,
            error = novaColors.error
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = novaColors.background.toArgb()
            window.navigationBarColor = novaColors.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalNovaColors provides novaColors,
        LocalNovaReducedMotion provides reducedMotion
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = NovaTypography,
            content = content
        )
    }
}
