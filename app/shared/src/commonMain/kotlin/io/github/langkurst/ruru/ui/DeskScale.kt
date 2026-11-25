package io.github.langkurst.ruru.ui

import kotlin.math.roundToInt

/**
 * How much a big computer window scales its whole interface up, so it does not look tiny on a large
 * screen. [widthDp] is the window's width at the system's own scale. Up to 1280dp nothing changes;
 * from there the scale grows gently with the width (35% of the extra width) and stops at 1.35 (2560dp).
 * Rounded to hundredths so a drag of the window edge does not shimmer the text by a fraction of a pixel.
 */
fun deskScale(widthDp: Float): Float {
    val raw = 1f + (widthDp - 1280f) / 1280f * 0.35f
    return ((raw.coerceIn(1f, 1.35f)) * 100f).roundToInt() / 100f
}
