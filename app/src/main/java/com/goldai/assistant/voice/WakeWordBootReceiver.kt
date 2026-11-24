package com.goldai.assistant.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.goldai.assistant.data.AlwaysListeningPreferences

/**
 * V14 — restart always-listening after a reboot.
 *
 * Fires only when the user turned always-listening on AND the mic
 * permission is still granted. Two restarts are attempted because a
 * BOOT_COMPLETED receiver cannot start every foreground-service type
 * directly on Android 15: the watchdog work is the safe route (it runs
 * later, under JobScheduler), and the direct start is tried too —
 * BOOT_COMPLETED is a background-start exemption for the microphone
 * type, so it works on most phones. Either way nothing crashes if the
 * system refuses: both attempts are best-effort.
 */
class WakeWordBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeWordBoot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AlwaysListeningPreferences.isEnabled(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Always-listening is on but mic permission is missing — not restarting")
            return
        }
        AlwaysListeningWatchdogWorker.enqueue(context)
        AlwaysListeningWatchdogWorker.enqueueOneTime(context)
        try {
            VoiceForegroundService.startAlwaysListening(context)
            Log.d(TAG, "Always-listening restarted after boot")
        } catch (e: Exception) {
            Log.w(TAG, "Direct restart after boot refused — watchdog will retry", e)
        }
    }
}
