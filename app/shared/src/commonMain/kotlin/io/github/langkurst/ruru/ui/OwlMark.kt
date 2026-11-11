package io.github.langkurst.ruru.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

/** Ruru's mark: a round owl head with yellow eyes and a low brow. Drawn, so it is sharp at any size. */
object OwlColours {
    val Face = Color(0xFF3A3127)
    val Eye = Color(0xFFE8C547)
    val Pupil = Color(0xFF0B0C0A)
    val Beak = Color(0xFFB8935A)
}

fun DrawScope.drawOwl(face: Color = OwlColours.Face, eye: Color = OwlColours.Eye, pupil: Color = OwlColours.Pupil, beak: Color = OwlColours.Beak) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawCircle(face, radius = s * 0.5f, center = Offset(cx, cy))
    val r = s * 0.165f
    val ex = s * 0.2f
    val ey = cy - s * 0.02f
    for (x in listOf(cx - ex, cx + ex)) {
        drawCircle(eye, radius = r, center = Offset(x, ey))
        drawCircle(pupil, radius = r * 0.52f, center = Offset(x, ey + r * 0.08f))
    }
    // The brow: a shallow V of face colour over the top of both eyes.
    val brow = Path().apply {
        moveTo(cx - ex - r * 1.25f, ey - r * 0.95f)
        lineTo(cx, ey - r * 0.2f)
        lineTo(cx + ex + r * 1.25f, ey - r * 0.95f)
        lineTo(cx + ex + r * 1.25f, ey - r * 2f)
        lineTo(cx - ex - r * 1.25f, ey - r * 2f)
        close()
    }
    drawPath(brow, face)
    val beakPath = Path().apply {
        moveTo(cx - s * 0.055f, cy + s * 0.07f)
        lineTo(cx + s * 0.055f, cy + s * 0.07f)
        lineTo(cx, cy + s * 0.19f)
        close()
    }
    drawPath(beakPath, beak)
}

@Composable
fun OwlMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawOwl() }
}

/** The mark as a [Painter], for window and tray icons. */
class OwlPainter : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)
    override fun DrawScope.onDraw() = drawOwl()
}
