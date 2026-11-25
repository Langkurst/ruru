package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.BatteryNotices
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.ui.resolve
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
                    val name = r.name ?: Words("notice_default_name").resolve()
                    when (notices.next(r.percent, r.charging)) {
                        BatteryNotices.Notice.CHARGED -> notify(
                            Words("notice_charged_title").resolve(),
                            Words("notice_charged_text", name).resolve(),
                            TrayIcon.MessageType.INFO,
                        )
                        BatteryNotices.Notice.LOW -> notify(
                            Words("notice_low_title").resolve(),
                            Words("notice_low_text", name, r.percent ?: 0).resolve(),
                            TrayIcon.MessageType.WARNING,
                        )
                        null -> Unit
                    }
                }
        }
    }

    private fun notify(title: String, text: String, type: TrayIcon.MessageType) =
        SwingUtilities.invokeLater { runCatching { icon.displayMessage(title, text, type) } }

    /** What the battery notices need; no reading while the headphones aren't connected. */
    private data class BatteryReading(val percent: Int?, val charging: Boolean?, val name: String?) {
        companion object {
            fun of(state: AppState): BatteryReading {
                val h = state.headset
                if (state.phase != Phase.Connected) return BatteryReading(null, null, null)
                val name = h.info?.customName?.ifBlank { null } ?: h.connection.deviceName
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
        popup.add(MenuItem(view.openLabel).apply { addActionListener { onOpen() } })
        popup.add(MenuItem(view.quitLabel).apply { addActionListener { onQuit() } })
    }

    /** Only what the menu shows, so it is rebuilt only when that changes. */
    private data class TrayView(
        val tooltip: String,
        val status: String?,
        val modes: List<Triple<Int, String, Boolean>>,
        val openLabel: String,
        val quitLabel: String,
    ) {
        companion object {
            suspend fun of(state: AppState): TrayView {
                val h = state.headset
                val open = Words("tray_open").resolve()
                val quit = Words("tray_quit").resolve()
                return when (val p = state.phase) {
                    Phase.Connected -> {
                        val name = h.info?.customName?.ifBlank { null } ?: h.connection.deviceName ?: Words("tray_default_name").resolve()
                        val current = h.modes.firstOrNull { it.index == h.currentModeIndex }?.name
                        val charging = h.isCharging == true
                        val battery = h.batteryPercent
                        TrayView(
                            tooltip = listOfNotNull(
                                name,
                                battery?.let { Words(if (charging) "tray_tip_battery_charging" else "tray_tip_battery", it).resolve() },
                                current,
                            ).joinToString(Words("list_separator").resolve()),
                            status = battery?.let { Words(if (charging) "battery_percent_charging" else "battery_percent", it).resolve() } ?: name,
                            modes = h.modes.filter { it.isPreset || (it.isConfigured && it.name.isNotBlank()) }
                                .map { Triple(it.index, it.name, it.index == h.currentModeIndex) },
                            openLabel = open,
                            quitLabel = quit,
                        )
                    }
                    is Phase.Connecting -> TrayView(
                        Words("tray_tip_connecting", p.name).resolve(), Words("connecting_title", p.name).resolve(), emptyList(), open, quit,
                    )
                    is Phase.Failed -> TrayView(
                        Words("tray_tip_failed").resolve(), Words("failed_title", p.name).resolve(), emptyList(), open, quit,
                    )
                    is Phase.NoHeadset -> TrayView(
                        Words("tray_tip_none").resolve(), Words("tray_status_none").resolve(), emptyList(), open, quit,
                    )
                    Phase.Starting -> TrayView("Ruru", Words("header_looking").resolve(), emptyList(), open, quit)
                    Phase.Unsupported -> TrayView("Ruru", Words("tray_status_unsupported").resolve(), emptyList(), open, quit)
                }
            }
        }
    }

    private companion object {
        /** The owl mark for small sizes, drawn with Java2D from art/ruru-small.svg (64 viewBox). */
        fun owlImage(size: Int): Image {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val k = size / 64.0
            fun dot(color: Color, x: Double, y: Double, r: Double) {
                g.color = color
                g.fill(Ellipse2D.Double((x - r) * k, (y - r) * k, 2 * r * k, 2 * r * k))
            }
            dot(Color(0xA8, 0x6C, 0x46), 32.0, 32.0, 31.0)
            for (x in listOf(21.5, 42.5)) dot(Color(0xE2, 0xA8, 0x17), x, 30.0, 9.5)
            for (x in listOf(22.0, 42.0)) dot(Color(0x0B, 0x0C, 0x0A), x, 30.5, 5.6)
            for (x in listOf(19.8, 39.8)) dot(Color(0xFF, 0xF8, 0xEC), x, 28.2, 1.9)
            val beak = Path2D.Double().apply {
                moveTo(32.0 * k, 37.0 * k)
                lineTo(34.6 * k, 41.2 * k)
                lineTo(32.0 * k, 45.4 * k)
                lineTo(29.4 * k, 41.2 * k)
                closePath()
            }
            g.color = Color(0xFB, 0xE8, 0xC2)
            g.fill(beak)
            g.stroke = BasicStroke((1.2 * k).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(beak)
            g.dispose()
            return img
        }
    }
}
