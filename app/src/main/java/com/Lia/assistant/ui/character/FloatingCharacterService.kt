package com.Lia.assistant.ui.character

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.Lia.assistant.MainActivity
import com.Lia.assistant.data.CharacterPreferences
import com.Lia.assistant.voice.SpeechLevelTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks whether Lia's own UI is in the foreground. Set by
 * MainActivity.onResume/onStop. The floating bubble must never show
 * while the app itself is in front (it would duplicate the home
 * character); it only appears over other apps.
 */
object AppVisibility {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun setForeground(inForeground: Boolean) {
        _isForeground.value = inForeground
    }
}

/**
 * "Show her everywhere": a small draggable bubble showing the selected
 * character's face over other apps, using SYSTEM_ALERT_WINDOW.
 *
 * The bubble is a plain-View overlay (two stacked ImageViews, closed
 * mouth below and open mouth above, crossfaded by the same
 * [SpeechLevelTracker] that drives the home character) — no Compose
 * here, because a Service-hosted ComposeView would need a hand-rolled
 * LifecycleOwner and SavedStateRegistryOwner.
 *
 * Behaviour:
 * - Only shown while the app is NOT in the foreground (see
 *   [AppVisibility]); hidden the moment Lia's own UI comes forward.
 * - Drag it anywhere; a tap (not a drag) launches MainActivity into
 *   voice talk.
 * - Lip-sync applies to Gemini voice only, exactly like the home
 *   character — the bubble animates while Lia speaks in voice mode.
 * - If the overlay permission is revoked while running, the service
 *   stops itself and turns the toggle back off so the setting never lies.
 *
 * Manifest entries are NOT added here — the parent merges them (see
 * the v10-character report): SYSTEM_ALERT_WINDOW permission plus the
 * service declaration.
 */
class FloatingCharacterService : Service() {

    companion object {
        const val ACTION_START = "com.Lia.assistant.action.START_CHARACTER_BUBBLE"
        const val ACTION_STOP = "com.Lia.assistant.action.STOP_CHARACTER_BUBBLE"

        /** "Show her everywhere" intent extra handled by MainActivity. */
        const val EXTRA_START_VOICE = "EXTRA_START_VOICE"

        private const val BUBBLE_SIZE_DP = 76
        private const val TAP_SLOP_DP = 10

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, FloatingCharacterService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingCharacterService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var bubbleView: FrameLayout? = null
    private var closedImage: ImageView? = null
    private var openImage: ImageView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeBubble()
            stopSelf()
            return START_NOT_STICKY
        }

        // No overlay permission (revoked after the toggle was turned on):
        // stop and turn the toggle off so Settings never shows a lie.
        if (!Settings.canDrawOverlays(this)) {
            CharacterPreferences.setShowEverywhere(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            createBubble()
        }
        return START_STICKY
    }

    private fun createBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val sizePx = (BUBBLE_SIZE_DP * metrics.density).toInt()
        val tapSlopPx = (TAP_SLOP_DP * metrics.density)

        // V15: headphones is the default character — the observer below
        // replaces these with the stored choice as soon as it loads.
        val closed = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(LiaCharacter.HEADPHONES.closedRes)
        }
        val open = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            setImageResource(LiaCharacter.HEADPHONES.openRes)
        }
        closedImage = closed
        openImage = open

        val container = FrameLayout(this).apply {
            // Circular bubble; the portraits are square with the face up
            // top, and CENTER_CROP keeps the face visible.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            addView(closed, FrameLayout.LayoutParams(sizePx, sizePx))
            addView(open, FrameLayout.LayoutParams(sizePx, sizePx))
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start at the right edge, about a third down the screen.
            x = metrics.widthPixels - sizePx - (16 * metrics.density).toInt()
            y = (metrics.heightPixels * 0.35f).toInt()
        }
        // Drag anywhere; a tap (barely moved) opens voice talk.
        var downX = 0f
        var downY = 0f
        var downParamsX = 0
        var downParamsY = 0
        var moved = false
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downParamsX = params.x
                    downParamsY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!moved && (dx * dx + dy * dy) > (tapSlopPx * tapSlopPx)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = downParamsX + dx
                        params.y = downParamsY + dy
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openVoiceTalk()
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        updateVisibility()
        wm.addView(container, params)

        // Follow character choice, lip-sync level, and app visibility.
        serviceScope.launch {
            CharacterPreferences.observe(this@FloatingCharacterService).collect { settings ->
                val character = LiaCharacter.fromId(settings.characterId)
                closedImage?.setImageResource(character.closedRes)
                openImage?.setImageResource(character.openRes)
            }
        }
        serviceScope.launch {
            SpeechLevelTracker.speechLevel.collect { level ->
                // Same mapping as CharacterView: silent = shut, loud = open.
                val openAmount = if (level <= 0.015f) {
                    0f
                } else {
                    ((level - 0.015f) / 0.30f).coerceIn(0f, 1f)
                }
                openImage?.alpha = openAmount
            }
        }
        serviceScope.launch {
            AppVisibility.isForeground.collect {
                updateVisibility()
            }
        }
    }

    /** Hide the bubble while Lia's own UI is in front. */
    private fun updateVisibility() {
        val view = bubbleView ?: return
        val appInForeground = AppVisibility.isForeground.value || isAppInForeground()
        view.visibility = if (appInForeground) View.GONE else View.VISIBLE
    }

    /** Backup foreground check, in case MainActivity has not reported yet. */
    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.runningAppProcesses?.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    /** Tap the bubble: bring Lia forward and start voice talk. */
    private fun openVoiceTalk() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_START_VOICE, true)
        }
        startActivity(intent)
    }

    private fun removeBubble() {
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        bubbleView = null
        closedImage = null
        openImage = null
        windowManager = null
    }

    override fun onDestroy() {
        removeBubble()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }
}
