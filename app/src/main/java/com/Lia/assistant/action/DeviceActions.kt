package com.Lia.assistant.action

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.URLEncoder

/**
 * Executes [DeviceCommand]s locally on the phone and returns a short
 * Roman Urdu reply for the user.
 *
 * Rules:
 * - Never place a call silently: dialing only opens the dialer, the user
 *   taps Call himself.
 * - Never change the system time silently: Android forbids it for normal
 *   apps (SET_TIME is signature/privileged). For time changes we only open
 *   the Date & time settings screen and explain that he taps the time there.
 * - No success message is ever faked: if an action could not run, the reply
 *   says so plainly.
 */
object DeviceActions {

    suspend fun execute(context: Context, command: DeviceCommand): String {
        return when (command) {
            is DeviceCommand.YouTubeSearch -> searchYouTube(context, command.query)
            is DeviceCommand.OpenApp -> openApp(context, command.appName)
            is DeviceCommand.OpenSettings -> openSettings(context, command.screen)
            is DeviceCommand.SetAlarm -> setAlarm(context, command)
            is DeviceCommand.Dial -> dial(context, command.target)
            is DeviceCommand.WhatsAppMessage -> whatsAppMessage(context, command)
            is DeviceCommand.Flashlight -> setFlashlight(context, command.on)
            DeviceCommand.VolumeUp -> adjustVolume(context, AudioManager.ADJUST_RAISE, "barha")
            DeviceCommand.VolumeDown -> adjustVolume(context, AudioManager.ADJUST_LOWER, "kam")
        }
    }

    // ---------------------------------------------------------------- YouTube

    private fun searchYouTube(context: Context, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded"
        val youtubeInstalled = try {
            context.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null
        } catch (_: Exception) {
            false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (youtubeInstalled) setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppContext.record("youtube")
            AppContext.markExternalLaunch()
            "YouTube par \"$query\" search kar diya hai."
        } catch (_: Exception) {
            // Last resort: plain browser view of the same search page.
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                AppContext.record("youtube")
                AppContext.markExternalLaunch()
                "YouTube par \"$query\" search kar diya hai."
            } catch (_: Exception) {
                "YouTube nahi khul saka — dobara try karo."
            }
        }
    }

    // ---------------------------------------------------------------- Open app

    /** Well-known apps: try these packages first, then the installed-app index. */
    private val appAliases: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to listOf("com.google.android.youtube"),
        "chrome" to listOf("com.android.chrome"),
        "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "gallery" to listOf(
            "com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"
        ),
        "photos" to listOf("com.google.android.apps.photos"),
        "camera" to listOf(
            "com.google.android.GoogleCamera", "com.android.camera", "com.sec.android.app.camera"
        ),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
        "play store" to listOf("com.android.vending"),
        "tiktok" to listOf("com.zhiliaoapp.musically"),
        "instagram" to listOf("com.instagram.android"),
        "facebook" to listOf("com.facebook.katana"),
        "spotify" to listOf("com.spotify.music")
    )

    private suspend fun openApp(context: Context, appName: String): String {
        val key = appName.trim().lowercase()
        val displayName = appName.trim().replaceFirstChar { it.uppercaseChar() }

        val aliasPackages = appAliases[key]
        if (aliasPackages != null) {
            for (pkg in aliasPackages) {
                if (AppLauncher.launch(context, pkg)) {
                    if (key == "whatsapp" || key == "youtube") AppContext.record(key)
                    AppContext.markExternalLaunch()
                    return "$displayName khol diya hai."
                }
            }
            return "$displayName install nahi hai — pehle Play Store se install kar lo."
        }

        val apps = InstalledAppLabelCache.get(context)
        val packageName = AppLauncher.findApp(key, apps)
        if (packageName != null && AppLauncher.launch(context, packageName)) {
            AppContext.markExternalLaunch()
            return "$displayName khol diya hai."
        }
        return "\"$appName\" naam ki app nahi mili — naam dobara check kar ke batao."
    }

    // ---------------------------------------------------------------- Settings

    private fun openSettings(context: Context, screen: SettingsScreen): String {
        val action = when (screen) {
            SettingsScreen.MAIN -> Settings.ACTION_SETTINGS
            SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsScreen.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsScreen.DATE_TIME -> Settings.ACTION_DATE_SETTINGS
            SettingsScreen.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsScreen.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsScreen.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SettingsScreen.APPS -> Settings.ACTION_APPLICATION_SETTINGS
            SettingsScreen.STORAGE -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        }
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            AppContext.markExternalLaunch()
            if (screen == SettingsScreen.DATE_TIME) {
                "Date & time settings khol di hain. Time khud se change nahi kar sakti — " +
                    "Android iski ijazat nahi deta. Yahan se time khud set kar lo."
            } else {
                "${screenLabel(screen)} khol di hain."
            }
        } catch (_: Exception) {
            "Settings nahi khul saki — dobara try karo."
        }
    }

    private fun screenLabel(screen: SettingsScreen): String = when (screen) {
        SettingsScreen.MAIN -> "Settings"
        SettingsScreen.WIFI -> "WiFi settings"
        SettingsScreen.BLUETOOTH -> "Bluetooth settings"
        SettingsScreen.DATE_TIME -> "Date & time settings"
        SettingsScreen.DISPLAY -> "Display settings"
        SettingsScreen.SOUND -> "Sound settings"
        SettingsScreen.LOCATION -> "Location settings"
        SettingsScreen.APPS -> "Apps settings"
        SettingsScreen.STORAGE -> "Storage settings"
    }

    // ---------------------------------------------------------------- Alarm

    private fun setAlarm(context: Context, command: DeviceCommand.SetAlarm): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                if (command.hasTime) {
                    putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                }
                putExtra(AlarmClock.EXTRA_MESSAGE, "Lia")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppContext.markExternalLaunch()
            if (command.hasTime) {
                "${formatAlarmTime(command.hour, command.minute)} ka alarm laga diya hai."
            } else {
                "Alarm khol diya hai — time khud set kar lo."
            }
        } catch (_: Exception) {
            "Alarm nahi lag saka — dobara try karo."
        }
    }

    private fun formatAlarmTime(hour24: Int, minute: Int): String {
        val part = when (hour24) {
            in 0..4 -> "Raat"
            in 5..11 -> "Subah"
            in 12..16 -> "Dopahar"
            in 17..20 -> "Shaam"
            else -> "Raat"
        }
        val hour12 = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        return if (minute == 0) "$part $hour12 baje" else "$part $hour12:${minute.toString().padStart(2, '0')} baje"
    }

    // ---------------------------------------------------------------- Call

    /** Opens the dialer only — the user taps Call himself. Never calls silently. */
    private fun dial(context: Context, target: String): String {
        return when (val lookup = ContactResolver.lookup(context, target)) {
            is ContactLookupResult.Found -> {
                val number = lookup.phoneNumber.replace(Regex("[^0-9+]"), "")
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    AppContext.markExternalLaunch()
                    "Dialer khol diya hai — ${lookup.displayName} ko call karne ke liye green button dabao."
                } catch (_: Exception) {
                    "Dialer nahi khul saka — dobara try karo."
                }
            }
            is ContactLookupResult.NoPermission -> {
                "Contacts ki permission nahi hai, is liye naam se number nahi dhoond sakti. " +
                    "Number bol do, ya Settings > Apps > Lia > Permissions se Contacts on kar do."
            }
            is ContactLookupResult.NotFound -> {
                "\"$target\" naam ka contact nahi mila — number bol do ya naam dobara batao."
            }
        }
    }

    // ---------------------------------------------------------------- WhatsApp

    /**
     * WhatsApp message/chat. What is REAL:
     * - A phone number opens that exact chat via wa.me/<number>.
     * - A name or a group can NOT be opened directly — WhatsApp exposes no
     *   intent for it. Lia only opens WhatsApp itself and says honestly
     *   that he should tap the chat/group himself. Never a fake success.
     * Lia only OPENS the chat — she never types or sends a message herself.
     */
    private fun whatsAppMessage(context: Context, command: DeviceCommand.WhatsAppMessage): String {
        val target = command.target.trim()
        AppContext.record("whatsapp")

        val number = toWaMeNumber(target)
        if (number != null) {
            val url = "https://wa.me/$number"
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppContext.markExternalLaunch()
                "WhatsApp par chat khol diya hai — message khud likh kar bhej do."
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                    AppContext.markExternalLaunch()
                    "WhatsApp par chat khol diya hai — message khud likh kar bhej do."
                } catch (_: Exception) {
                    "WhatsApp nahi khul saka — dobara try karo."
                }
            }
        }

        // Name or group: open WhatsApp itself, be honest about the limit.
        return if (AppLauncher.launch(context, "com.whatsapp")) {
            AppContext.markExternalLaunch()
            if (command.isGroup) {
                "WhatsApp khol diya hai — '$target' group par khud tap kar lo, " +
                    "main kisi group ko direct nahi khol sakti, WhatsApp iski ijazat nahi deta."
            } else {
                "WhatsApp khol diya hai — '$target' ka number mere paas nahi hai, " +
                    "is liye chat direct nahi khol sakti. Number bol do to chat khol dungi, " +
                    "ya yahan se khud tap kar lo."
            }
        } else {
            "WhatsApp install nahi hai — pehle Play Store se install kar lo."
        }
    }

    /**
     * Converts a spoken number to wa.me format (international, no '+').
     * Pakistani mobiles: 03XX-XXXXXXX -> 92XXXXXXXXXX. Returns null when
     * the target is not a phone number at all.
     */
    private fun toWaMeNumber(target: String): String? {
        val digits = target.replace(Regex("[^0-9]"), "")
        if (digits.length < 10 || digits.length > 15) return null
        if (!target.matches(Regex("^[+0-9][0-9\\s\\-()]*$"))) return null
        return when {
            digits.startsWith("92") && digits.length == 12 -> digits
            digits.startsWith("0") && digits.length == 11 -> "92" + digits.substring(1)
            digits.length == 10 -> "92$digits"
            digits.startsWith("92") -> digits
            else -> digits
        }
    }

    // ---------------------------------------------------------------- Torch

    private fun setFlashlight(context: Context, on: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Flashlight ke liye Camera permission chahiye — Settings > Apps > Lia > " +
                "Permissions se Camera on kar do."
        }
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Is phone mein flashlight nahi hai."
            cameraManager.setTorchMode(cameraId, on)
            if (on) "Flashlight on kar di hai." else "Flashlight band kar di hai."
        } catch (_: Exception) {
            "Flashlight nahi chal saki — dobara try karo."
        }
    }

    // ---------------------------------------------------------------- Volume

    private fun adjustVolume(context: Context, direction: Int, word: String): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI
            )
            "Volume $word diya hai."
        } catch (_: Exception) {
            "Volume change nahi ho saka — dobara try karo."
        }
    }
}
