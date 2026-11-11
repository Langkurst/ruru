package io.github.langkurst.ruru.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.RURU_VERSION
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
import java.awt.Dimension
import java.awt.SystemTray
import java.util.concurrent.atomic.AtomicBoolean
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(platformForThisSystem(), scope, startInDemo = "--demo" in args)
    model.start()
    val hasTray = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    val window = WindowHost(model)

    if (hasTray) {
        TrayMenu(model, scope, onOpen = window::open, onQuit = {
            model.stop()
            exitProcess(0)
        }).install()
        if ("--hidden" !in args) window.open()
    } else {
        // No tray (some Linux desktops): the window is the app, and closing it quits.
        window.openBlocking()
        model.stop()
        exitProcess(0)
    }
}

/** Opens the window on demand, one at a time; closing it releases everything it used. */
private class WindowHost(private val model: AppModel) {
    private val open = AtomicBoolean(false)
    private val reduceMotion = os.startsWith("Windows") && !WinSystem.animationsEnabled()
    private val extras = listOfNotNull(StartWithWindows.switchOrNull())

    fun open() {
        if (!open.compareAndSet(false, true)) return
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

    fun openBlocking() = application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ruru",
            icon = OwlPainter(),
            state = rememberWindowState(width = 400.dp, height = 780.dp, position = WindowPosition(Alignment.Center)),
        ) {
            window.minimumSize = Dimension(360, 480)
            RuruTheme(reduceMotion = reduceMotion) {
                RuruScreen(model, RURU_VERSION, extras = extras)
            }
        }
    }
}

private fun platformForThisSystem(): HeadsetPlatform = when {
    os.startsWith("Windows") -> WindowsHeadsetPlatform()
    os.startsWith("Linux") -> LinuxHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("Linux without BlueZ")
    os.startsWith("Mac") -> MacHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("macOS")
    else -> UnsupportedPlatform(os.ifBlank { "this system" })
}
