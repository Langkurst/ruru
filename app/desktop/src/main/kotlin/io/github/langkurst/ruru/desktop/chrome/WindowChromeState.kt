package io.github.langkurst.ruru.desktop.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.langkurst.ruru.platform.windows.WinWindowChrome
import java.awt.Window
import java.awt.event.WindowEvent

/** How the window's title bar is made. */
enum class ChromeKind {
    /** The system's own title bar (Linux, or when Ruru could not draw its own). */
    None,

    /** Ruru draws the bar and three caption buttons; Windows still treats the window as normal. */
    Windows,

    /** Ruru draws the bar under the real traffic lights, which stay where macOS puts them. */
    Mac,
}

/**
 * What the title bar needs to know about its window, and how it asks the window to do things.
 * One per open window. The native parts exist only for [ChromeKind.Windows].
 */
class WindowChromeState(val kind: ChromeKind, private val closeWindow: () -> Unit) {
    var maximised by mutableStateOf(false)
        internal set
    var maxHover by mutableStateOf(false)
        internal set
    var maxPressed by mutableStateOf(false)
        internal set

    /** How much of the window's outer size is off screen at the right and bottom (maximised on Windows). */
    var insetRightPx by mutableIntStateOf(0)
        internal set
    var insetBottomPx by mutableIntStateOf(0)
        internal set

    @Volatile
    internal var native: WinWindowChrome? = null

    @Volatile
    internal var awt: Window? = null

    val isMaximised: Boolean get() = native?.maximised ?: false

    fun minimise() { native?.minimise() }

    fun toggleMaximise() { native?.toggleMaximise() }

    fun close() {
        awt?.dispatchEvent(WindowEvent(awt, WindowEvent.WINDOW_CLOSING)) ?: closeWindow()
    }

    internal val listener = object : WinWindowChrome.Listener {
        override fun maxButtonHover(hover: Boolean) { maxHover = hover }
        override fun maxButtonPressed(pressed: Boolean) { maxPressed = pressed }
        override fun frameChanged(maximised: Boolean, insetRightPx: Int, insetBottomPx: Int) {
            this@WindowChromeState.maximised = maximised
            this@WindowChromeState.insetRightPx = insetRightPx
            this@WindowChromeState.insetBottomPx = insetBottomPx
        }
    }
}
