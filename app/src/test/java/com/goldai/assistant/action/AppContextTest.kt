package com.goldai.assistant.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContextTest {

    @Test
    fun recordAndCurrent() {
        AppContext.clear()
        assertNull(AppContext.current())
        AppContext.record("youtube")
        assertEquals("youtube", AppContext.current())
        AppContext.record("WhatsApp")
        assertEquals("whatsapp", AppContext.current())
        AppContext.clear()
        assertNull(AppContext.current())
    }

    @Test
    fun externalLaunchMarker() {
        AppContext.clear()
        assertFalse(AppContext.wasExternalLaunchRecent())
        AppContext.markExternalLaunch()
        assertTrue(AppContext.wasExternalLaunchRecent())
        assertTrue(AppContext.wasExternalLaunchRecent(60_000L))
        AppContext.clear()
        assertFalse(AppContext.wasExternalLaunchRecent())
    }
}
