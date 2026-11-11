package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.BatteryNotices
import io.github.langkurst.ruru.app.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * The tray icon and its menu, built from the system's own menu (AWT), not the UI toolkit, so the
 * tray costs little memory. Shows the battery and lets you switch listening mode without the window.
 */
internal class TrayMenu(
    private val model: AppModel,
    private val scope: CoroutineScope,
    private val onOpen: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private val popup = PopupMenu()
    private val icon = TrayIcon(owlImage(64), "Ruru", popup).apply {
        isImageAutoSize = true
        addActionListener { onOpen() } // double-click on Windows, click elsewhere
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun install() {
        SystemTray.getSystemTray().add(icon)
        scope.launch {
            model.controller
                .flatMapLatest { c -> c.state.map { TrayView.of(it) } }
                .distinctUntilChanged()
                .collectLatest { view -> SwingUtilities.invokeLater { render(view) } }
        }
        // A system notification when a charge finishes or the battery runs low, once each.
        val notices = BatteryNotices()
        scope.launch {
            model.controller
                .flatMapLatest { c -> c.state.map { BatteryReading.of(it) } }
                .distinctUntilChanged()
                .collect { r ->
                    when (notices.next(r.percent, r.charging)) {
                        BatteryNotices.Notice.CHARGED -> notify("Charged", "${r.name} are fully charged.", TrayIcon.MessageType.INFO)
                        BatteryNotices.Notice.LOW -> notify("Battery low", "${r.name} are at ${r.percent}%.", TrayIcon.MessageType.WARNING)
                        null -> Unit
                    }
                }
        }
    }

    private fun notify(title: String, text: String, type: TrayIcon.MessageType) =
        SwingUtilities.invokeLater { runCatching { icon.displayMessage(title, text, type) } }

    /** What the battery notices need; no reading while the headphones aren't connected. */
    private data class BatteryReading(val percent: Int?, val charging: Boolean?, val name: String) {
        companion object {
            fun of(state: AppState): BatteryReading {
                val h = state.headset
                if (state.phase != Phase.Connected) return BatteryReading(null, null, "")
                val name = h.info?.customName?.ifBlank { null } ?: h.connection.deviceName ?: "Your headphones"
                return BatteryReading(h.batteryPercent, h.isCharging, name)
            }
        }
    }

    private fun render(view: TrayView) {
        icon.toolTip = view.tooltip
        popup.removeAll()
        view.status?.let { popup.add(MenuItem(it).apply { isEnabled = false }) }
        if (view.modes.isNotEmpty()) {
            popup.addSeparator()
            for ((index, name, selected) in view.modes) {
                popup.add(CheckboxMenuItem(name, selected).apply {
                    addItemListener { model.controller.value.setMode(index) }
                })
            }
        }
        popup.addSeparator()
        popup.add(MenuItem("Open Ruru").apply { addActionListener { onOpen() } })
        popup.add(MenuItem("Quit").apply { addActionListener { onQuit() } })
    }

    /** Only what the menu shows, so it is rebuilt only when that changes. */
    private data class TrayView(val tooltip: String, val status: String?, val modes: List<Triple<Int, String, Boolean>>) {
        companion object {
            fun of(state: AppState): TrayView {
                val h = state.headset
                return when (val p = state.phase) {
                    Phase.Connected -> {
                        val name = h.info?.customName?.ifBlank { null } ?: h.connection.deviceName ?: "Headphones"
                        val current = h.modes.firstOrNull { it.index == h.currentModeIndex }?.name
                        val charging = if (h.isCharging == true) ", charging" else ""
                        TrayView(
                            tooltip = listOfNotNull(name, h.batteryPercent?.let { "battery $it%$charging" }, current).joinToString(", "),
                            status = h.batteryPercent?.let { "Battery $it%$charging" } ?: name,
                            modes = h.modes.filter { it.isPreset || (it.isConfigured && it.name.isNotBlank()) }
                                .map { Triple(it.index, it.name, it.index == h.currentModeIndex) },
                        )
                    }
                    is Phase.Connecting -> TrayView("Ruru: connecting to ${p.name}", "Connecting to ${p.name}", emptyList())
                    is Phase.Failed -> TrayView("Ruru: could not connect", "Could not connect to ${p.name}", emptyList())
                    is Phase.NoHeadset -> TrayView("Ruru: no headphones connected", "No headphones connected", emptyList())
                    Phase.Starting -> TrayView("Ruru", "Looking for headphones", emptyList())
                    Phase.Unsupported -> TrayView("Ruru", "Not available on this system", emptyList())
                }
            }
        }
    }

    private companion object {
        /** The owl mark, drawn with Java2D (same geometry as OwlMark.kt). */
        fun owlImage(size: Int): Image {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = size.toDouble()
            val cx = s / 2
            val cy = s / 2
            val face = Color(0x3A, 0x31, 0x27)
            g.color = face
            g.fill(Ellipse2D.Double(0.0, 0.0, s, s))
            val r = s * 0.165
            val ex = s * 0.2
            val ey = cy - s * 0.02
            for (x in listOf(cx - ex, cx + ex)) {
                g.color = Color(0xE8, 0xC5, 0x47)
                g.fill(Ellipse2D.Double(x - r, ey - r, 2 * r, 2 * r))
                g.color = Color(0x0B, 0x0C, 0x0A)
                val pr = r * 0.52
                g.fill(Ellipse2D.Double(x - pr, ey + r * 0.08 - pr, 2 * pr, 2 * pr))
            }
            g.clip = Ellipse2D.Double(0.0, 0.0, s, s)
            g.color = face
            g.fill(Path2D.Double().apply {
                moveTo(cx - ex - r * 1.25, ey - r * 0.95)
                lineTo(cx, ey - r * 0.2)
                lineTo(cx + ex + r * 1.25, ey - r * 0.95)
                lineTo(cx + ex + r * 1.25, ey - r * 2)
                lineTo(cx - ex - r * 1.25, ey - r * 2)
                closePath()
            })
            g.color = Color(0xB8, 0x93, 0x5A)
            g.fill(Path2D.Double().apply {
                moveTo(cx - s * 0.055, cy + s * 0.07)
                lineTo(cx + s * 0.055, cy + s * 0.07)
                lineTo(cx, cy + s * 0.19)
                closePath()
            })
            g.stroke = BasicStroke(1f)
            g.dispose()
            return img
        }
    }
}
