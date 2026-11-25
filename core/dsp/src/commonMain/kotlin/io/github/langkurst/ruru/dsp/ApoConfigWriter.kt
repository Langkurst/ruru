package io.github.langkurst.ruru.dsp

/**
 * Writes the Equalizer APO config file Ruru keeps next to `config.txt` (`ruru.txt`):
 *
 * ```
 * # Written by Ruru. Changes are overwritten.
 * Device: <part of the output's name>      (only when a device is given)
 * Preamp: -3.0 dB
 * Filter 1: ON PK Fc 105 Hz Gain -3.0 dB Q 0.70
 * ```
 *
 * `PK` peaking, `LSC` low shelf, `HSC` high shelf. Values are rounded to what is printed and
 * the preamp is the headroom value ([ProEqState.desktopPreampDb]), because APO has no limiter.
 * Disabled bands and bands that round to 0 dB are left out. An off state writes a flat file.
 */
object ApoConfigWriter {
    const val FILE_NAME = "ruru.txt"
    const val INCLUDE_LINE = "Include: $FILE_NAME"
    const val HEADER = "# Written by Ruru. Changes are overwritten."

    private val lineBreaks = Regex("[\\r\\n]+")

    fun write(state: ProEqState, deviceMatch: String? = null): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        deviceMatch?.replace(lineBreaks, " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { sb.append("Device: ").append(it).append('\n') }
        val active = if (state.enabled) state.sanitized() else ProEqState()
        sb.append("Preamp: ").append(active.desktopPreampDb().toFixed(1)).append(" dB\n")
        var n = 0
        for (b in active.outputBands()) {
            if (b.gainDb == 0.0) continue
            n++
            sb.append("Filter ").append(n).append(": ON ").append(typeCode(b.type))
                .append(" Fc ").append(freq(b.frequencyHz)).append(" Hz")
                .append(" Gain ").append(b.gainDb.toFixed(1)).append(" dB")
                .append(" Q ").append(b.q.toFixed(2)).append('\n')
        }
        return sb.toString()
    }

    private fun typeCode(t: FilterType) = when (t) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSC"
        FilterType.HIGH_SHELF -> "HSC"
    }

    private fun freq(v: Double): String =
        if (v == ProEq.round(v, 1.0)) v.toFixed(0) else v.toFixed(1)
}
