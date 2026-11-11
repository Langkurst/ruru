package io.github.langkurst.ruru.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.app.SingleInstance
import io.github.langkurst.ruru.app.UnsupportedPlatform
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.platform.linux.LinuxHeadsetPlatform
import io.github.langkurst.ruru.platform.macos.MacHeadsetPlatform
import io.github.langkurst.ruru.platform.windows.WinSystem
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import io.github.langkurst.ruru.ui.OwlPainter
import io.github.langkurst.ruru.ui.RuruScreen
import io.github.langkurst.ruru.ui.RuruTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.SystemTray
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

private val os: String = System.getProperty("os.name").orEmpty()

/**
 * Ruru on the desktop. The tray icon is plain AWT, so starting hidden (as "Start with Windows"
 * does) never loads the UI toolkit; the window is created when opened and fully released when
 * closed. The headphone connection lives in [AppModel] the whole time.
 */
fun main(args: Array<String>) {
    if (System.getenv("RURU_DEBUG") == "1") {
        Logging.sink = { _, tag, msg, t -> System.err.println("$tag $msg${t?.let { " (${it.message})" } ?: ""}") }
    }
    // One Ruru per person. Opening it again (the desktop shortcut while it sits in the tray)
    // shows the running copy's window instead of starting a second copy beside it.
    val single = SingleInstance(SingleInstance.defaultDir())
    val show = AtomicReference<() -> Unit> {}
    val onlyCopy = runCatching { single.claim { show.get()() } }.getOrDefault(true)
    if (!onlyCopy) {
        if ("--hidden" !in args) runCatching { single.askToShow() }
        exitProcess(0)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(platformForThisSystem(), scope, startInDemo = "--demo" in args)
    model.start()
    val hasTray = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    val window = WindowHost(model)
    show.set(window::open)
    openOnDockClick(window::open)

    if (hasTray) {
        TrayMenu(model, scope, onOpen = window::open, onQuit = {
            model.stop()
            exitProcess(0)
        }).install()
        if ("--hidden" !in args) window.open()
    } else {
        // No tray (some Linux desktops): the window is the app, and closing it quits.
        window.openAndWait()
        model.stop()
        exitProcess(0)
    }
}

/** macOS: clicking the Dock icon while the window is closed opens it again. */
private fun openOnDockClick(open: () -> Unit) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener(AppReopenedListener { open() })
        }
    }
}

/** Opens the window on demand, one at a time; closing it releases everything it used. */
private class WindowHost(private val model: AppModel) {
    private val open = AtomicBoolean(false)
    private val reduceMotion = os.startsWith("Windows") && !WinSystem.animationsEnabled()
    private val extras = listOfNotNull(StartWithWindows.switchOrNull())

    @Volatile
    private var frame: Frame? = null

    /** Opens the window, or brings it to the front if it is already open. */
    fun open() {
        if (!open.compareAndSet(false, true)) {
            bringToFront()
            return
        }
        Thread({
            try {
                openBlocking()
            } finally {
                open.set(false)
                Thread.sleep(1_500)
                System.gc() // hand the window's freed heap back to the system
            }
        }, "ruru-window").start()
    }

    /** Opens the window and returns once it has been closed. */
    fun openAndWait() {
        if (!open.compareAndSet(false, true)) return
        try {
            openBlocking()
        } finally {
            open.set(false)
        }
    }

    private fun openBlocking() = application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ruru",
            icon = OwlPainter(),
            state = rememberWindowState(width = 400.dp, height = 780.dp, position = WindowPosition(Alignment.Center)),
        ) {
            window.minimumSize = Dimension(360, 480)
            DisposableEffect(Unit) {
                frame = window
                // A window opened for a second launch belongs to the copy in the tray, which Windows
                // would otherwise show behind whatever is in front. Raise it once it is on screen.
                val raise = object : WindowAdapter() {
                    override fun windowOpened(e: WindowEvent) = bringToFront()
                }
                window.addWindowListener(raise)
                if (window.isShowing) bringToFront() // already on screen: its "opened" event has been and gone
                onDispose {
                    window.removeWindowListener(raise)
                    frame = null
                }
            }
            RuruTheme(reduceMotion = reduceMotion) {
                RuruScreen(model, RURU_VERSION, extras = extras)
            }
        }
    }

    private fun bringToFront() = SwingUtilities.invokeLater {
        val shown = frame ?: return@invokeLater
        if (shown.extendedState and Frame.ICONIFIED != 0) shown.extendedState = shown.extendedState and Frame.ICONIFIED.inv()
        // Windows only lets a program lift its own window over another program's by making it
        // "always on top". Hold that for a moment so it takes effect, then let it go.
        val lift = os.startsWith("Windows")
        if (lift) shown.isAlwaysOnTop = true
        // toFront() activates the window and hands keyboard focus back to its content. Don't call
        // requestFocus() on the frame: that gives focus to the frame itself, and the keyboard then
        // does nothing until the window is clicked.
        shown.toFront()
        if (lift) Timer(RAISE_HOLD_MS) { if (shown.isDisplayable) shown.isAlwaysOnTop = false }.apply { isRepeats = false }.start()
    }

    private companion object {
        const val RAISE_HOLD_MS = 400
    }
}

private fun platformForThisSystem(): HeadsetPlatform = when {
    os.startsWith("Windows") -> WindowsHeadsetPlatform()
    os.startsWith("Linux") -> LinuxHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("Linux without BlueZ")
    os.startsWith("Mac") -> MacHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("macOS")
    else -> UnsupportedPlatform(os.ifBlank { "this system" })
}
