package com.goldai.assistant.ui.character

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goldai.assistant.R
import com.goldai.assistant.ui.theme.LocalNovaColors

/**
 * Lia's home characters. Each one is a pair of 2D illustrations of the
 * same front-facing portrait — mouth closed, and mouth open mid-speech.
 * This is NOT a 3D model: [CharacterView] just crossfades between the
 * two drawings in time with her voice. Never describe it as 3D in UI.
 *
 * Both pairs were verified aligned (same face position/size; only the
 * mouth and a few redrawn strands differ), so the crossfade does not
 * make the face jump.
 */
enum class LiaCharacter(
    val id: String,
    val displayName: String,
    val closedRes: Int,
    val openRes: Int
) {
    SAREE(
        id = "saree",
        displayName = "Lia — Red Saree",
        closedRes = R.drawable.lia_character_saree_closed,
        openRes = R.drawable.lia_character_saree_open
    ),
    HEADPHONES(
        id = "headphones",
        displayName = "Lia — Headphones",
        closedRes = R.drawable.lia_character_headphones_closed,
        openRes = R.drawable.lia_character_headphones_open
    );

    companion object {
        // V15: headphones is the default (ayan's order 2026-11-22).
        fun fromId(id: String?): LiaCharacter =
            values().firstOrNull { it.id == id } ?: HEADPHONES
    }
}

/**
 * Maps live speech amplitude (0f..1f, from SpeechLevelTracker — Gemini
 * voice only) to how open the mouth drawing is. Gemini Live RMS is
 * typically 0.02..0.35, so the range is stretched: anything at the
 * silence floor stays shut, loud speech is fully open.
 */
private fun levelToMouthOpen(level: Float): Float {
    if (level <= 0.015f) return 0f
    return ((level - 0.015f) / 0.30f).coerceIn(0f, 1f)
}

/**
 * Displays the selected character, blending from the closed-mouth
 * drawing toward the open-mouth drawing as [speechLevel] rises.
 *
 * The blend animates fast on attack (~70ms, the mouth opens the moment
 * she starts speaking) and a little slower on release (~160ms, so it
 * eases shut instead of flickering). With [animate] false (reduced
 * motion) the mouth snaps to the target instead of tweening.
 *
 * The portraits are square with the face in the upper half, so the
 * images are cropped with [Alignment.TopCenter] — a centred crop would
 * cut the chin and show mostly shoulders in a circle.
 */
@Composable
fun CharacterView(
    character: LiaCharacter,
    speechLevel: Float,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    shape: Shape = CircleShape,
    animate: Boolean = true,
    contentDescription: String? = null
) {
    val colors = LocalNovaColors.current
    val targetOpen = levelToMouthOpen(speechLevel.coerceIn(0f, 1f))

    // Remember the previous target so the tween knows whether this is
    // an attack (mouth opening) or a release (mouth closing).
    var previousTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(targetOpen) { previousTarget = targetOpen }

    val mouthOpen by animateFloatAsState(
        targetValue = targetOpen,
        animationSpec = if (!animate) {
            snap()
        } else if (targetOpen >= previousTarget) {
            tween(durationMillis = 70, easing = LinearEasing)
        } else {
            tween(durationMillis = 160, easing = LinearEasing)
        },
        label = "characterMouthOpen"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(
                width = 2.dp,
                color = colors.accent.copy(alpha = 0.25f + 0.55f * mouthOpen),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Closed-mouth drawing: always fully visible underneath.
        Image(
            painter = painterResource(character.closedRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        // Open-mouth drawing: faded in on top, proportional to speech.
        // Same framing as the closed drawing, so the face never jumps.
        Image(
            painter = painterResource(character.openRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            alpha = mouthOpen,
            modifier = Modifier.fillMaxSize()
        )
    }
}
