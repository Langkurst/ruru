package io.github.langkurst.ruru.app

import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.util.prefs.Preferences

/**
 * Where the window was last, so Ruru opens where the person left it. Kept per user (java.util.prefs).
 * With no settings node (the build's warm-up run) it remembers nothing and returns nothing.
 */
class WindowMemory(private val prefs: Preferences? = PrefsRoot.node("window")) {
    class Saved(val bounds: Rectangle, val maximised: Boolean)

    /** The remembered window, or null when there is none or it would open off every screen. */
    fun load(): Saved? = runCatching {
        val p = prefs ?: return null
        if (!p.getBoolean("saved", false)) return null
        val bounds = Rectangle(p.getInt("x", 0), p.getInt("y", 0), p.getInt("w", 0), p.getInt("h", 0))
        if (bounds.width < MIN_WIDTH || bounds.height < MIN_HEIGHT) return null
        // What a window that never opened properly leaves behind (0.2.0's build wrote it): not a place anyone chose.
        if (isPlaceholder(bounds)) return null
        if (!onSomeScreen(bounds)) return null
        // A screen smaller than the one the window was saved on (or a taskbar added since) must not cut it off.
        Saved(fitTo(bounds, workAreaFor(bounds) ?: return null), p.getBoolean("max", false))
    }.getOrNull()

    /**
     * Remembers [bounds] (the window when not maximised). A size under the default is kept only when
     * the person really resized the window ([userResized]); a window that has not been drawn yet, or one
     * the system squeezed, must not become the next start's size.
     */
    fun save(bounds: Rectangle, maximised: Boolean, userResized: Boolean) {
        if (!worthKeeping(bounds, userResized)) return
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

    companion object {
        /** How much of the screen's work area (the screen minus the taskbar or menu bar) a first window may take. */
        private const val FIRST_WINDOW_SHARE = 0.92

        /**
         * Where the very first window opens: 1100 x 720, or 92% of the work area when that is smaller (a 1024 x 768
         * screen got a window 1100 wide, cut off at the right), centred in the work area.
         */
        fun defaultBounds(work: Rectangle): Rectangle {
            val w = minOf(DEFAULT_WIDTH, (work.width * FIRST_WINDOW_SHARE).toInt()).coerceAtLeast(minOf(MIN_WIDTH, work.width))
            val h = minOf(DEFAULT_HEIGHT, (work.height * FIRST_WINDOW_SHARE).toInt()).coerceAtLeast(minOf(MIN_HEIGHT, work.height))
            return Rectangle(work.x + (work.width - w) / 2, work.y + (work.height - h) / 2, w, h)
        }

        /** [bounds] moved and, if need be, made smaller so that the whole window is inside [work]. */
        fun fitTo(bounds: Rectangle, work: Rectangle): Rectangle {
            val w = minOf(bounds.width, work.width)
            val h = minOf(bounds.height, work.height)
            return Rectangle(
                bounds.x.coerceIn(work.x, work.x + work.width - w),
                bounds.y.coerceIn(work.y, work.y + work.height - h),
                w,
                h,
            )
        }

        private fun workArea(config: GraphicsConfiguration): Rectangle {
            val screen = config.bounds
            val edge = Toolkit.getDefaultToolkit().getScreenInsets(config)
            return Rectangle(screen.x + edge.left, screen.y + edge.top, screen.width - edge.left - edge.right, screen.height - edge.top - edge.bottom)
        }

        /** The main screen's work area. Where there is no screen to ask, a common laptop one. */
        fun primaryWorkArea(): Rectangle = runCatching {
            workArea(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration)
        }.getOrDefault(Rectangle(0, 0, 1920, 1040))

        /** The work area of the screen that holds most of [bounds]. */
        internal fun workAreaFor(bounds: Rectangle): Rectangle? = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { workArea(it.defaultConfiguration) }
                .maxByOrNull { val i = it.intersection(bounds); if (i.isEmpty) 0L else i.width.toLong() * i.height }
        }.getOrNull()

        const val DEFAULT_WIDTH = 1100
        const val DEFAULT_HEIGHT = 720
        const val MIN_WIDTH = 360
        const val MIN_HEIGHT = 480

        /** The minimum size at the very corner of the screen: the signature of a window that was never really shown. */
        internal fun isPlaceholder(b: Rectangle) = b.x == 0 && b.y == 0 && b.width == MIN_WIDTH && b.height == MIN_HEIGHT

        internal fun worthKeeping(b: Rectangle, userResized: Boolean): Boolean {
            if (isPlaceholder(b)) return false
            if (b.width < MIN_WIDTH || b.height < MIN_HEIGHT) return false
            if ((b.width < DEFAULT_WIDTH || b.height < DEFAULT_HEIGHT) && !userResized) return false
            return true
        }
    }
}
