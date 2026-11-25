package io.github.langkurst.ruru.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

/**
 * Ruru's mark: a chubby ruru (morepork) with white brows, rosy cheeks, a little open beak and slim
 * black over-ear headphones. Drawn from art/ruru.svg (over 40px: the whole owl with feet, wings, a
 * spotted belly and smiling eyes) and art/ruru-small.svg (40px and less: face and body only), so it
 * is sharp at any size. Those SVGs are the source of truth. Each keeps its own viewBox (1024 and 64)
 * mapped onto the square canvas, so the owl sits inside it with a little space around.
 */
object OwlColours {
    val Body = Color(0xFF8E5B37)
    val Wing = Color(0xFF704426)
    val Belly = Color(0xFFF1DDBE)
    val BellySpot = Color(0xFFA3734C)
    val Feet = Color(0xFFF2B33D)
    val Eye = Color(0xFFF5B82E)
    val Pupil = Color(0xFF1B1410)
    val Glint = Color(0xFFFFFFFF)
    val Brow = Color(0xFFFFF3E0)
    val Cheek = Color(0xFFFF8F86)
    val BeakOpen = Color(0xFFB8483F)
    val Beak = Color(0xFFF2B33D)
    val Band = Color(0xFF1C1C1E)
    val BandShine = Color(0xFF3B3C40)
    val Arm = Color(0xFF7C7F86)
    val CupBack = Color(0xFF38393D)
    val CupInner = Color(0xFF26272A)
    val CupRim = Color(0xFF55575C)
    val CupShine = Color(0xFF6A6C72)
}

/** Sizes in pixels above which the whole owl is drawn instead of the small face-and-body one. */
private const val FULL_MARK_ABOVE_PX = 40f

/** Maps SVG viewBox units onto the canvas: offset ([ox], [oy]) and [k] pixels per unit. */
private class Fit(val ox: Float, val oy: Float, val k: Float) {
    fun x(v: Float) = ox + v * k
    fun y(v: Float) = oy + v * k
    fun off(px: Float, py: Float) = Offset(x(px), y(py))
    fun path(build: Path.(Fit) -> Unit): Path = Path().also { it.build(this) }
}

private fun DrawScope.dot(m: Fit, color: Color, cx: Float, cy: Float, r: Float) =
    drawCircle(color, r * m.k, m.off(cx, cy))

private fun DrawScope.oval(m: Fit, color: Color, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float = 1f) =
    drawOval(color, m.off(cx - rx, cy - ry), Size(2 * rx * m.k, 2 * ry * m.k), alpha)

private fun DrawScope.ovalLine(m: Fit, color: Color, cx: Float, cy: Float, rx: Float, ry: Float, width: Float) =
    drawOval(color, m.off(cx - rx, cy - ry), Size(2 * rx * m.k, 2 * ry * m.k), style = Stroke(width * m.k))

private fun DrawScope.line(m: Fit, color: Color, path: Path, width: Float) =
    drawPath(path, color, style = Stroke(width * m.k, cap = StrokeCap.Round, join = StrokeJoin.Round))

private fun Path.mv(m: Fit, px: Float, py: Float) = moveTo(m.x(px), m.y(py))
private fun Path.ln(m: Fit, px: Float, py: Float) = lineTo(m.x(px), m.y(py))
private fun Path.cb(m: Fit, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) =
    cubicTo(m.x(a), m.y(b), m.x(c), m.y(d), m.x(e), m.y(f))

fun DrawScope.drawOwl() {
    val s = size.minDimension
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    if (s > FULL_MARK_ABOVE_PX) drawFullOwl(Fit(ox, oy, s / 1024f)) else drawSmallOwl(Fit(ox, oy, s / 64f))
}

/** art/ruru.svg, in its paint order. */
private fun DrawScope.drawFullOwl(m: Fit) {
    val c = OwlColours
    // Feet
    for ((fx, fy) in listOf(408f to 902f, 440f to 910f, 376f to 910f, 616f to 902f, 648f to 910f, 584f to 910f)) {
        oval(m, c.Feet, fx, fy, 28f, 22f)
    }
    // One chubby body
    val body = m.path { f ->
        mv(f, 512f, 150f)
        cb(f, 720f, 150f, 850f, 290f, 860f, 480f)
        cb(f, 872f, 700f, 760f, 900f, 512f, 900f)
        cb(f, 264f, 900f, 152f, 700f, 164f, 480f)
        cb(f, 174f, 290f, 304f, 150f, 512f, 150f)
        close()
    }
    drawPath(body, c.Body)
    // Stubby wings
    rotate(16f, m.off(196f, 660f)) { oval(m, c.Wing, 196f, 660f, 58f, 118f) }
    rotate(-16f, m.off(828f, 660f)) { oval(m, c.Wing, 828f, 660f, 58f, 118f) }
    // Spotted belly
    oval(m, c.Belly, 512f, 730f, 214f, 168f)
    val spots = listOf(
        452f to 640f, 512f to 628f, 572f to 640f,
        392f to 712f, 472f to 704f, 552f to 704f, 632f to 712f,
        432f to 784f, 512f to 780f, 592f to 784f,
        472f to 850f, 552f to 850f,
    )
    for ((sx, sy) in spots) oval(m, c.BellySpot, sx, sy, 11f, 17f)
    // Eyes, smiling: the two lid circles are cut out, so the body shows through (the SVG mask).
    val lids = Path().apply {
        addOval(Rect(m.off(404f - 112f, 586f - 112f), Size(224f * m.k, 224f * m.k)))
        addOval(Rect(m.off(620f - 112f, 586f - 112f), Size(224f * m.k, 224f * m.k)))
    }
    clipPath(lids, ClipOp.Difference) {
        dot(m, c.Eye, 404f, 452f, 98f); dot(m, c.Eye, 620f, 452f, 98f)
        dot(m, c.Pupil, 408f, 456f, 70f); dot(m, c.Pupil, 616f, 456f, 70f)
        dot(m, c.Glint, 378f, 418f, 27f); dot(m, c.Glint, 586f, 418f, 27f)
        dot(m, c.Glint, 434f, 452f, 11f); dot(m, c.Glint, 642f, 452f, 11f)
    }
    // White ruru eyebrows
    line(m, c.Brow, m.path { mv(it, 306f, 318f); cb(it, 352f, 258f, 446f, 252f, 494f, 312f) }, 26f)
    line(m, c.Brow, m.path { mv(it, 718f, 318f); cb(it, 672f, 258f, 578f, 252f, 530f, 312f) }, 26f)
    // Rosy cheeks
    oval(m, c.Cheek, 312f, 538f, 58f, 34f, 0.8f)
    oval(m, c.Cheek, 712f, 538f, 58f, 34f, 0.8f)
    // Little open beak
    oval(m, c.BeakOpen, 512f, 540f, 22f, 17f)
    val beak = m.path { mv(it, 486f, 488f); ln(it, 538f, 488f); ln(it, 512f, 530f); close() }
    drawPath(beak, c.Beak)
    drawPath(beak, c.Beak, style = Stroke(14f * m.k, join = StrokeJoin.Round))
    // Black headphones: slim band, metal arms, oval cups with cushions
    line(m, c.Band, m.path { mv(it, 198f, 336f); cb(it, 198f, 92f, 826f, 92f, 826f, 336f) }, 36f)
    line(m, c.BandShine, m.path { mv(it, 236f, 236f); cb(it, 300f, 128f, 724f, 128f, 788f, 236f) }, 7f)
    line(m, c.Arm, m.path { mv(it, 198f, 330f); ln(it, 188f, 400f) }, 16f)
    line(m, c.Arm, m.path { mv(it, 826f, 330f); ln(it, 836f, 400f) }, 16f)
    oval(m, c.CupBack, 202f, 490f, 34f, 92f)
    oval(m, c.CupBack, 822f, 490f, 34f, 92f)
    for (cx in listOf(176f, 848f)) {
        oval(m, c.Band, cx, 490f, 64f, 100f)
        ovalLine(m, c.CupRim, cx, 490f, 64f, 100f, 7f)
    }
    oval(m, c.CupInner, 172f, 490f, 40f, 72f)
    oval(m, c.CupInner, 852f, 490f, 40f, 72f)
    line(m, c.CupShine, m.path { mv(it, 146f, 440f); cb(it, 150f, 416f, 162f, 404f, 176f, 400f) }, 8f)
    line(m, c.CupShine, m.path { mv(it, 878f, 440f); cb(it, 874f, 416f, 862f, 404f, 848f, 400f) }, 8f)
}

/** art/ruru-small.svg: face and body, brows, cheeks, beak, black band and two oval cups. */
private fun DrawScope.drawSmallOwl(m: Fit) {
    val c = OwlColours
    val body = m.path { f ->
        mv(f, 32f, 10f)
        cb(f, 46f, 10f, 55f, 20f, 55f, 34f)
        cb(f, 55f, 49f, 46f, 58f, 32f, 58f)
        cb(f, 18f, 58f, 9f, 49f, 9f, 34f)
        cb(f, 9f, 20f, 18f, 10f, 32f, 10f)
        close()
    }
    drawPath(body, c.Body)
    oval(m, c.Belly, 32f, 54f, 12f, 5.5f)
    dot(m, c.Eye, 24.5f, 32f, 7.4f); dot(m, c.Eye, 39.5f, 32f, 7.4f)
    dot(m, c.Pupil, 24.8f, 32.6f, 5.3f); dot(m, c.Pupil, 39.2f, 32.6f, 5.3f)
    dot(m, c.Glint, 22.8f, 30.2f, 2f); dot(m, c.Glint, 37.2f, 30.2f, 2f)
    line(m, c.Brow, m.path { mv(it, 17.8f, 23f); cb(it, 20.8f, 19f, 27f, 18.8f, 30.2f, 22.6f) }, 2.4f)
    line(m, c.Brow, m.path { mv(it, 46.2f, 23f); cb(it, 43.2f, 19f, 37f, 18.8f, 33.8f, 22.6f) }, 2.4f)
    oval(m, c.Cheek, 17.6f, 41f, 3.8f, 2.3f, 0.85f)
    oval(m, c.Cheek, 46.4f, 41f, 3.8f, 2.3f, 0.85f)
    val beak = m.path { mv(it, 30f, 37.6f); ln(it, 34f, 37.6f); ln(it, 32f, 40.8f); close() }
    drawPath(beak, c.Beak)
    drawPath(beak, c.Beak, style = Stroke(1f * m.k, join = StrokeJoin.Round))
    line(m, c.Band, m.path { mv(it, 11.5f, 27f); cb(it, 11.5f, 7.5f, 52.5f, 7.5f, 52.5f, 27f) }, 3f)
    line(m, c.Arm, m.path { mv(it, 11.5f, 27f); ln(it, 10.8f, 30f) }, 1.4f)
    line(m, c.Arm, m.path { mv(it, 52.5f, 27f); ln(it, 53.2f, 30f) }, 1.4f)
    for (cx in listOf(10f, 54f)) {
        oval(m, c.Band, cx, 36f, 5f, 7f)
        ovalLine(m, c.CupRim, cx, 36f, 5f, 7f, 0.8f)
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
