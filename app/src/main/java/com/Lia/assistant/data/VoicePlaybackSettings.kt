package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice playback settings (v10) — the "Voice & language" settings from
 * official LIA v1.12: speech speed, voice volume, and "Listen when the app
 * opens".
 *
 * Own small store on purpose: it lives in its own SharedPreferences file
 * ("voice_playback_prefs"), separate from NovaPreferences ("nova_prefs"),
 * so NovaPreferences.kt is untouched and these values cannot collide with
 * the older, unused NovaAppState.speechSpeed / voiceVolume fields (which
 * default to 1.0x / 1.0 and were never applied to playback).
 *
 * Defaults match official LIA v1.12: speech speed 0.85x, voice volume 65%,
 * listen-on-open off.
 *
 * Reads are synchronous (SharedPreferences), which is what the
 * listen-on-open gate in MainActivity.onResume and the per-chunk playback
 * refresh in LiveAudioIO need — no coroutine hop to decide whether to
 * start listening or how loud/fast to play.
 */
object VoicePlaybackSettings {

    private const val PREF_NAME = "voice_playback_prefs"
    private const val KEY_SPEECH_SPEED = "speech_speed"
    private const val KEY_VOICE_VOLUME = "voice_volume"
    private const val KEY_LISTEN_ON_OPEN = "listen_on_open"

    const val MIN_SPEECH_SPEED = 0.5f
    const val MAX_SPEECH_SPEED = 1.5f
    const val SPEECH_SPEED_STEP = 0.05f
    const val DEFAULT_SPEECH_SPEED = 0.85f

    const val MIN_VOICE_VOLUME = 0
    const val MAX_VOICE_VOLUME = 100
    const val DEFAULT_VOICE_VOLUME = 65

    const val DEFAULT_LISTEN_ON_OPEN = false

    /** Immutable snapshot of the three settings, exposed as a StateFlow. */
    data class VoicePlaybackState(
        val speechSpeed: Float = DEFAULT_SPEECH_SPEED,
        val voiceVolume: Int = DEFAULT_VOICE_VOLUME,
        val listenOnOpen: Boolean = DEFAULT_LISTEN_ON_OPEN
    )

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun load(context: Context): VoicePlaybackState {
        val prefs = getPrefs(context)
        return VoicePlaybackState(
            speechSpeed = prefs.getFloat(KEY_SPEECH_SPEED, DEFAULT_SPEECH_SPEED)
                .coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED),
            voiceVolume = prefs.getInt(KEY_VOICE_VOLUME, DEFAULT_VOICE_VOLUME)
                .coerceIn(MIN_VOICE_VOLUME, MAX_VOICE_VOLUME),
            listenOnOpen = prefs.getBoolean(KEY_LISTEN_ON_OPEN, DEFAULT_LISTEN_ON_OPEN)
        )
    }

    /**
     * Reads the stored values and publishes them on [state]. Call once when
     * a screen first observes the settings (e.g. in a LaunchedEffect), and
     * any time a caller needs a fresh snapshot instead of the last published
     * one. Returns the loaded state.
     */
    fun refresh(context: Context): VoicePlaybackState {
        val loaded = load(context.applicationContext)
        if (_state.value != loaded) _state.value = loaded
        return loaded
    }

    /** Synchronous snapshot of the stored values (refreshes the StateFlow). */
    fun current(context: Context): VoicePlaybackState = refresh(context)

    fun getSpeechSpeed(context: Context): Float = current(context).speechSpeed

    fun getVoiceVolume(context: Context): Int = current(context).voiceVolume

    fun isListenOnOpen(context: Context): Boolean = current(context).listenOnOpen

    /** Persists immediately and publishes the new value on [state]. */
    fun setSpeechSpeed(context: Context, speed: Float) {
        // Snap to the 0.05 UI step so the stored value always matches what
        // the slider shows (e.g. 0.85, never 0.8499999).
        val stepped = (speed / SPEECH_SPEED_STEP).let { kotlin.math.round(it) * SPEECH_SPEED_STEP }
        val clamped = stepped.coerceIn(MIN_SPEECH_SPEED, MAX_SPEECH_SPEED)
        getPrefs(context.applicationContext).edit()
            .putFloat(KEY_SPEECH_SPEED, clamped).apply()
        _state.value = _state.value.copy(speechSpeed = clamped)
    }

    /** Persists immediately and publishes the new value on [state]. */
    fun setVoiceVolume(context: Context, volumePercent: Int) {
        val clamped = volumePercent.coerceIn(MIN_VOICE_VOLUME, MAX_VOICE_VOLUME)
        getPrefs(context.applicationContext).edit()
            .putInt(KEY_VOICE_VOLUME, clamped).apply()
        _state.value = _state.value.copy(voiceVolume = clamped)
    }

    /** Persists immediately and publishes the new value on [state]. */
    fun setListenOnOpen(context: Context, listenOnOpen: Boolean) {
        getPrefs(context.applicationContext).edit()
            .putBoolean(KEY_LISTEN_ON_OPEN, listenOnOpen).apply()
        _state.value = _state.value.copy(listenOnOpen = listenOnOpen)
    }

    /** 0–100 volume percent -> AudioTrack gain 0f..1f. */
    fun volumePercentToGain(volumePercent: Int): Float {
        return volumePercent.coerceIn(MIN_VOICE_VOLUME, MAX_VOICE_VOLUME) / 100f
    }

    /** Formats a speed for display, e.g. 0.85 -> "0.85x". */
    fun formatSpeed(speed: Float): String {
        return String.format(Locale.US, "%.2fx", speed)
    }

    /** Formats a volume percent for display, e.g. 65 -> "65%". */
    fun formatVolume(volumePercent: Int): String = "$volumePercent%"
}
