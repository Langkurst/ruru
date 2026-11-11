package io.github.langkurst.ruru.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.monasans_medium
import io.github.langkurst.ruru.ui.res.monasans_regular
import io.github.langkurst.ruru.ui.res.monasans_semibold
import org.jetbrains.compose.resources.Font

/** Colours from docs/DESIGN.md, named after the ruru (morepork) and its night bush. */
@Immutable
data class RuruColors(
    val night: Color,
    val bough: Color,
    val bark: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val line: Color,
    val lineSoft: Color,
    val eye: Color,
    val onEye: Color,
    val alarm: Color,
    val calm: Color,
    val isDark: Boolean,
)

private val DarkInk = Color(0xFFF2F3EE)
private val LightInk = Color(0xFF1A1C17)

val DarkColors = RuruColors(
    night = Color(0xFF0B0C0A), bough = Color(0xFF131512), bark = Color(0xFF1C1E1A),
    ink = DarkInk, ink2 = DarkInk.copy(alpha = 0.72f), ink3 = DarkInk.copy(alpha = 0.52f), ink4 = DarkInk.copy(alpha = 0.32f),
    line = Color.White.copy(alpha = 0.08f), lineSoft = Color.White.copy(alpha = 0.05f),
    eye = Color(0xFFE8C547), onEye = LightInk, alarm = Color(0xFFFF6161), calm = Color(0xFF59D499), isDark = true,
)

val LightColors = RuruColors(
    night = Color(0xFFF4F5F3), bough = Color(0xFFFFFFFF), bark = Color(0xFFEAECE8),
    ink = LightInk, ink2 = LightInk.copy(alpha = 0.72f), ink3 = LightInk.copy(alpha = 0.56f), ink4 = LightInk.copy(alpha = 0.40f),
    line = Color.Black.copy(alpha = 0.08f), lineSoft = Color.Black.copy(alpha = 0.05f),
    eye = Color(0xFFE2B91F), onEye = LightInk, alarm = Color(0xFFC23B3B), calm = Color(0xFF1E8A5A), isDark = false,
)

/** Type from docs/DESIGN.md: Mona Sans, nothing over 18sp. */
@Immutable
class RuruType(family: FontFamily) {
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
    val section = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp)
    val bodyMedium = body.copy(fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
    // Plain figures: Mona Sans' tabular set gives the 1 and 0 a code-font look. Changing values sit
    // in fixed-width, end-aligned slots instead, so nothing jumps.
    val figure = body
    val figureMedium = bodyMedium
}

/** One signature curve (Raycast's ease-out-quint), two durations. */
object Motion {
    val Quint = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    const val MOVE_MS = 300
    const val HOVER_MS = 160
}

val LocalColors = staticCompositionLocalOf { DarkColors }
val LocalType = staticCompositionLocalOf<RuruType> { error("RuruTheme is missing") }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Ruru {
    val colors: RuruColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: RuruType
        @Composable @ReadOnlyComposable get() = LocalType.current
}

/** For things that move after a user action: 300ms quint, or nothing with reduced motion. */
@Composable
fun <T> moveSpec(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(Motion.MOVE_MS, easing = Motion.Quint)

/** For hover and press tints: 160ms quint, or nothing with reduced motion. */
@Composable
fun <T> hoverSpec(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(Motion.HOVER_MS, easing = Motion.Quint)

@Composable
fun RuruTheme(dark: Boolean = isSystemInDarkTheme(), reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val family = FontFamily(
        Font(Res.font.monasans_regular, FontWeight.Normal),
        Font(Res.font.monasans_medium, FontWeight.Medium),
        Font(Res.font.monasans_semibold, FontWeight.SemiBold),
    )
    val type = remember(family) { RuruType(family) }
    CompositionLocalProvider(
        LocalColors provides if (dark) DarkColors else LightColors,
        LocalType provides type,
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}
