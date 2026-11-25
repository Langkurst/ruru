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

    /** The one-time notice after the window first closes to the tray. */
    fun notifyStillRunning() {
        scope.launch { notify("Ruru", Words("desktop_tray_notice").resolve(), TrayIcon.MessageType.INFO) }
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

    internal companion object {
        /** The owl mark for small sizes, drawn with Java2D from art/ruru-small.svg (64 viewBox). */
        fun owlImage(size: Int): Image {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            val k = size / 64.0
            fun rgb(hex: Int, alpha: Double = 1.0) = Color(hex shr 16 and 0xFF, hex shr 8 and 0xFF, hex and 0xFF, (alpha * 255).toInt())
            fun fillShape(hex: Int, shape: java.awt.Shape, alpha: Double = 1.0) { g.color = rgb(hex, alpha); g.fill(shape) }
            fun dot(hex: Int, x: Double, y: Double, r: Double) =
                fillShape(hex, Ellipse2D.Double((x - r) * k, (y - r) * k, 2 * r * k, 2 * r * k))
            fun oval(hex: Int, x: Double, y: Double, rx: Double, ry: Double, alpha: Double = 1.0) =
                fillShape(hex, Ellipse2D.Double((x - rx) * k, (y - ry) * k, 2 * rx * k, 2 * ry * k), alpha)
            fun stroke(hex: Int, shape: java.awt.Shape, width: Double, cap: Int = BasicStroke.CAP_ROUND) {
                g.color = rgb(hex)
                g.stroke = BasicStroke((width * k).toFloat(), cap, BasicStroke.JOIN_ROUND)
                g.draw(shape)
            }
            fun curve(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
                Path2D.Double().apply { moveTo(x0 * k, y0 * k); curveTo(x1 * k, y1 * k, x2 * k, y2 * k, x3 * k, y3 * k) }
            fun segment(x0: Double, y0: Double, x1: Double, y1: Double) =
                Path2D.Double().apply { moveTo(x0 * k, y0 * k); lineTo(x1 * k, y1 * k) }

            val body = Path2D.Double().apply {
                moveTo(32 * k, 10 * k)
                curveTo(46 * k, 10 * k, 55 * k, 20 * k, 55 * k, 34 * k)
                curveTo(55 * k, 49 * k, 46 * k, 58 * k, 32 * k, 58 * k)
                curveTo(18 * k, 58 * k, 9 * k, 49 * k, 9 * k, 34 * k)
                curveTo(9 * k, 20 * k, 18 * k, 10 * k, 32 * k, 10 * k)
                closePath()
            }
            fillShape(0x8E5B37, body)
            oval(0xF1DDBE, 32.0, 54.0, 12.0, 5.5)
            for (x in listOf(24.5, 39.5)) dot(0xF5B82E, x, 32.0, 7.4)
            for (x in listOf(24.8, 39.2)) dot(0x1B1410, x, 32.6, 5.3)
            for (x in listOf(22.8, 37.2)) dot(0xFFFFFF, x, 30.2, 2.0)
            stroke(0xFFF3E0, curve(17.8, 23.0, 20.8, 19.0, 27.0, 18.8, 30.2, 22.6), 2.4)
            stroke(0xFFF3E0, curve(46.2, 23.0, 43.2, 19.0, 37.0, 18.8, 33.8, 22.6), 2.4)
            oval(0xFF8F86, 17.6, 41.0, 3.8, 2.3, 0.85)
            oval(0xFF8F86, 46.4, 41.0, 3.8, 2.3, 0.85)
            val beak = Path2D.Double().apply {
                moveTo(30.0 * k, 37.6 * k)
                lineTo(34.0 * k, 37.6 * k)
                lineTo(32.0 * k, 40.8 * k)
                closePath()
            }
            fillShape(0xF2B33D, beak)
            stroke(0xF2B33D, beak, 1.0)
            stroke(0x1C1C1E, curve(11.5, 27.0, 11.5, 7.5, 52.5, 7.5, 52.5, 27.0), 3.0)
            stroke(0x7C7F86, segment(11.5, 27.0, 10.8, 30.0), 1.4)
            stroke(0x7C7F86, segment(52.5, 27.0, 53.2, 30.0), 1.4)
            for (x in listOf(10.0, 54.0)) {
                val cup = Ellipse2D.Double((x - 5) * k, (36.0 - 7) * k, 10 * k, 14 * k)
                fillShape(0x1C1C1E, cup)
                stroke(0x55575C, cup, 0.8)
            }
            g.dispose()
            return img
        }
    }
}
