package io.github.langkurst.ruru.desktop.chrome

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.prefs.Preferences

/** Where the window was last, so Ruru opens where the person left it. Kept per user (java.util.prefs). */
object WindowMemory {
    const val DEFAULT_WIDTH = 1100
    const val DEFAULT_HEIGHT = 720
    const val MIN_WIDTH = 360
    const val MIN_HEIGHT = 480

    class Saved(val bounds: Rectangle, val maximised: Boolean)

    private val prefs: Preferences? = runCatching { Preferences.userRoot().node("io/github/langkurst/ruru/window") }.getOrNull()

    /** The remembered window, or null when there is none or it would open off every screen. */
    fun load(): Saved? = runCatching {
        val p = prefs ?: return null
        if (!p.getBoolean("saved", false)) return null
        val bounds = Rectangle(p.getInt("x", 0), p.getInt("y", 0), p.getInt("w", 0), p.getInt("h", 0))
        if (bounds.width < MIN_WIDTH || bounds.height < MIN_HEIGHT) return null
        if (!onSomeScreen(bounds)) return null
        Saved(bounds, p.getBoolean("max", false))
    }.getOrNull()

    fun save(bounds: Rectangle, maximised: Boolean) {
        runCatching {
            val p = prefs ?: return
            p.putBoolean("saved", true)
            p.putInt("x", bounds.x)
            p.putInt("y", bounds.y)
            p.putInt("w", bounds.width)
            p.putInt("h", bounds.height)
            p.putBoolean("max", maximised)
            p.flush()
        }
    }

    /** A screen has to show a good part of the title bar's strip, or the window could not be grabbed. */
    private fun onSomeScreen(bounds: Rectangle): Boolean {
        val strip = Rectangle(bounds.x + 80, bounds.y, (bounds.width - 160).coerceAtLeast(40), 40)
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.contains(strip) }
    }
}
