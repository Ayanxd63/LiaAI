package com.goldai.assistant.voice

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * V16 (APK-delta addition) — Lia's echo handling, two layers.
 *
 * Layer 1 — platform AEC, where Lia owns the recorder. When Lia
 * creates its own [AudioRecord] (the Gemini Live path in
 * [LiveAudioIO]), Android's [AcousticEchoCanceler] is attached to
 * that recorder's audio session, together with the noise suppressor
 * and automatic gain control. This is the platform's own echo
 * canceller — no bundled library, no extra dependency, no extra
 * APK size. [attach] is the single place that does it, so the
 * create/release bookkeeping cannot drift between call sites.
 * Every effect is best-effort: if the phone has no AEC hardware
 * support, the session still records — it just relies on layer 2.
 *
 * Layer 2 — [EchoGuard]'s mute tail, where Lia does NOT own the
 * recorder. The no-key path uses the system [android.speech.
 * SpeechRecognizer] and the wake-word path uses Vosk's own
 * recorder; neither exposes an audio session id, so the platform
 * AEC cannot attach to them at all. There the guard mutes the mic
 * for 450 ms after Lia's own playback stops, so her voice cannot
 * echo back into the recognizer. That is the honest limit of this
 * design — a real neural AEC (DTLN/WebRTC) would need a bundled
 * native library and is a V17 decision, not a V16 patch.
 *
 * [lastStatus] is a plain human-readable line for the voice
 * pipeline debugger, updated on every attach/release.
 */
object PlatformEcho {
    private const val TAG = "PlatformEcho"

    /** One attached set of platform audio effects; release when done. */
    class Session(
        private val canceler: AcousticEchoCanceler?,
        private val noiseSuppressor: NoiseSuppressor?,
        private val gainControl: AutomaticGainControl?
    ) {
        /** True when the echo canceller is actually running. */
        val aecActive: Boolean get() = canceler?.enabled == true

        fun release() {
            try { canceler?.release() } catch (_: Exception) {}
            try { noiseSuppressor?.release() } catch (_: Exception) {}
            try { gainControl?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Human-readable state for the voice pipeline debugger, as a
     * StateFlow so the debugger updates live while recording.
     */
    private val _lastStatus = MutableStateFlow("Not recording")
    val lastStatus: StateFlow<String> = _lastStatus.asStateFlow()

    /** Whether this phone offers platform echo cancellation at all. */
    fun isAvailable(): Boolean {
        return try {
            AcousticEchoCanceler.isAvailable()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Attach the platform effects to [audioSessionId] (from
     * [AudioRecord.getAudioSessionId]). Returns null for session 0
     * (no real session) — the caller records without AEC in that
     * case. Never throws.
     */
    fun attach(audioSessionId: Int): Session? {
        if (audioSessionId == 0) {
            _lastStatus.value = "AEC off (no audio session)"
            Log.d(TAG, "No audio session — recording without platform AEC")
            return null
        }
        var canceler: AcousticEchoCanceler? = null
        var suppressor: NoiseSuppressor? = null
        var gain: AutomaticGainControl? = null
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                canceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                suppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                gain = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
        val session = Session(canceler, suppressor, gain)
        _lastStatus.value = if (session.aecActive) {
            "AEC on (platform)"
        } else if (isAvailable()) {
            "AEC available, not active"
        } else {
            "AEC unavailable on this phone"
        }
        Log.d(TAG, "Platform echo attached: ${_lastStatus.value}")
        return session
    }

    /** Called when the recorder is released — resets the status line. */
    fun onReleased() {
        _lastStatus.value = "Not recording"
    }
}
