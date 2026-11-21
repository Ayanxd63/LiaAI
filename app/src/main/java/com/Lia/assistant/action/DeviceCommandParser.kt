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
}

enum class SettingsScreen {
    MAIN, WIFI, BLUETOOTH, DATE_TIME, DISPLAY, SOUND, LOCATION, APPS, STORAGE
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
        parseAlarm(text)?.let { return it }
        parseCall(text)?.let { return it }
        parseFlashlight(text)?.let { return it }
        parseVolume(text)?.let { return it }
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
