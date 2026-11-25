package io.github.langkurst.ruru.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayPreferenceTest {
    private val node: Preferences = Preferences.userRoot().node("io/github/langkurst/ruru/test-" + UUID.randomUUID())

    @AfterTest
    fun cleanUp() {
        node.removeNode()
    }

    @Test
    fun `keeping Ruru in the tray is on until the person turns it off`() {
        val pref = TrayPreference(node)
        assertTrue(pref.keepRunning)
        pref.keepRunning = false
        assertFalse(TrayPreference(node).keepRunning) // remembered
    }

    @Test
    fun `closing the window goes to the tray only with a tray and the setting on`() {
        val pref = TrayPreference(node)
        assertEquals(CloseAction.CloseToTray, pref.closeAction(hasTray = true))
        assertEquals(CloseAction.Quit, pref.closeAction(hasTray = false))
        pref.keepRunning = false
        assertEquals(CloseAction.Quit, pref.closeAction(hasTray = true))
    }

    @Test
    fun `the still running notice is given once`() {
        val pref = TrayPreference(node)
        assertTrue(pref.takeFirstTrayNotice())
        assertFalse(pref.takeFirstTrayNotice())
        assertFalse(TrayPreference(node).takeFirstTrayNotice())
    }
}
