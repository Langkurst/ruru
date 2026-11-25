package io.github.langkurst.ruru.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

/**
 * Ruru's mark: a friendly ruru (morepork) face, a round brown head with a cream face mask, big
 * golden eyes and a small beak. Drawn from art/ruru.svg (over 40px) and art/ruru-small.svg (40px
 * and less: no mask, bigger eyes), so it is sharp at any size. Those SVGs are the source of truth.
 */
object OwlColours {
    val Face = Color(0xFFA86C46)
    val Mask = Color(0xFFDDB88E)
    val Eye = Color(0xFFDCA012)
    val EyeSmall = Color(0xFFE2A817)
    val Pupil = Color(0xFF0B0C0A)
    val Glint = Color(0xFFFFF8EC)
    val Beak = Color(0xFFFBE8C2)
}

/** Sizes in pixels above which the full mark (with the face mask) is drawn instead of the small one. */
private const val FULL_MARK_ABOVE_PX = 40f

fun DrawScope.drawOwl(
    face: Color = OwlColours.Face,
    eye: Color = OwlColours.Eye,
    pupil: Color = OwlColours.Pupil,
    beak: Color = OwlColours.Beak,
    mask: Color = OwlColours.Mask,
    glint: Color = OwlColours.Glint,
    eyeSmall: Color = OwlColours.EyeSmall,
) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    if (s > FULL_MARK_ABOVE_PX) {
        // art/ruru.svg: 1024 viewBox, head circle centre 512 radius 384, scaled so the head fills the canvas.
        val k = s / 768f
        fun x(v: Float) = cx + (v - 512f) * k
        fun y(v: Float) = cy + (v - 512f) * k
        fun dot(color: Color, px: Float, py: Float, r: Float) = drawCircle(color, r * k, Offset(x(px), y(py)))
        dot(face, 512f, 512f, 384f)
        val maskPath = Path().apply {
            moveTo(x(512f), y(444f))
            cubicTo(x(472f), y(372f), x(424f), y(338f), x(370f), y(338f))
            cubicTo(x(270f), y(338f), x(208f), y(410f), x(208f), y(494f))
            cubicTo(x(208f), y(586f), x(300f), y(640f), x(400f), y(652f))
            cubicTo(x(452f), y(658f), x(488f), y(666f), x(512f), y(682f))
            cubicTo(x(536f), y(666f), x(572f), y(658f), x(624f), y(652f))
            cubicTo(x(724f), y(640f), x(816f), y(586f), x(816f), y(494f))
            cubicTo(x(816f), y(410f), x(754f), y(338f), x(654f), y(338f))
            cubicTo(x(600f), y(338f), x(552f), y(372f), x(512f), y(444f))
            close()
        }
        drawPath(maskPath, mask)
        for (ex in listOf(366f, 658f)) dot(eye, ex, 496f, 90f)
        for (px in listOf(369f, 655f)) dot(pupil, px, 499f, 61f)
        for (gx in listOf(346f, 632f)) dot(glint, gx, 472f, 18f)
        val beakPath = Path().apply {
            moveTo(x(512f), y(528f)); lineTo(x(538f), y(580f)); lineTo(x(512f), y(634f)); lineTo(x(486f), y(580f)); close()
        }
        drawPath(beakPath, beak)
        drawPath(beakPath, beak, style = Stroke(width = 14f * k, join = StrokeJoin.Round))
    } else {
        // art/ruru-small.svg: 64 viewBox, head circle centre 32 radius 31.
        val k = s / 62f
        fun x(v: Float) = cx + (v - 32f) * k
        fun y(v: Float) = cy + (v - 32f) * k
        fun dot(color: Color, px: Float, py: Float, r: Float) = drawCircle(color, r * k, Offset(x(px), y(py)))
        dot(face, 32f, 32f, 31f)
        for (ex in listOf(21.5f, 42.5f)) dot(eyeSmall, ex, 30f, 9.5f)
        for (px in listOf(22f, 42f)) dot(pupil, px, 30.5f, 5.6f)
        for (gx in listOf(19.8f, 39.8f)) dot(glint, gx, 28.2f, 1.9f)
        val beakPath = Path().apply {
            moveTo(x(32f), y(37f)); lineTo(x(34.6f), y(41.2f)); lineTo(x(32f), y(45.4f)); lineTo(x(29.4f), y(41.2f)); close()
        }
        drawPath(beakPath, beak)
        drawPath(beakPath, beak, style = Stroke(width = 1.2f * k, join = StrokeJoin.Round))
    }
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
