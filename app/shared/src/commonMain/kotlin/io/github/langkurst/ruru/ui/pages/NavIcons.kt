package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The line icons of the sidebar and the bottom bar, drawn here so no icon font or image is needed. */
internal enum class NavIcon { Headphones, Gear, Refresh, Sliders, Moon, Devices, Tools, Speaker, More }

@Composable
internal fun NavIconView(icon: NavIcon, colour: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawNavIcon(icon, colour) }
}

/** The icons are 24 x 24 line drawings, stroked at 1.6 and scaled to whatever size the canvas has. */
internal fun DrawScope.drawNavIcon(icon: NavIcon, colour: Color) {
    val s = size.width / 24f
    val stroke = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(colour, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), 1.6f * s, StrokeCap.Round)
    when (icon) {
        NavIcon.Headphones -> {
            val band = Path().apply {
                moveTo(4 * s, 14 * s)
                lineTo(4 * s, 12 * s)
                arcTo(Rect(4 * s, 4 * s, 20 * s, 20 * s), 180f, 180f, false)
                lineTo(20 * s, 14 * s)
            }
            drawPath(band, colour, style = stroke)
            drawRoundRect(colour, Offset(3 * s, 14 * s), Size(4 * s, 7 * s), CornerRadius(2 * s), style = stroke)
            drawRoundRect(colour, Offset(17 * s, 14 * s), Size(4 * s, 7 * s), CornerRadius(2 * s), style = stroke)
        }
        NavIcon.Gear -> {
            drawCircle(colour, 3 * s, Offset(12 * s, 12 * s), style = stroke)
            line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f); line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f)
            line(4.9f, 4.9f, 7f, 7f); line(17f, 17f, 19.1f, 19.1f); line(4.9f, 19.1f, 7f, 17f); line(17f, 7f, 19.1f, 4.9f)
        }
        NavIcon.Refresh -> {
            // Nearly a full circle, with an arrow head at its end.
            val r = 7f
            val arc = Path().apply { arcTo(Rect((12 - r) * s, (12 - r) * s, (12 + r) * s, (12 + r) * s), 30f, 300f, true) }
            drawPath(arc, colour, style = stroke)
            val end = 330.0 * PI / 180.0
            val ex = 12f + r * cos(end).toFloat()
            val ey = 12f + r * sin(end).toFloat()
            val tx = (-sin(end)).toFloat()
            val ty = cos(end).toFloat()
            for (turn in listOf(0.75, -0.75)) {
                val a = kotlin.math.atan2((-ty).toDouble(), (-tx).toDouble()) + turn
                line(ex, ey, ex + 4f * cos(a).toFloat(), ey + 4f * sin(a).toFloat())
            }
        }
        NavIcon.Sliders -> {
            // Three tracks with a knob each, at different heights.
            for ((x, knob) in listOf(6f to 15f, 12f to 9f, 18f to 13f)) {
                line(x, 4f, x, knob - 2.6f)
                line(x, knob + 2.6f, x, 20f)
                drawCircle(colour, 2.2f * s, Offset(x * s, knob * s), style = stroke)
            }
        }
        NavIcon.Moon -> {
            // A crescent: the big arc round the outside, then a smaller one back along the inside.
            val moon = Path().apply {
                moveTo(21f * s, 12.79f * s)
                arcTo(Rect(3 * s, 3 * s, 21 * s, 21 * s), 5f, 260f, false)
                arcTo(Rect((16.84f - 7f) * s, (7.16f - 7f) * s, (16.84f + 7f) * s, (7.16f + 7f) * s), 216.5f, -163f, false)
                close()
            }
            drawPath(moon, colour, style = stroke)
        }
        NavIcon.Devices -> {
            // A screen on a stand, and a phone beside it.
            drawRoundRect(colour, Offset(2.5f * s, 5f * s), Size(12.5f * s, 9.5f * s), CornerRadius(1.6f * s), style = stroke)
            line(9f, 14.5f, 9f, 19f); line(5.5f, 19f, 12.5f, 19f)
            drawRoundRect(colour, Offset(17f * s, 9f * s), Size(4.5f * s, 10f * s), CornerRadius(1.6f * s), style = stroke)
        }
        NavIcon.Tools -> {
            // A spanner: a ring at the top right and the handle running down to the left.
            drawCircle(colour, 3.6f * s, Offset(15.5f * s, 8.5f * s), style = stroke)
            line(13f, 11f, 4.5f, 19.5f)
        }
        NavIcon.Speaker -> {
            val body = Path().apply {
                moveTo(4 * s, 9.5f * s); lineTo(8 * s, 9.5f * s); lineTo(13 * s, 5.5f * s)
                lineTo(13 * s, 18.5f * s); lineTo(8 * s, 14.5f * s); lineTo(4 * s, 14.5f * s); close()
            }
            drawPath(body, colour, style = stroke)
            for (r in listOf(4f, 7.5f)) {
                val arc = Path().apply { arcTo(Rect((13 - r) * s, (12 - r) * s, (13 + r) * s, (12 + r) * s), -42f, 84f, true) }
                drawPath(arc, colour, style = stroke)
            }
        }
        NavIcon.More -> for (x in listOf(5f, 12f, 19f)) drawCircle(colour, 1.7f * s, Offset(x * s, 12f * s))
    }
}
