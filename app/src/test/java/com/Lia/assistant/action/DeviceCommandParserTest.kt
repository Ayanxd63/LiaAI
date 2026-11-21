package com.Lia.assistant.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCommandParserTest {

    // ------------------------------------------------------------ YouTube search

    @Test
    fun youtubeSearchRomanUrdu() {
        assertEquals(
            DeviceCommand.YouTubeSearch("cat video"),
            DeviceCommandParser.parse("youtube par cat video search karo")
        )
        assertEquals(
            DeviceCommand.YouTubeSearch("ali zafar ke gaane"),
            DeviceCommandParser.parse("YouTube pe ali zafar ke gaane dikhao")
        )
        assertEquals(
            DeviceCommand.YouTubeSearch("funny cat"),
            DeviceCommandParser.parse("youtube mein funny cat search kar do")
        )
    }

    @Test
    fun youtubeSearchEnglish() {
        assertEquals(
            DeviceCommand.YouTubeSearch("cat video"),
            DeviceCommandParser.parse("youtube search cat video")
        )
        assertEquals(
            DeviceCommand.YouTubeSearch("cat video"),
            DeviceCommandParser.parse("search on youtube for cat video")
        )
    }

    @Test
    fun youtubeOpenIsNotSearch() {
        // "youtube kholo" opens the app, it is not a search.
        assertEquals(
            DeviceCommand.OpenApp("youtube"),
            DeviceCommandParser.parse("youtube kholo")
        )
    }

    // ------------------------------------------------------------ Open app

    @Test
    fun openApp() {
        assertEquals(DeviceCommand.OpenApp("whatsapp"), DeviceCommandParser.parse("whatsapp kholo"))
        assertEquals(DeviceCommand.OpenApp("chrome"), DeviceCommandParser.parse("chrome kholo"))
        assertEquals(DeviceCommand.OpenApp("gallery"), DeviceCommandParser.parse("gallery kholo"))
        assertEquals(DeviceCommand.OpenApp("youtube"), DeviceCommandParser.parse("open youtube"))
        assertEquals(DeviceCommand.OpenApp("camera"), DeviceCommandParser.parse("camera khol do"))
    }

    // ------------------------------------------------------------ Settings

    @Test
    fun openSettings() {
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.WIFI),
            DeviceCommandParser.parse("wifi settings kholo")
        )
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.BLUETOOTH),
            DeviceCommandParser.parse("bluetooth settings kholo")
        )
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.DISPLAY),
            DeviceCommandParser.parse("display settings kholo")
        )
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.SOUND),
            DeviceCommandParser.parse("sound settings kholo")
        )
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.MAIN),
            DeviceCommandParser.parse("settings kholo")
        )
    }

    @Test
    fun timeChangeOpensDateTimeSettings() {
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.DATE_TIME),
            DeviceCommandParser.parse("mera time change kar do")
        )
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.DATE_TIME),
            DeviceCommandParser.parse("time settings kholo")
        )
        assertEquals(
            DeviceCommand.OpenSettings(SettingsScreen.DATE_TIME),
            DeviceCommandParser.parse("date time settings kholo")
        )
    }

    // ------------------------------------------------------------ Alarm

    @Test
    fun alarmWithTime() {
        assertEquals(
            DeviceCommand.SetAlarm(7, 0, true),
            DeviceCommandParser.parse("subah 7 baje alarm lagao")
        )
        assertEquals(
            DeviceCommand.SetAlarm(19, 0, true),
            DeviceCommandParser.parse("shaam 7 baje ka alarm lagao")
        )
        assertEquals(
            DeviceCommand.SetAlarm(7, 30, true),
            DeviceCommandParser.parse("7:30 ka alarm lagao")
        )
        assertEquals(
            DeviceCommand.SetAlarm(7, 0, true),
            DeviceCommandParser.parse("saat baje alarm lagao")
        )
    }

    @Test
    fun alarmWithoutTime() {
        assertEquals(
            DeviceCommand.SetAlarm(0, 0, false),
            DeviceCommandParser.parse("alarm lagao")
        )
    }

    // ------------------------------------------------------------ Call / torch / volume

    @Test
    fun callCommands() {
        assertEquals(DeviceCommand.Dial("ali"), DeviceCommandParser.parse("ali ko call karo"))
        assertEquals(
            DeviceCommand.Dial("03001234567"),
            DeviceCommandParser.parse("03001234567 ko call karo")
        )
        assertEquals(DeviceCommand.Dial("ami"), DeviceCommandParser.parse("call ami"))
    }

    @Test
    fun flashlightCommands() {
        assertEquals(DeviceCommand.Flashlight(true), DeviceCommandParser.parse("flashlight on karo"))
        assertEquals(DeviceCommand.Flashlight(false), DeviceCommandParser.parse("torch band karo"))
        assertEquals(DeviceCommand.Flashlight(false), DeviceCommandParser.parse("flashlight off kar do"))
    }

    @Test
    fun volumeCommands() {
        assertEquals(DeviceCommand.VolumeUp, DeviceCommandParser.parse("volume up karo"))
        assertEquals(DeviceCommand.VolumeUp, DeviceCommandParser.parse("volume tez karo"))
        assertEquals(DeviceCommand.VolumeDown, DeviceCommandParser.parse("volume kam karo"))
        assertEquals(DeviceCommand.VolumeDown, DeviceCommandParser.parse("volume down karo"))
    }

    // ------------------------------------------------------------ Non-commands

    @Test
    fun ordinaryChatIsNotADeviceCommand() {
        assertNull(DeviceCommandParser.parse("mujhe girlfriend chahiye"))
        assertNull(DeviceCommandParser.parse("aaj ka mausam kaisa hai"))
        assertNull(DeviceCommandParser.parse("time kya hai"))
        assertNull(DeviceCommandParser.parse("mujhe aik kahani sunao"))
        assertNull(DeviceCommandParser.parse("hello"))
        assertNull(DeviceCommandParser.parse(""))
    }
}
