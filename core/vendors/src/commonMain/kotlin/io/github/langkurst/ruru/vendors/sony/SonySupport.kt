package io.github.langkurst.ruru.vendors.sony

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.Logging

/**
 * Log tag `Ruru/Sony` for the Sony code (connection, session). Lines name commands, inquired types
 * and sizes only: some Sony replies carry the phone's name, paired devices' addresses or the
 * headset's own address, so payload bytes are never logged. Every line also goes into
 * [LogRing] for the connection report.
 */
internal object SonyLog {
    const val TAG = "Ruru/Sony"
    fun d(msg: String) { LogRing.add('D', "Sony", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Sony", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Sony", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
}

/**
 * The few `String.format` patterns the Sony code uses (`%02x`, `%02X`, `%04x`, `%08x`, `%d`, `%s`,
 * `%%`), because common Kotlin has no `format`. Same package, so it also wins over the JVM's.
 */
internal fun String.format(vararg args: Any?): String {
    val out = StringBuilder()
    var next = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { out.append(c); i++; continue }
        i++
        if (i < length && this[i] == '%') { out.append('%'); i++; continue }
        var zero = false
        if (i < length && this[i] == '0') { zero = true; i++ }
        var width = 0
        while (i < length && this[i].isDigit()) { width = width * 10 + (this[i] - '0'); i++ }
        val conv = this[i++]
        val arg = args[next++]
        val text = when (conv) {
            'x' -> unsigned(arg).toString(16)
            'X' -> unsigned(arg).toString(16).uppercase()
            'd' -> (arg as Number).toLong().toString()
            's' -> arg.toString()
            else -> throw IllegalArgumentException("unsupported format %$conv")
        }
        out.append(if (zero) text.padStart(width, '0') else text.padStart(width, ' '))
    }
    return out.toString()
}

private fun unsigned(arg: Any?): Long = when (arg) {
    is Byte -> arg.toLong() and 0xFF
    is Short -> arg.toLong() and 0xFFFF
    is Int -> arg.toLong() and 0xFFFFFFFFL
    is Number -> arg.toLong()
    else -> throw IllegalArgumentException("not a number: $arg")
}
