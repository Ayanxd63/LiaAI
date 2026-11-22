package com.Lia.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.Lia.assistant.data.AlwaysListeningPreferences
import java.util.concurrent.TimeUnit

/**
 * V14 — watchdog that keeps always-listening alive.
 *
 * Runs every 15 minutes (the WorkManager minimum) and restarts the
 * voice service when always-listening is ON but the service is not
 * running — e.g. after the phone's battery manager killed it, or after
 * a reboot where the boot receiver could not start it directly.
 *
 * This is BEST-EFFORT by design, and the code says so: on Android 12+
 * a background worker cannot always start a foreground service (the
 * system may throw ForegroundServiceStartNotAllowedException), so the
 * worker catches everything and never retries in a loop. The reliable
 * paths are the boot receiver, the onTaskRemoved restart in the
 * service, and the user reopening the app — the watchdog is the third
 * line of defence, not the first.
 */
class AlwaysListeningWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "LiaWatchdog"
        private const val UNIQUE_PERIODIC_NAME = "lia_always_listening_watchdog"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlwaysListeningWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<AlwaysListeningWatchdogWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_NAME)
        }
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!AlwaysListeningPreferences.isEnabled(ctx)) return Result.success()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        if (VoiceForegroundService.isRunning) return Result.success()
        return try {
            VoiceForegroundService.startAlwaysListening(ctx)
            Log.d(TAG, "Watchdog restarted the always-listening service")
            Result.success()
        } catch (e: Exception) {
            // Background start not allowed right now — the next 15-minute
            // run (or a reboot / app open) will try again. Never fail the
            // work: a failed worker would back off and retry in a loop.
            Log.w(TAG, "Watchdog could not restart the service (best-effort)", e)
            Result.success()
        }
    }
}
