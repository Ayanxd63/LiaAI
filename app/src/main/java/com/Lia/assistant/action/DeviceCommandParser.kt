package com.Lia.assistant.action

/**
 * Pure-Kotlin parser for local device commands. No Android imports, so it is
 * fully unit-testable on the JVM.
 *
 * Matches commands in English and Roman Urdu, case-insensitive. When a
 * message parses to a [DeviceCommand], Lia executes it LOCALLY on the phone
 * instead of sending it to Gemini — so it works even on a very slow network.
 *
 * Anything that does not parse here goes to Gemini exactly as before.
 */
sealed interface DeviceCommand {
    data class YouTubeSearch(val query: String) : DeviceCommand
    data class OpenApp(val appName: String) : DeviceCommand
    data class OpenSettings(val screen: SettingsScreen) : DeviceCommand
    data class SetAlarm(val hour: Int, val minute: Int, val hasTime: Boolean) : DeviceCommand
    data class Dial(val target: String) : DeviceCommand

    /**
     * WhatsApp message/chat. [target] is a phone number (digits, may start
     * with + or 0), a contact/group name, or empty when only a group was
     * named. [isGroup] is true when the user asked for a group by name.
     * WhatsApp exposes NO intent to open a specific group — for names and
     * groups Lia only opens WhatsApp itself and says so honestly.
     */
    data class WhatsAppMessage(val target: String, val isGroup: Boolean = false) : DeviceCommand
    data class Flashlight(val on: Boolean) : DeviceCommand
    data object VolumeUp : DeviceCommand
    data object VolumeDown : DeviceCommand

    /**
     * V14 — direct call: ACTION_CALL without opening the dialer. Needs
     * CALL_PHONE (runtime). Unlike [Dial] (dialer-only by design), this
     * one calls straight away when the permission is granted, and falls
     * back to the dialer when it is not (see CommIntents.placeCall).
     */
    data class DirectCall(val target: String) : DeviceCommand

    /**
     * V14 — brightness. Exactly one of [level] (absolute 0-100) or
     * [delta] (relative, e.g. -20 for "kam karo") is set. Needs
     * WRITE_SETTINGS (special permission, granted on a system screen).
     */
    data class Brightness(val level: Int? = null, val delta: Int? = null) : DeviceCommand

    /** V14 — Do Not Disturb. Needs Notification Policy Access. */
    data class DoNotDisturb(val on: Boolean) : DeviceCommand

    /**
     * V14 — open the Internet connectivity panel (WiFi + mobile data
     * toggles). Android gives no silent toggle for WiFi or data, so
     * "wifi on karo" opens this panel and the user taps once — that is
     * the Android rule, not a Lia limitation.
     */
    data object InternetPanel : DeviceCommand

    /** V14 — Bluetooth: on shows the system Allow dialog, off opens settings. */
    data class BluetoothToggle(val on: Boolean) : DeviceCommand

    /** V14 — read the recent notifications out loud. Needs notification access. */
    data object ReadNotifications : DeviceCommand

    /**
     * V14 — reply to a notification through the original app's own
     * reply action (RemoteInput). [appHint] is an app name ("whatsapp")
     * or null for the latest replyable notification.
     */
    data class ReplyNotification(val appHint: String?, val message: String) : DeviceCommand

    /** V14 (P2) — which app is in front right now. Needs Usage access. */
    data object CurrentApp : DeviceCommand

    /** V14 (P2) — top apps by foreground time today. Needs Usage access. */
    data object TopApps : DeviceCommand
}

enum class SettingsScreen {
    MAIN, WIFI, BLUETOOTH, DATE_TIME, DISPLAY, SOUND, LOCATION, APPS, STORAGE,
    // V14: battery saver, mobile-data usage, notification-access screens.
    BATTERY_SAVER, DATA_USAGE, NOTIFICATION_ACCESS
}

object DeviceCommandParser {

    // Roman Urdu number words for alarm hours ("saat baje" = 7 o'clock).
    private val urduNumbers = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "paanch" to 5,
        "chhe" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "gyarah" to 11, "barah" to 12
    )

    /**
     * Parse a message. [lastApp] is the app Lia opened most recently in this
     * session ("youtube" / "whatsapp", from [AppContext]) — it is only used
     * for bare follow-ups like "song search karo" after YouTube was opened.
     * Explicit single-step commands always win over context: the follow-up
     * parser runs only after every explicit command parser has said no.
     */
    fun parse(rawText: String, lastApp: String? = null): DeviceCommand? {
        val text = normalize(rawText)
        if (text.isEmpty()) return null

        parseYouTubeSearch(text)?.let { return it }
        parseWhatsAppMessage(text)?.let { return it }
        parseSettings(text)?.let { return it }
        parseTogglePanel(text)?.let { return it }
        parseAlarm(text)?.let { return it }
        parseDirectCall(text)?.let { return it }
        parseCall(text)?.let { return it }
        parseFlashlight(text)?.let { return it }
        parseVolume(text)?.let { return it }
        parseBrightness(text)?.let { return it }
        parseDoNotDisturb(text)?.let { return it }
        parseNotifications(text)?.let { return it }
        parseUsage(text)?.let { return it }
        parseFollowUp(text, lastApp)?.let { return it }
        parseOpenApp(text)?.let { return it }
        return null
    }

    /** Lowercase, collapse whitespace, strip trailing punctuation and a leading "lia"/"please". */
    internal fun normalize(rawText: String): String {
        var text = rawText.trim().lowercase()
        text = text.replace(Regex("\\s+"), " ")
        text = text.trimEnd('.', '!', '?')
        for (prefix in listOf("lia, ", "lia ", "please ", "plz ")) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }
        return text.trim()
    }

    // "youtube par cat video search karo" / "youtube pe ali zafar dikhao" /
    // "youtube search cat video" / "search on youtube for cat video"
    private fun parseYouTubeSearch(text: String): DeviceCommand.YouTubeSearch? {
        val patterns = listOf(
            Regex("^youtube\\s+(?:par|pe|mein|me|on)\\s+(.+?)\\s+(?:search\\s+karo|search\\s+kar\\s+do|search\\s+kar|search|dikhao|dikha\\s+do|dikha|play\\s+karo|play\\s+kar|chalao|chala\\s+do)$"),
            Regex("^youtube\\s+search\\s+(.+)$"),
            Regex("^search\\s+(?:on\\s+)?youtube\\s+(?:for\\s+)?(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val query = match.groupValues.last().trim()
            if (query.isNotEmpty()) return DeviceCommand.YouTubeSearch(query)
        }
        return null
    }

    // "03001234567 ko whatsapp karo" / "ali ko whatsapp message karo" /
    // "ali ko message karo" / "school group kholo" / "mera school group kholo"
    // NOTE: a group can never be opened directly — WhatsApp exposes no
    // intent for it. The executor opens WhatsApp itself and says so honestly.
    private fun parseWhatsAppMessage(text: String): DeviceCommand.WhatsAppMessage? {
        // "... group kholo" — a group name, never a number.
        val groupKholo = Regex(
            "^(?:mera\\s+|my\\s+)?(.+?)\\s+group\\s+(?:kholo|khol\\s+do|open\\s+karo|open\\s+kar\\s+do)$"
        ).find(text)
        if (groupKholo != null) {
            val name = groupKholo.groupValues[1].trim()
            if (name.isNotEmpty() && name != "whatsapp") {
                return DeviceCommand.WhatsAppMessage(name, isGroup = true)
            }
        }

        val patterns = listOf(
            Regex("^(.+?)\\s+ko\\s+(?:whatsapp|whatsap|watsapp)\\s+(?:message\\s+karo|msg\\s+karo|karo|kar\\s+do)$"),
            Regex("^(.+?)\\s+ko\\s+(?:whatsapp\\s+)?(?:message|msg)\\s+(?:karo|kar\\s+do|bhejo|bhej\\s+do)$"),
            Regex("^whatsapp\\s+(?:par|pe|mein|me|on)\\s+(.+?)\\s+ko\\s+(?:message|msg)\\s+(?:karo|kar\\s+do|bhejo|bhej\\s+do)$"),
            Regex("^whatsapp\\s+(?:message|msg)\\s+(?:karo|kar\\s+do)\\s+(?:to\\s+)?(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val target = match.groupValues.last().trim()
            if (target.isNotEmpty()) return DeviceCommand.WhatsAppMessage(target)
        }
        return null
    }

    /**
     * Bare follow-ups that only make sense with the last app as context.
     * Only YouTube follow-ups live here — WhatsApp follow-ups ("X ko message
     * karo") are already handled by [parseWhatsAppMessage] with or without
     * context. This runs AFTER every explicit command parser, so "alarm
     * lagao" or "volume tez karo" are never mistaken for a YouTube search.
     */
    private fun parseFollowUp(text: String, lastApp: String?): DeviceCommand? {
        if (lastApp != "youtube") return null
        // "song search karo" / "atif aslam chalao" / "funny video dikhao" /
        // "naat play karo" — the query is whatever comes before the verb.
        val match = Regex(
            "^(.+?)\\s+(search\\s+karo|search\\s+kar\\s+do|search|chalao|chala\\s+do|dikhao|dikha\\s+do|play\\s+karo|play\\s+kar|laga\\s+do|lagao)$"
        ).find(text) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return null
        return DeviceCommand.YouTubeSearch(query)
    }

    // "wifi settings kholo", "bluetooth settings kholo", "time change karo",
    // "date time settings kholo", "display settings kholo", "settings kholo"
    private fun parseSettings(text: String): DeviceCommand.OpenSettings? {
        val mentionsSettings = text.contains("setting") || text.contains("kholo") ||
            text.contains("open") || text.contains("change") || text.contains("set karo") ||
            text.contains("badlo") || text.contains("badal do")
        if (!mentionsSettings) return null

        return when {
            text.contains("wifi") -> DeviceCommand.OpenSettings(SettingsScreen.WIFI)
            text.contains("bluetooth") -> DeviceCommand.OpenSettings(SettingsScreen.BLUETOOTH)
            text.contains("time") || text.contains("date") ->
                DeviceCommand.OpenSettings(SettingsScreen.DATE_TIME)
            text.contains("display") -> DeviceCommand.OpenSettings(SettingsScreen.DISPLAY)
            text.contains("sound") -> DeviceCommand.OpenSettings(SettingsScreen.SOUND)
            text.contains("location") -> DeviceCommand.OpenSettings(SettingsScreen.LOCATION)
            text.contains("storage") -> DeviceCommand.OpenSettings(SettingsScreen.STORAGE)
            text.contains("apps") -> DeviceCommand.OpenSettings(SettingsScreen.APPS)
            text == "settings kholo" || text == "setting kholo" ||
                text == "open settings" || text == "settings open karo" ->
                DeviceCommand.OpenSettings(SettingsScreen.MAIN)
            else -> null
        }
    }

    // "alarm lagao", "subah 7 baje alarm lagao", "shaam 7 baje ka alarm lagao",
    // "7:30 ka alarm lagao", "alarm set karo"
    private fun parseAlarm(text: String): DeviceCommand.SetAlarm? {
        if (!text.contains("alarm")) return null
        val isSetCommand = text.contains("lagao") || text.contains("laga do") ||
            text.contains("set karo") || text.contains("set kar do") ||
            text.contains("lagana") || text == "alarm"
        if (!isSetCommand) return null

        val (hour, minute) = parseAlarmTime(text) ?: return DeviceCommand.SetAlarm(0, 0, false)
        return DeviceCommand.SetAlarm(hour, minute, true)
    }

    /** Returns (hour 0-23, minute) or null when no time is mentioned. */
    private fun parseAlarmTime(text: String): Pair<Int, Int>? {
        // "7 baj kar 30 minute" / "saat baj kar tees minute" (digits only for minutes)
        val bajKar = Regex("(\\d{1,2})\\s*baj\\s*kar\\s*(\\d{1,2})").find(text)
        if (bajKar != null) {
            val hour = adjustForDayPart(bajKar.groupValues[1].toInt(), text)
            return hour to bajKar.groupValues[2].toInt().coerceIn(0, 59)
        }

        // "7:30" / "7:30 baje"
        val colon = Regex("(\\d{1,2}):(\\d{2})").find(text)
        if (colon != null) {
            val hour = adjustForDayPart(colon.groupValues[1].toInt(), text)
            return hour to colon.groupValues[2].toInt().coerceIn(0, 59)
        }

        // "7 baje" / "saat baje"
        val digitHour = Regex("(\\d{1,2})\\s*baje?").find(text)
        if (digitHour != null) {
            return adjustForDayPart(digitHour.groupValues[1].toInt(), text) to 0
        }
        for ((word, hour) in urduNumbers) {
            if (Regex("\\b$word\\s*baje?\\b").containsMatchIn(text)) {
                return adjustForDayPart(hour, text) to 0
            }
        }
        return null
    }

    /** "shaam"/"raat"/"dopahar" + hour < 12 means PM; "subah" means AM. */
    private fun adjustForDayPart(hour: Int, text: String): Int {
        if (hour !in 1..12) return hour.coerceIn(0, 23)
        val isPm = text.contains("shaam") || text.contains("raat") ||
            text.contains("dopahar") || text.contains("evening") || text.contains("night")
        if (!isPm) return hour % 24
        return if (hour == 12) 12 else hour + 12
    }

    // "ali ko call karo", "03001234567 ko call karo", "call ali"
    private fun parseCall(text: String): DeviceCommand.Dial? {
        val koCall = Regex("^(.+?)\\s+ko\\s+call\\s+(?:karo|kar\\s+do|lagao|laga\\s+do)$").find(text)
        if (koCall != null) {
            val target = koCall.groupValues[1].trim()
            if (target.isNotEmpty()) return DeviceCommand.Dial(target)
        }
        val callX = Regex("^call\\s+(.+)$").find(text)
        if (callX != null) {
            val target = callX.groupValues[1].trim()
            if (target.isNotEmpty()) return DeviceCommand.Dial(target)
        }
        return null
    }

    // "flashlight on karo", "torch band karo", "flashlight off kar do"
    private fun parseFlashlight(text: String): DeviceCommand.Flashlight? {
        if (!text.contains("flashlight") && !text.contains("torch")) return null
        val off = text.contains("off") || text.contains("band") || text.contains("bujhao") ||
            text.contains("bujha do")
        val on = text.contains("on") || text.contains("jalao") || text.contains("jla do") ||
            text.contains("khol do") || text.contains("kholo")
        if (!off && !on) return null
        return DeviceCommand.Flashlight(on = !off)
    }

    // "volume up karo", "volume tez karo", "volume kam karo", "volume down karo"
    private fun parseVolume(text: String): DeviceCommand? {
        if (!text.contains("volume")) return null
        return when {
            text.contains("up") || text.contains("tez") || text.contains("badhao") ||
                text.contains("barhao") || text.contains("zyada") || text.contains("loud") ->
                DeviceCommand.VolumeUp
            text.contains("down") || text.contains("kam") || text.contains("ghatao") ||
                text.contains("halka") || text.contains("low") ->
                DeviceCommand.VolumeDown
            else -> null
        }
    }

    // V14 — "wifi on karo" / "mobile data off karo" / "bluetooth on karo" /
    // "location on karo" / "battery saver on karo". Android gives NO silent
    // toggle for any of these, so each one opens the right system panel or
    // dialog and the user taps once. Runs after parseSettings (which owns
    // the "... settings kholo" phrases) and before parseOpenApp.
    private fun parseTogglePanel(text: String): DeviceCommand? {
        val mentionsToggle = text.contains("on karo") || text.contains("on kar do") ||
            text.contains("off karo") || text.contains("off kar do") ||
            text.contains("chalao") || text.contains("band karo")
        if (!mentionsToggle) return null
        val isOff = text.contains("off karo") || text.contains("off kar do") ||
            text.contains("band karo")
        return when {
            text.contains("wifi") || text.contains("mobile data") ||
                text.contains("data on") || text.contains("data off") ||
                text.contains("internet") -> DeviceCommand.InternetPanel
            text.contains("bluetooth") ->
                DeviceCommand.BluetoothToggle(on = !isOff)
            text.contains("location") || text.contains("gps") ->
                DeviceCommand.OpenSettings(SettingsScreen.LOCATION)
            text.contains("battery saver") ->
                DeviceCommand.OpenSettings(SettingsScreen.BATTERY_SAVER)
            else -> null
        }
    }

    // V14 — "ali ko direct call karo" / "direct call ali" / "direct call karo ali".
    // Runs before parseCall: "direct call" must not fall through to the
    // dialer-only Dial command.
    private fun parseDirectCall(text: String): DeviceCommand.DirectCall? {
        val koDirect = Regex(
            "^(.+?)\\s+ko\\s+direct\\s+call\\s+(?:karo|kar\\s+do|lagao|laga\\s+do)$"
        ).find(text)
        if (koDirect != null) {
            val target = koDirect.groupValues[1].trim()
            if (target.isNotEmpty()) return DeviceCommand.DirectCall(target)
        }
        val directX = Regex("^direct\\s+call\\s+(?:karo\\s+)?(.+)$").find(text)
        if (directX != null) {
            val target = directX.groupValues[1].trim()
            if (target.isNotEmpty()) return DeviceCommand.DirectCall(target)
        }
        return null
    }

    // V14 — "brightness kam karo" / "brightness barhao" / "brightness 50 kar do" /
    // "brightness full karo" / "screen bright karo" / "screen dim karo".
    private fun parseBrightness(text: String): DeviceCommand.Brightness? {
        val mentionsBrightness = text.contains("brightness") ||
            (text.contains("screen") && (text.contains("bright") || text.contains("dim") ||
                text.contains("roshni") || text.contains("andhera")))
        if (!mentionsBrightness) return null
        val digits = Regex("(\\d{1,3})\\s*%?").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (digits != null) {
            return DeviceCommand.Brightness(level = digits.coerceIn(0, 100))
        }
        return when {
            text.contains("full") || text.contains("poori") ->
                DeviceCommand.Brightness(level = 100)
            text.contains("half") || text.contains("aadhi") ->
                DeviceCommand.Brightness(level = 50)
            text.contains("low") ->
                DeviceCommand.Brightness(level = 20)
            text.contains("kam") || text.contains("ghatao") || text.contains("dim") ||
                text.contains("andhera") ->
                DeviceCommand.Brightness(delta = -20)
            text.contains("barhao") || text.contains("badhao") || text.contains("zyada") ||
                text.contains("bright") || text.contains("roshni") ->
                DeviceCommand.Brightness(delta = 20)
            else -> null
        }
    }

    // V14 — "dnd on karo" / "do not disturb on karo" / "dnd band karo" /
    // "disturb mat karo".
    private fun parseDoNotDisturb(text: String): DeviceCommand.DoNotDisturb? {
        val mentionsDnd = text.contains("dnd") || text.contains("do not disturb") ||
            text.contains("disturb")
        if (!mentionsDnd) return null
        val off = text.contains("off") || text.contains("band") || text.contains("khatam")
        val on = text.contains("on") || text.contains("lagao") || text.contains("mat karo") ||
            text.contains("na karo") || text.contains("chalu")
        if (!off && !on) return null
        return DeviceCommand.DoNotDisturb(on = !off)
    }

    // V14 — "notifications parho" / "mere notifications parho" /
    // "whatsapp ko reply karo hello" / "reply karo theek hai".
    private fun parseNotifications(text: String): DeviceCommand? {
        if (text.contains("notification")) {
            val wantsRead = text.contains("parho") || text.contains("parh") ||
                text.contains("dikhao") || text.contains("batao") ||
                text.contains("sunao") || text.contains("dekho") ||
                text.contains("check karo")
            if (wantsRead) return DeviceCommand.ReadNotifications
        }
        val replyWithApp = Regex(
            "^(.+?)\\s+(?:ko\\s+|par\\s+)?reply\\s+karo\\s+(.+)$"
        ).find(text)
        if (replyWithApp != null) {
            val rawHint = replyWithApp.groupValues[1].trim()
            val message = replyWithApp.groupValues[2].trim()
            if (message.isNotEmpty()) {
                val hint = when (rawHint) {
                    "usko", "us ko", "isko", "is ko", "ise", "usey" -> null
                    else -> rawHint
                }
                return DeviceCommand.ReplyNotification(hint, message)
            }
        }
        val replyBare = Regex("^reply\\s+karo\\s+(.+)$").find(text)
        if (replyBare != null) {
            val message = replyBare.groupValues[1].trim()
            if (message.isNotEmpty()) {
                return DeviceCommand.ReplyNotification(null, message)
            }
        }
        return null
    }

    // V14 (P2) — "konsi app khuli hai" / "aaj sab se zyada konsi app use ki" /
    // "screen time batao".
    private fun parseUsage(text: String): DeviceCommand? {
        if ((text.contains("konsi app") || text.contains("which app")) &&
            (text.contains("khuli") || text.contains("chal rahi") ||
                text.contains("open") || text.contains("front"))
        ) {
            return DeviceCommand.CurrentApp
        }
        if (text.contains("screen time") ||
            (text.contains("aaj") && text.contains("sab se zyada") &&
                text.contains("app"))
        ) {
            return DeviceCommand.TopApps
        }
        return null
    }

    // "whatsapp kholo", "chrome kholo", "open youtube", "gallery kholo"
    private fun parseOpenApp(text: String): DeviceCommand.OpenApp? {
        val kholo = Regex("^(.+?)\\s+(?:kholo|khol\\s+do|open\\s+karo|open\\s+kar\\s+do)$").find(text)
        if (kholo != null) {
            val name = kholo.groupValues[1].trim()
            if (name.isNotEmpty()) return DeviceCommand.OpenApp(name)
        }
        val openX = Regex("^open\\s+(.+)$").find(text)
        if (openX != null) {
            val name = openX.groupValues[1].trim()
            if (name.isNotEmpty()) return DeviceCommand.OpenApp(name)
        }
        return null
    }
}
