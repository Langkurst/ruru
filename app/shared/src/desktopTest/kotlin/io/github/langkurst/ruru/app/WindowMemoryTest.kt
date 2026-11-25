package io.github.langkurst.ruru.app

import java.awt.Rectangle
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowMemoryTest {
    private val node: Preferences = Preferences.userRoot().node("io/github/langkurst/ruru/test-" + UUID.randomUUID())

    @AfterTest
    fun cleanUp() {
        node.removeNode()
    }

    @Test
    fun `a window the person did not resize is not kept below the default size`() {
        val memory = WindowMemory(node)
        memory.save(Rectangle(40, 40, 600, 500), maximised = false, userResized = false)
        assertFalse(node.getBoolean("saved", false))
    }

    @Test
    fun `a window the person made smaller is kept`() {
        val memory = WindowMemory(node)
        memory.save(Rectangle(40, 40, 600, 500), maximised = false, userResized = true)
        assertEquals(600, node.getInt("w", 0))
    }

    @Test
    fun `the minimum size at 0,0 is never saved, even after a resize`() {
        val memory = WindowMemory(node)
        memory.save(Rectangle(0, 0, WindowMemory.MIN_WIDTH, WindowMemory.MIN_HEIGHT), maximised = false, userResized = true)
        assertFalse(node.getBoolean("saved", false))
    }

    @Test
    fun `a normal window is kept and read back`() {
        val memory = WindowMemory(node)
        memory.save(Rectangle(100, 100, 1100, 720), maximised = true, userResized = false)
        assertTrue(node.getBoolean("saved", false))
        assertTrue(WindowMemory.worthKeeping(Rectangle(100, 100, 1500, 900), userResized = false))
    }

    @Test
    fun `the leftover of the old warm-up run is ignored on load`() {
        node.putBoolean("saved", true)
        node.putInt("x", 0); node.putInt("y", 0); node.putInt("w", 360); node.putInt("h", 480)
        assertNull(WindowMemory(node).load())
    }

    @Test
    fun `no settings node means nothing is remembered`() {
        val memory = WindowMemory(null)
        memory.save(Rectangle(100, 100, 1100, 720), maximised = false, userResized = true)
        assertNull(memory.load())
    }

    @Test
    fun `the warm-up switch gives no settings node, so tray and window write nothing`() {
        PrefsRoot.disabled = true
        try {
            assertNull(PrefsRoot.node("window"))
            val tray = TrayPreference()
            assertFalse(tray.takeFirstTrayNotice())
            tray.keepRunning = false
            assertTrue(tray.keepRunning) // nothing was stored, so the default stands
            assertNull(WindowMemory().load())
        } finally {
            PrefsRoot.disabled = false
        }
    }
}
