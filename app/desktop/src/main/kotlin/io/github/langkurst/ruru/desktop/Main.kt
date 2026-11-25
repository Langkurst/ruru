package io.github.langkurst.ruru.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.app.SingleInstance
import io.github.langkurst.ruru.app.UnsupportedPlatform
import io.github.langkurst.ruru.app.CloseAction
import io.github.langkurst.ruru.app.TrayPreference
import io.github.langkurst.ruru.desktop.chrome.CaptionButtonWidth
import io.github.langkurst.ruru.desktop.chrome.ChromeKind
import io.github.langkurst.ruru.desktop.chrome.RuruWindowFrame
import io.github.langkurst.ruru.desktop.chrome.TitleBarHeight
import io.github.langkurst.ruru.desktop.chrome.WindowChromeState
import io.github.langkurst.ruru.desktop.chrome.WindowMemory
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.platform.linux.LinuxHeadsetPlatform
import io.github.langkurst.ruru.platform.macos.MacHeadsetPlatform
import io.github.langkurst.ruru.platform.windows.WinSystem
import io.github.langkurst.ruru.platform.windows.WinWindowChrome
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import io.github.langkurst.ruru.ui.DarkColors
import io.github.langkurst.ruru.ui.LightColors
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private val os: String = System.getProperty("os.name").orEmpty()

/**
 * Ruru on the desktop. The tray icon is plain AWT, so starting hidden (as "Start with Windows"
 * does) never loads the UI toolkit; the window is created when opened and fully released when
 * closed. The headphone connection lives in [AppModel] the whole time.
 */
fun main(args: Array<String>) {
    // A small settings window draws fine in software: measured on Windows, 125 MB with the window
    // open against 198 MB on DirectX and 374 MB on OpenGL, and less CPU. Not on macOS: it has no
    // SOFTWARE_FAST, and the window failed to open there when this was set for every system.
    if (!os.startsWith("Mac") && System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    }
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
    val quit = {
        model.stop()
        exitProcess(0)
    }
    var trayMenu: TrayMenu? = null
    val window = WindowHost(model, TrayPreference(), hasTray, onQuit = quit, onFirstTrayClose = { trayMenu?.notifyStillRunning() })
    show.set(window::open)
    macAppHooks(window::open)

    if (hasTray) {
        trayMenu = TrayMenu(model, scope, onOpen = window::open, onQuit = quit).also { it.install() }
        if ("--hidden" !in args) window.open()
    } else {
        // No tray (some Linux desktops): the window is the app, and closing it quits.
        window.openAndWait()
        model.stop()
        exitProcess(0)
    }
}

/**
 * macOS: clicking the Dock icon while the window is closed opens it again, and the menu's
 * "About Ruru" opens the window, whose footer shows the real version, instead of the system panel.
 */
private fun macAppHooks(open: () -> Unit) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            desktop.addAppEventListener(AppReopenedListener { open() })
        }
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler { open() }
        }
    }
}

/** Opens the window on demand, one at a time; closing it releases everything it used. */
private class WindowHost(
    private val model: AppModel,
    private val closing: TrayPreference,
    private val hasTray: Boolean,
    private val onQuit: () -> Unit,
    private val onFirstTrayClose: () -> Unit,
) {
    private val open = AtomicBoolean(false)
    private val reduceMotion = os.startsWith("Windows") && !WinSystem.animationsEnabled()
    private val extras = listOfNotNull(StartWithWindows.switchOrNull(), keepInTraySwitch(closing, hasTray))

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
        val saved = remember { WindowMemory.load() }
        // Where the next window opens: the remembered spot, or (after a fallback) where the last one was.
        val resume = remember { arrayOf(saved?.bounds) }
        var kind by remember { mutableStateOf(wantedChrome()) }
        val dark = themeOverride() ?: isSystemInDarkTheme()
        val darkNow by rememberUpdatedState(dark)
        // The close button, Alt+F4, the taskbar and the macOS red button all end up here.
        val requestClose = {
            when (closing.closeAction(hasTray)) {
                CloseAction.Quit -> onQuit()
                CloseAction.CloseToTray -> {
                    exitApplication()
                    if (closing.takeFirstTrayNotice()) onFirstTrayClose()
                }
            }
        }
        // A different kind of title bar needs a different window, so the window is keyed on it.
        key(kind) {
            val chrome = remember { WindowChromeState(kind, requestClose) }
            val start = resume[0]
            val state = rememberWindowState(
                width = (start?.width ?: WindowMemory.DEFAULT_WIDTH).dp,
                height = (start?.height ?: WindowMemory.DEFAULT_HEIGHT).dp,
                position = start?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
            )
            Window(
                onCloseRequest = requestClose,
                title = "Ruru",
                icon = OwlPainter(),
                state = state,
                undecorated = kind == ChromeKind.Windows,
            ) {
                window.minimumSize = Dimension(WindowMemory.MIN_WIDTH, WindowMemory.MIN_HEIGHT)
                DisposableEffect(Unit) {
                    frame = window
                    chrome.awt = window
                    val page = (if (darkNow) DarkColors else LightColors).night
                    window.background = java.awt.Color(page.red, page.green, page.blue)
                    if (kind == ChromeKind.Mac) macTitleBar(window)
                    // Last position and size when not maximised, kept for the next start.
                    var normal = window.bounds
                    val track = object : ComponentAdapter() {
                        private fun note() {
                            if (window.isShowing && !chrome.isMaximised && window.extendedState and Frame.ICONIFIED == 0) normal = window.bounds
                        }
                        override fun componentResized(e: ComponentEvent) = note()
                        override fun componentMoved(e: ComponentEvent) = note()
                    }
                    window.addComponentListener(track)
                    fun drawOwnTitleBar() {
                        if (kind != ChromeKind.Windows || chrome.native != null) return
                        val attached = WinWindowChrome.attach(window, WINDOW_GEOMETRY, chrome.listener)
                        if (attached == null) {
                            // Whatever went wrong, the person still gets a normal window with the system title bar.
                            System.err.println("Ruru: custom title bar unavailable, using the system one")
                            resume[0] = normal
                            kind = ChromeKind.None
                            return
                        }
                        chrome.native = attached
                        applyWindowTheme(attached, darkNow)
                        if (saved?.maximised == true) attached.toggleMaximise()
                    }
                    // A window opened for a second launch belongs to the copy in the tray, which Windows
                    // would otherwise show behind whatever is in front. Raise it once it is on screen.
                    val opened = object : WindowAdapter() {
                        override fun windowOpened(e: WindowEvent) {
                            drawOwnTitleBar()
                            bringToFront()
                        }
                    }
                    window.addWindowListener(opened)
                    if (window.isShowing) {
                        drawOwnTitleBar() // already on screen: its "opened" event has been and gone
                        bringToFront()
                    }
                    onDispose {
                        WindowMemory.save(normal, chrome.isMaximised)
                        window.removeWindowListener(opened)
                        window.removeComponentListener(track)
                        chrome.native = null
                        chrome.awt = null
                        frame = null
                    }
                }
                LaunchedEffect(dark) { chrome.native?.let { applyWindowTheme(it, dark) } }
                RuruTheme(dark = dark, reduceMotion = reduceMotion) {
                    RuruWindowFrame(chrome) { RuruScreen(model, RURU_VERSION, extras = extras) }
                }
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

private val WINDOW_GEOMETRY = WinWindowChrome.Geometry(
    titleBarDp = TitleBarHeight.value.toInt(),
    buttonDp = CaptionButtonWidth.value.toInt(),
)

/**
 * Windows draws Ruru's own title bar, macOS puts the page under the traffic lights, and Linux keeps
 * its native one. RURU_NATIVE_TITLEBAR=1 forces the system title bar everywhere, for testing the fallback.
 */
private fun wantedChrome(): ChromeKind = when {
    System.getenv("RURU_NATIVE_TITLEBAR") == "1" -> ChromeKind.None
    os.startsWith("Windows") && WinWindowChrome.supported -> ChromeKind.Windows
    os.startsWith("Mac") -> ChromeKind.Mac
    else -> ChromeKind.None
}

/** RURU_THEME=light or dark (or -Druru.theme) overrides the system theme, for screenshots. */
private fun themeOverride(): Boolean? = when ((System.getProperty("ruru.theme") ?: System.getenv("RURU_THEME")).orEmpty().lowercase()) {
    "dark" -> true
    "light" -> false
    else -> null
}

/** The system's own parts of the window (shadow, border line, anything it still draws) follow the app theme. */
private fun applyWindowTheme(chrome: WinWindowChrome, dark: Boolean) {
    val colors = if (dark) DarkColors else LightColors
    val edge = colors.line.compositeOver(colors.night)
    chrome.setDark(dark)
    chrome.setBorderColor((edge.red * 255).roundToInt(), (edge.green * 255).roundToInt(), (edge.blue * 255).roundToInt())
}

/** macOS: the page runs under a transparent title bar, and the traffic lights stay where they are. */
private fun macTitleBar(window: Frame) {
    runCatching {
        val pane = (window as javax.swing.RootPaneContainer).rootPane
        pane.putClientProperty("apple.awt.fullWindowContent", true)
        pane.putClientProperty("apple.awt.transparentTitleBar", true)
        pane.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

private fun platformForThisSystem(): HeadsetPlatform = when {
    os.startsWith("Windows") -> WindowsHeadsetPlatform()
    os.startsWith("Linux") -> LinuxHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("Linux without BlueZ")
    os.startsWith("Mac") -> MacHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("macOS")
    else -> UnsupportedPlatform(os.ifBlank { "this system" })
}
