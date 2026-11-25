package io.github.langkurst.ruru.desktop.chrome

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.ui.deskScale
import io.github.langkurst.ruru.ui.OwlMark
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.hoverSpec
import kotlin.math.floor

/** Height of the drawn title bar at scale 1 (the native hit-testing in WinWindowChrome uses the same numbers, times the UI scale). */
val TitleBarHeight = 40.dp

/** Width of each caption button (minimise, maximise, close). */
val CaptionButtonWidth = 46.dp

/** Room the macOS traffic lights take at the left of the bar. */
private val TrafficLightsWidth = 78.dp

private val CloseRed = Color(0xFFC42B1C)

/**
 * The window's content under Ruru's own title bar (docs/DESIGN.md: owl and name at the left, three
 * caption buttons at the right). The bar is a plain strip of the page colour, so it reads as part of
 * the window, as in the Windows 11 and macOS apps people already use.
 *
 * The screen content goes below the bar and knows nothing about it. With [ChromeKind.None] (Linux,
 * or the system title bar on request) there is no bar and the content fills the window.
 */
@Composable
fun RuruWindowFrame(chrome: WindowChromeState, content: @Composable () -> Unit) {
    val system = LocalDensity.current
    // Maximised, the system window is a little bigger than the screen, but WinWindowChrome's WM_NCCALCSIZE
    // already clips its client area (what Compose draws into) to the work area. Measured on a real screen:
    // leaving more out here left a 16px dark band at the right and the bottom.
    Box(Modifier.fillMaxSize().background(Ruru.colors.night)) {
        // On a big window the whole interface (bar, sidebar and pages together) is drawn a little larger.
        // BoxWithConstraints measures the width in the same pass as the layout, so a resize never shows
        // one frame at the old scale. The 880dp narrow/wide switch sees the scaled width, which is the
        // same as the system width wherever it matters (the scale is 1 below 1280dp).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val k = deskScale(maxWidth.value)
            SideEffect { chrome.setUiScale(k) }
            CompositionLocalProvider(LocalDensity provides Density(system.density * k, system.fontScale)) {
                Column(Modifier.fillMaxSize()) {
                    if (chrome.kind == ChromeKind.Mac) {
                        // The traffic lights are the system's and do not grow, so the bar stays at the system scale.
                        CompositionLocalProvider(LocalDensity provides system) { TitleBar(chrome) }
                    } else if (chrome.kind != ChromeKind.None) {
                        TitleBar(chrome)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(chrome: WindowChromeState) {
    val c = Ruru.colors
    val windows = chrome.kind == ChromeKind.Windows
    val focused = LocalWindowInfo.current.isWindowFocused
    Row(Modifier.fillMaxWidth().height(TitleBarHeight), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(if (windows) 14.dp else TrafficLightsWidth))
        OwlMark(20.dp)
        Spacer(Modifier.width(9.dp))
        BasicText("Ruru", style = Ruru.type.section.copy(color = if (focused) c.ink2 else c.ink3))
        Spacer(Modifier.weight(1f))
        if (windows) {
            CaptionButton(Caption.Minimise, hovered = null, onClick = chrome::minimise)
            CaptionButton(
                if (chrome.maximised) Caption.Restore else Caption.Maximise,
                // The system answers for this button (it shows the snap layouts), so it reports the hover.
                hovered = chrome.maxHover,
                onClick = chrome::toggleMaximise,
            )
            CaptionButton(Caption.Close, hovered = null, onClick = chrome::close)
        }
    }
}

private enum class Caption(val label: String) {
    Minimise("Minimise"),
    Maximise("Maximise"),
    Restore("Restore"),
    Close("Close"),
}

/** One 46 x 40 caption button: a 10px glyph, a bark tint on hover (red for close), 160ms. */
@Composable
private fun CaptionButton(kind: Caption, hovered: Boolean?, onClick: () -> Unit) {
    val c = Ruru.colors
    val source = remember { MutableInteractionSource() }
    val hover = hovered ?: source.collectIsHoveredAsState().value
    val close = kind == Caption.Close
    val tint by animateColorAsState(
        when {
            !hover -> Color.Transparent
            close -> CloseRed
            else -> c.bark
        },
        hoverSpec(),
    )
    val glyph = if (close && hover) Color.White else c.ink2
    Box(
        Modifier
            .size(CaptionButtonWidth, TitleBarHeight)
            .background(tint)
            .hoverable(source)
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = kind.label
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val stroke = Stroke(width = 1f, join = StrokeJoin.Round) // one device pixel, whatever the scale
            val s = size.width
            val u = s / 10f
            when (kind) {
                Caption.Minimise -> {
                    val y = floor(s / 2f) + 0.5f
                    drawLine(glyph, Offset(0f, y), Offset(s, y), strokeWidth = 1f)
                }
                Caption.Maximise -> drawRoundRect(
                    glyph, Offset(0.5f, 0.5f), Size(s - 1f, s - 1f), CornerRadius(1.5f * u), style = stroke,
                )
                Caption.Restore -> {
                    // A square in front, and the edge of a second one behind it at the top right.
                    drawRoundRect(glyph, Offset(0.5f, 2.5f * u + 0.5f), Size(7f * u, 7f * u - 1f), CornerRadius(1.5f * u), style = stroke)
                    val back = Path().apply {
                        moveTo(2.5f * u + 0.5f, 2.5f * u)
                        lineTo(2.5f * u + 0.5f, 0.5f)
                        lineTo(s - 0.5f, 0.5f)
                        lineTo(s - 0.5f, 7.5f * u)
                        lineTo(7.5f * u + 0.5f, 7.5f * u)
                    }
                    drawPath(back, glyph, style = stroke)
                }
                Caption.Close -> {
                    drawLine(glyph, Offset(0f, 0f), Offset(s, s), strokeWidth = 1f)
                    drawLine(glyph, Offset(s, 0f), Offset(0f, s), strokeWidth = 1f)
                }
            }
        }
    }
}
