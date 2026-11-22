package com.Lia.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.Lia.assistant.data.VoicePlaybackSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LiveAudioIO(
    private val onMicChunk: (ByteArray) -> Unit,
    private val onMicAmplitude: (Float) -> Unit,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "LiveAudioIO"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val MIC_CHUNK_SIZE = 2048

        fun calculateRms(pcmBytes: ByteArray): Float {
            if (pcmBytes.size < 2) return 0f
            var sumSquares = 0.0
            val sampleCount = pcmBytes.size / 2
            for (i in 0 until sampleCount) {
                val low = pcmBytes[i * 2].toInt() and 0xFF
                val high = pcmBytes[i * 2 + 1].toInt()
                val sample = (high shl 8) or low
                sumSquares += (sample * sample).toDouble()
            }
            val mean = sumSquares / sampleCount
            val rawRms = sqrt(mean) / 32768.0
            return max(0f, min(1f, rawRms.toFloat()))
        }

        /** 0–100 volume percent -> AudioTrack gain 0f..1f. */
        fun volumePercentToGain(volumePercent: Int): Float {
            return VoicePlaybackSettings.volumePercentToGain(volumePercent)
        }

        /**
         * Speech speed -> AudioTrack playback rate in Hz (sampleRate * speed).
         * Clamped to 0.5x–2.0x of the speaker sample rate (12–48 kHz), a
         * conservative legal playback-rate range that covers the whole
         * 0.5x–1.5x settings range (12–36 kHz). setPlaybackRate itself is
         * still guarded with try/catch at the call site.
         */
        fun playbackRateForSpeed(speed: Float): Int {
            val clamped = speed.coerceIn(
                VoicePlaybackSettings.MIN_SPEECH_SPEED,
                VoicePlaybackSettings.MAX_SPEECH_SPEED
            )
            val rate = (SPEAKER_SAMPLE_RATE * clamped).roundToInt()
            return rate.coerceIn(SPEAKER_SAMPLE_RATE / 2, SPEAKER_SAMPLE_RATE * 2)
        }
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var audioTrack: AudioTrack? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val lock = Any()
    private var currentVolume = 1.0f

    // v10 voice playback settings (VoicePlaybackSettings store, defaults
    // 0.85x / 65%). Applied to the AudioTrack at playback time; see
    // refreshPlaybackSettings() / applyPlaybackRateToTrack().
    private var speechSpeed = VoicePlaybackSettings.DEFAULT_SPEECH_SPEED
    private var appliedPlaybackRate = SPEAKER_SAMPLE_RATE
    private var appliedSpeechSpeed: Float? = null

    // Audio focus: held only while the mic is open, and abandoned on release
    // so other apps (e.g. WhatsApp voice notes) get the mic back cleanly.
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val ctx = context ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (_: Exception) {
            // Focus is best-effort; recording still works without it.
        }
    }

    private fun abandonAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        } finally {
            audioFocusRequest = null
            audioManager = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.get()) return

        requestAudioFocus()
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, 4096)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            val sessionId = record.audioSessionId
            if (sessionId != 0) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                } catch (_: Exception) {}

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }
                } catch (_: Exception) {}

                try {
                    if (AutomaticGainControl.isAvailable()) {
                        gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    }
                } catch (_: Exception) {}
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)

            recordThread = Thread({
                val buffer = ByteArray(MIC_CHUNK_SIZE)
                while (isRecording.get()) {
                    val read = record.read(buffer, 0, MIC_CHUNK_SIZE)
                    if (read > 0) {
                        val chunk = if (read == MIC_CHUNK_SIZE) buffer.clone() else buffer.copyOf(read)
                        val amp = calculateRms(chunk)
                        onMicAmplitude(amp)
                        onMicChunk(chunk)
                    }
                }
            }, "LiveAudioIO-MicThread").apply { start() }
        } catch (_: Exception) {
            isRecording.set(false)
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        recordThread?.interrupt()
        // The record thread may be blocked inside AudioRecord.read(); wait
        // briefly so it exits before we release the recorder underneath it.
        try { recordThread?.join(500) } catch (_: Exception) {}
        recordThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        Log.d(TAG, "Mic stopped: AudioRecord released")
    }

    private fun getOrCreateSpeaker(): AudioTrack {
        synchronized(lock) {
            audioTrack?.let { return it }

            // Load the v10 voice playback settings before creating the
            // track, so the first chunk already plays at the right
            // volume and speed instead of the 1.0x / 100% defaults.
            context?.let { ctx ->
                val settings = VoicePlaybackSettings.current(ctx)
                speechSpeed = settings.speechSpeed
                currentVolume = VoicePlaybackSettings.volumePercentToGain(settings.voiceVolume)
            }

            val minBuf = AudioTrack.getMinBufferSize(
                SPEAKER_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = minBuf * 4

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack(
                attributes,
                format,
                bufSize,
                AudioTrack.MODE_STREAM,
                0
            )
            track.setVolume(currentVolume)
            track.play()
            audioTrack = track
            // A fresh track plays at its native sample rate; apply the
            // user's speech speed to it (no-op if it is already 1.0x).
            appliedPlaybackRate = SPEAKER_SAMPLE_RATE
            appliedSpeechSpeed = null
            applyPlaybackRateToTrack()
            return track
        }
    }

    fun playChunk(pcmBytes: ByteArray): Float {
        if (pcmBytes.isEmpty()) return 0f
        // Re-read the settings on every chunk: a slider change in the
        // Voice & language screen takes effect on the next chunk of
        // Gemini speech, with no session restart. Cheap — the store is
        // SharedPreferences (in-memory) and the rate setter is a no-op
        // unless the speed actually changed.
        refreshPlaybackSettings()
        val track = getOrCreateSpeaker()
        track.write(pcmBytes, 0, pcmBytes.size)
        val rms = calculateRms(pcmBytes)
        // Lip-sync driver: this is the ONLY place assistant audio is
        // played, and its only caller is VoiceSessionManager.onAudioChunk
        // (Gemini Live voice). The chat screen's system TextToSpeech
        // read-aloud never passes through here, so it never moves the
        // character's mouth — lip sync is Gemini voice only, by design.
        SpeechLevelTracker.update(rms)
        return rms
    }

    fun flushPlayback() {
        synchronized(lock) {
            audioTrack?.let { track ->
                try {
                    track.pause()
                    track.flush()
                    track.play()
                } catch (_: Exception) {}
            }
        }
    }

    fun setVolume(volume: Float) {
        val clamped = max(0f, min(1f, volume))
        currentVolume = clamped
        synchronized(lock) {
            audioTrack?.setVolume(clamped)
        }
    }

    /**
     * Applies the v10 voice playback settings to the speaker: volume
     * 0–100 -> AudioTrack gain via the existing [setVolume], speech speed
     * via [AudioTrack.setPlaybackRate]. Safe to call any time; with no
     * track yet the values are stored and applied when the track is
     * created in [getOrCreateSpeaker].
     */
    fun applyPlaybackSettings(volumePercent: Int, speed: Float) {
        speechSpeed = speed.coerceIn(
            VoicePlaybackSettings.MIN_SPEECH_SPEED,
            VoicePlaybackSettings.MAX_SPEECH_SPEED
        )
        val gain = VoicePlaybackSettings.volumePercentToGain(volumePercent)
        if (gain != currentVolume) {
            setVolume(gain)
        }
        applyPlaybackRateToTrack()
    }

    /**
     * Re-reads the stored voice playback settings and applies them.
     * Called from [playChunk] so a change made mid-session takes effect
     * on the next chunk of Gemini speech.
     */
    fun refreshPlaybackSettings() {
        val ctx = context
        if (ctx == null) {
            applyPlaybackRateToTrack()
            return
        }
        val settings = VoicePlaybackSettings.current(ctx)
        applyPlaybackSettings(settings.voiceVolume, settings.speechSpeed)
    }

    /**
     * Applies [speechSpeed] to the current AudioTrack via setPlaybackRate
     * (sampleRate * speed). No-op if this speed was already applied to
     * this track, so per-chunk refreshes are cheap and a rejected rate is
     * not retried on every chunk. If the device rejects the rate, falls
     * back to 1.0x (the native sample rate) and keeps playing — never
     * throws, never silences Lia.
     */
    private fun applyPlaybackRateToTrack() {
        synchronized(lock) {
            val track = audioTrack ?: return
            if (appliedSpeechSpeed == speechSpeed) return
            appliedSpeechSpeed = speechSpeed
            val desiredRate = playbackRateForSpeed(speechSpeed)
            if (desiredRate == appliedPlaybackRate) return
            try {
                val result = track.setPlaybackRate(desiredRate)
                if (result == AudioTrack.SUCCESS) {
                    appliedPlaybackRate = desiredRate
                } else {
                    Log.w(
                        TAG,
                        "setPlaybackRate($desiredRate) rejected (result=$result), " +
                            "falling back to 1.0x"
                    )
                    fallbackPlaybackRate(track)
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "setPlaybackRate($desiredRate) rejected, falling back to 1.0x", e)
                fallbackPlaybackRate(track)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "setPlaybackRate failed (track state), keeping current rate", e)
            } catch (e: Exception) {
                Log.w(TAG, "setPlaybackRate failed, keeping current rate", e)
            }
        }
    }

    private fun fallbackPlaybackRate(track: AudioTrack) {
        try {
            if (track.setPlaybackRate(SPEAKER_SAMPLE_RATE) == AudioTrack.SUCCESS) {
                appliedPlaybackRate = SPEAKER_SAMPLE_RATE
            }
        } catch (_: Exception) {
            // Keep playing at whatever rate the track is already using.
        }
    }

    fun release() {
        stopRecording()
        abandonAudioFocus()
        synchronized(lock) {
            audioTrack?.let { track ->
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }
            audioTrack = null
            appliedPlaybackRate = SPEAKER_SAMPLE_RATE
            appliedSpeechSpeed = null
        }
        Log.d(TAG, "Mic released: AudioRecord stopped, audio focus abandoned — other apps can use the mic now")
    }
}
