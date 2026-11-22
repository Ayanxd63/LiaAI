package com.Lia.assistant.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.Lia.assistant.MainActivity
import com.Lia.assistant.R
import com.Lia.assistant.data.AlwaysListeningPreferences
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.ui.fx.OverlayEdgeGlowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * V14 — the voice foreground service, now with two modes.
 *
 * SESSION mode (V7–V13, unchanged): a voice conversation is running.
 * The notification says Lia is listening, "Stop" ends the session and
 * the service stops itself.
 *
 * ALWAYS-LISTENING mode (V14): no conversation is running; the
 * on-device Vosk wake-word spotter listens for "Hey Lia". The
 * notification says so, and "Stop listening" turns always-listening
 * OFF entirely (preference cleared, watchdog cancelled, service
 * stopped). When the wake word fires, the spotter stops and a normal
 * session starts; when that session ends, the spotter starts again —
 * the service never stops itself while always-listening is on.
 *
 * The mic is owned by exactly one thing at a time: the spotter OR the
 * session (Live audio or the no-key SpeechRecognizer). The service is
 * the only place that switches between them, driven by
 * [VoiceSessionManager.isActive].
 *
 * Always-listening is OFF by default and only runs when the user
 * turned it on in Settings (with the privacy line). When it is OFF,
 * this service behaves exactly like V13.
 */
class VoiceForegroundService : Service() {

    companion object {
        private const val TAG = "VoiceFGS"

        const val ACTION_START = "com.Lia.assistant.action.START_VOICE"
        const val ACTION_STOP = "com.Lia.assistant.action.STOP_VOICE"
        const val ACTION_START_ALWAYS_LISTENING =
            "com.Lia.assistant.action.START_ALWAYS_LISTENING"
        const val ACTION_STOP_LISTENING =
            "com.Lia.assistant.action.STOP_LISTENING"

        private const val CHANNEL_ID = "lia_voice_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * True while the service process is alive. The watchdog reads
         * this (best-effort) instead of guessing from the activity.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun startAlwaysListening(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_START_ALWAYS_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Turn always-listening OFF for good: preference cleared and
         * watchdog cancelled here (so it works even if the service is
         * not currently running), then the service is told to stop.
         */
        fun stopListening(context: Context) {
            AlwaysListeningPreferences.setEnabled(context, false)
            AlwaysListeningWatchdogWorker.cancel(context)
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** True while the user has always-listening on (persisted pref). */
    private var alwaysListening: Boolean = false

    private var spotter: WakeWordSpotter? = null
    private var spotterError: String? = null
    private var stopping: Boolean = false

    /**
     * The session observer must ignore the FIRST collected value: when
     * the service is created for a normal session while always-listening
     * is on, isActive is still false at that moment, and an unguarded
     * observer would start the spotter a split second before
     * onStartCommand stops it again. The observer is armed only after
     * the first command has been handled.
     */
    private var sessionObserverArmed: Boolean = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        alwaysListening = AlwaysListeningPreferences.isEnabled(this)
        // The session/spotter switchboard: whenever a session starts,
        // the spotter stops; whenever it ends and always-listening is
        // still on, the spotter starts again.
        serviceScope.launch {
            VoiceSessionManager.isActive.collect { active ->
                if (stopping || !sessionObserverArmed) return@collect
                if (active) {
                    stopSpotter()
                } else if (alwaysListening) {
                    startSpotter()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                AlwaysListeningPreferences.setEnabled(this, false)
                AlwaysListeningWatchdogWorker.cancel(this)
                alwaysListening = false
                stopSpotter()
                VoiceSessionManager.stop()
                OverlayEdgeGlowController.stop(this)
                stopForegroundService()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                if (alwaysListening) {
                    // End this conversation, stay alive, go back to
                    // listening for "Hey Lia". The session observer
                    // re-arms the spotter on later transitions; this
                    // explicit start covers this transition, because
                    // StateFlow does not re-deliver a value the observer
                    // skipped while it was unarmed.
                    sessionObserverArmed = true
                    VoiceSessionManager.stop()
                    OverlayEdgeGlowController.stop(this)
                    refreshNotification()
                    startSpotter()
                    return START_STICKY
                }
                VoiceSessionManager.stop()
                OverlayEdgeGlowController.stop(this)
                stopForegroundService()
                return START_NOT_STICKY
            }

            ACTION_START_ALWAYS_LISTENING -> {
                alwaysListening = true
                stopping = false
                sessionObserverArmed = true
                startForegroundWithNotification(listening = true)
                if (!VoiceSessionManager.isActive.value) {
                    startSpotter()
                }
                return START_STICKY
            }

            ACTION_START -> {
                stopping = false
                sessionObserverArmed = true
                stopSpotter()
                startForegroundWithNotification(listening = false)
                OverlayEdgeGlowController.start(this)
                VoiceSessionManager.start(this)
                return START_STICKY
            }

            else -> {
                // Null intent: the system restarted us (START_STICKY).
                // Only stay alive when always-listening is on.
                if (AlwaysListeningPreferences.isEnabled(this)) {
                    alwaysListening = true
                    stopping = false
                    sessionObserverArmed = true
                    startForegroundWithNotification(listening = true)
                    if (!VoiceSessionManager.isActive.value) {
                        startSpotter()
                    }
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * The user swiped the app away from recents. A normal service dies
     * here; with always-listening on, ask the system to start us again.
     * Best-effort — the watchdog is the backup if this is refused.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!alwaysListening) return
        try {
            startAlwaysListening(this)
        } catch (e: Exception) {
            Log.w(TAG, "Restart after task-removed refused (watchdog is backup)", e)
        }
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        serviceScope.cancel()
        stopSpotter()
        VoiceSessionManager.stop()
        OverlayEdgeGlowController.stop(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Wake-word spotter
    // ------------------------------------------------------------------

    private fun startSpotter() {
        if (stopping || !alwaysListening) return
        if (spotter?.isRunning == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            spotterError = "Microphone permission missing"
            refreshNotification()
            return
        }
        if (!WakeWordModelManager.isModelReady(this)) {
            spotterError = "Voice model missing — open Settings to download it"
            refreshNotification()
            return
        }
        spotterError = null
        val newSpotter = WakeWordSpotter(
            context = this,
            onWakeWord = {
                // Vosk callbacks arrive on its audio thread — hop to main.
                mainHandler.post { onWakeWordHeard() }
            },
            onError = { message ->
                mainHandler.post {
                    spotterError = message
                    refreshNotification()
                }
            }
        )
        spotter = newSpotter
        newSpotter.start()
        refreshNotification()
    }

    private fun stopSpotter() {
        spotter?.release()
        spotter = null
    }

    /** "Hey Lia" heard: stop spotting, start a normal voice session. */
    private fun onWakeWordHeard() {
        if (stopping || !alwaysListening) return
        if (VoiceSessionManager.isActive.value) return
        stopSpotter()
        startForegroundWithNotification(listening = false)
        OverlayEdgeGlowController.start(this)
        VoiceSessionManager.start(this)
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun startForegroundWithNotification(listening: Boolean) {
        createNotificationChannel()
        val notification = buildNotification(listening)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        if (!isRunning) return
        createNotificationChannel()
        val listening = alwaysListening && !VoiceSessionManager.isActive.value
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(listening))
    }

    private fun stopForegroundService() {
        stopping = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val assistantName = NovaPreferences.getString(
                this,
                NovaPreferences.Keys.ASSISTANT_NAME,
                AssistantBrand.NAME
            )
            val channelName = "$assistantName voice session"
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing active voice conversation with $assistantName"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(listening: Boolean): Notification {
        val assistantName = NovaPreferences.getString(
            this,
            NovaPreferences.Keys.ASSISTANT_NAME,
            AssistantBrand.NAME
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(assistantName)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (listening) {
            builder.setContentText(
                spotterError ?: "$assistantName is listening for \"Hey Lia\"…"
            )
            val stopListeningIntent = Intent(this, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            val stopListeningPendingIntent = PendingIntent.getService(
                this,
                2,
                stopListeningIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Stop listening", stopListeningPendingIntent)
        } else {
            builder.setContentText("$assistantName is listening...")
            val stopIntent = Intent(this, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Stop", stopPendingIntent)
        }
        return builder.build()
    }
}
