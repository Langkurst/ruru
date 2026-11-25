package io.github.langkurst.ruru.vendors.gaia

private const val DIGITS = "0123456789abcdef"

/** Lower-case hex of the low [bytes] bytes of [value], zero-padded (common Kotlin has no `format`). */
internal fun hex(value: Int, bytes: Int): String = buildString {
    for (i in bytes - 1 downTo 0) {
        val b = (value ushr (i * 8)) and 0xFF
        append(DIGITS[b ushr 4]).append(DIGITS[b and 0x0F])
    }
}
