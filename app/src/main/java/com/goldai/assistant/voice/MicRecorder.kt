package com.goldai.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * V21 — small shared microphone capture (restored from V17) for the on-device engines.
 *
 * Blocking helpers (call on a background thread, never Main):
 * fixed-duration capture for voice enrolment, and the raw
 * AudioRecord factory the streaming engines (Shruti STT) build
 * their own loops on. 16 kHz mono PCM — the sample rate every
 * sherpa-onnx model expects.
 *
 * RECORD_AUDIO is already declared for the voice sessions; callers
 * still check the permission here and return null when it is missing.
 */
object MicRecorder {

    private const val TAG = "MicRecorder"
    const val SAMPLE_RATE = 16_000

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Create a 16 kHz mono AudioRecord, or null when it cannot start. */
    fun createAudioRecord(): AudioRecord? {
        return runCatching {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2) // >= 1s
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.onFailure { e ->
            Log.w(TAG, "AudioRecord creation failed", e)
        }.getOrNull()
    }

    /** Convert one PCM-16 chunk into float samples in [-1, 1]. */
    fun pcm16ToFloat(buffer: ShortArray, read: Int): FloatArray {
        val out = FloatArray(read)
        for (i in 0 until read) {
            out[i] = (buffer[i].toFloat() / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }

    /**
     * Record exactly [seconds] of audio and return the samples, or
     * null when the mic is unavailable / permission is missing.
     * Used by voice enrolment (3-second voice samples).
     */
    fun captureSeconds(context: Context, seconds: Int): FloatArray? {
        if (!hasPermission(context)) return null
        val record = createAudioRecord() ?: return null
        val totalSamples = SAMPLE_RATE * seconds
        val collected = FloatArray(totalSamples)
        var filled = 0
        try {
            record.startRecording()
            val chunk = ShortArray(1024)
            while (filled < totalSamples) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break
                val take = minOf(read, totalSamples - filled)
                for (i in 0 until take) {
                    collected[filled + i] =
                        (chunk[i].toFloat() / 32768f).coerceIn(-1f, 1f)
                }
                filled += take
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mic capture failed", e)
            return null
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        if (filled < SAMPLE_RATE / 2) return null // less than 0.5s — unusable
        return collected.copyOf(filled)
    }
}
