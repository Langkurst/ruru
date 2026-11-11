package io.github.langkurst.ruru.dsp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Fixed-point text for a number, like Java's `String.format(Locale.ROOT, "%.Nf", v)`, in common
 * code: always a dot, half-up rounding, a minus sign kept on values that round to zero.
 */
internal fun Double.toFixed(decimals: Int): String {
    require(decimals in 0..9) { "decimals out of range: $decimals" }
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "Infinity" else "-Infinity"
    val factor = 10.0.pow(decimals)
    val scaled = floor(abs(this) * factor + 0.5).toLong()
    val sign = if (this < 0.0 || (this == 0.0 && 1.0 / this < 0)) "-" else ""
    if (decimals == 0) return sign + scaled
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val cut = digits.length - decimals
    return sign + digits.substring(0, cut) + "." + digits.substring(cut)
}
