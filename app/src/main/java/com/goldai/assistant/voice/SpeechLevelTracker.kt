package com.goldai.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Lip-sync driver for Lia's home character.
 *
 * Fed ONLY by [LiveAudioIO.playChunk] — the single choke point where
 * Gemini Live voice audio (Sulafat, or whichever Live voice is active)
 * is written to the speaker. Nothing else feeds it: the chat screen's
 * system TextToSpeech read-aloud never passes through here, so it
 * never moves the character's mouth (different voice, not Gemini).
 *
 * The character is a 2D illustration, not a 3D model: [speechLevel]
 * blends between the closed-mouth and open-mouth drawings. Attack is
 * instant (the mouth opens the moment sound starts) and release decays
 * a little slower, so it reads as talking instead of flickering.
 */
object SpeechLevelTracker {

    /** RMS below this counts as silence — the mouth stays closed. */
    private const val SILENCE_FLOOR = 0.015f

    /** Per-chunk release decay: the level falls to ~0 in a few chunks. */
    private const val RELEASE_DECAY = 0.72f

    private val _speechLevel = MutableStateFlow(0f)

    /** Live speech amplitude, 0f (silent) .. 1f (loudest). */
    val speechLevel: StateFlow<Float> = _speechLevel.asStateFlow()

    private var lastLevel = 0f

    /** Feed the RMS of one played assistant PCM chunk. */
    @Synchronized
    fun update(rms: Float) {
        val level = if (rms < SILENCE_FLOOR) 0f else rms
        lastLevel = if (level > lastLevel) {
            // Fast attack: open immediately on louder sound.
            level
        } else {
            // Slower release: ease shut instead of snapping closed.
            max(0f, lastLevel * RELEASE_DECAY)
        }
        _speechLevel.value = min(1f, lastLevel)
    }

    /** Silence: mouth closed. Called on turn end, interruption and stop. */
    @Synchronized
    fun reset() {
        lastLevel = 0f
        _speechLevel.value = 0f
    }
}
