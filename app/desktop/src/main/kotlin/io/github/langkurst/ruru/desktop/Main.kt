package io.github.langkurst.ruru.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.Phase
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

private val os: String = System.getProperty("os.name").orEmpty()

fun main(args: Array<String>) {
    if (System.getenv("RURU_DEBUG") == "1") {
        Logging.sink = { _, tag, msg, t -> System.err.println("$tag $msg${t?.let { " (${it.message})" } ?: ""}") }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(platformForThisSystem(), scope, startInDemo = "--demo" in args)
    model.start()
    val reduceMotion = os.startsWith("Windows") && !WinSystem.animationsEnabled()
    val hasTray = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    application {
        var visible by remember { mutableStateOf("--hidden" !in args || !hasTray) }
        val controller by model.controller.collectAsState()
        val state by controller.state.collectAsState()
        val owl = remember { OwlPainter() }

        if (hasTray) {
            Tray(
                icon = owl,
                tooltip = trayTooltip(state),
                onAction = { visible = true },
                menu = {
                    val h = state.headset
                    if (state.phase == Phase.Connected) {
                        h.batteryPercent?.let { Item("Battery $it%", enabled = false, onClick = {}) }
                        Separator()
                        h.modes.filter { it.isPreset || (it.isConfigured && it.name.isNotBlank()) }.forEach { mode ->
                            RadioButtonItem(mode.name, selected = mode.index == h.currentModeIndex, onClick = { controller.setMode(mode.index) })
                        }
                    } else {
                        Item(trayStatus(state), enabled = false, onClick = {})
                    }
                    Separator()
                    Item("Open Ruru", onClick = { visible = true })
                    Item("Quit", onClick = {
                        model.stop()
                        exitApplication()
                    })
                },
            )
        }

        // The window exists only while it is shown: hiding it to the tray frees its graphics
        // memory, and the connection keeps running in the model.
        if (visible) {
            Window(
                onCloseRequest = {
                    if (hasTray) {
                        visible = false
                        releaseMemorySoon()
                    } else {
                        model.stop()
                        exitApplication()
                    }
                },
                title = "Ruru",
                icon = owl,
                state = rememberWindowState(width = 400.dp, height = 780.dp, position = WindowPosition(Alignment.Center)),
            ) {
                window.minimumSize = Dimension(360, 480)
                RuruTheme(reduceMotion = reduceMotion) {
                    RuruScreen(model, RURU_VERSION)
                }
            }
        }
    }
}

/** After the window closes, collect once so the freed heap goes back to the system. */
private fun releaseMemorySoon() {
    Thread {
        Thread.sleep(1_500)
        System.gc()
    }.apply { isDaemon = true }.start()
}

private fun platformForThisSystem(): HeadsetPlatform = when {
    os.startsWith("Windows") -> WindowsHeadsetPlatform()
    os.startsWith("Linux") -> LinuxHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("Linux without BlueZ")
    os.startsWith("Mac") -> MacHeadsetPlatform().takeIf { it.supported } ?: UnsupportedPlatform("macOS")
    else -> UnsupportedPlatform(os.ifBlank { "this system" })
}

private fun trayTooltip(state: AppState): String {
    val h = state.headset
    return when (state.phase) {
        Phase.Connected -> buildString {
            append(h.info?.customName?.ifBlank { null } ?: h.connection.deviceName ?: "Headphones")
            h.batteryPercent?.let { append(", battery $it%") }
            h.modes.firstOrNull { it.index == h.currentModeIndex }?.let { append(", ${it.name}") }
        }
        else -> "Ruru: ${trayStatus(state)}"
    }
}

private fun trayStatus(state: AppState): String = when (val p = state.phase) {
    Phase.Connected -> "Connected"
    is Phase.Connecting -> "Connecting to ${p.name}"
    is Phase.Failed -> "Could not connect to ${p.name}"
    is Phase.NoHeadset -> "No headphones connected"
    Phase.Starting -> "Looking for headphones"
    Phase.Unsupported -> "Not available on this system"
}
