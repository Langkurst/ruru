package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.Logging

/**
 * Log tag `Ruru/Soundcore` for the Soundcore code (connection, session). Lines name commands and
 * sizes only: the state holds the earbuds' serial, which embeds their address on some models, so
 * payload bytes are never logged. Every line also goes into [LogRing] for the connection report.
 */
internal object SoundcoreLog {
    const val TAG = "Ruru/Soundcore"
    fun d(msg: String) { LogRing.add('D', "Soundcore", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Soundcore", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Soundcore", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
}

/** Two lower-case hex digits (common Kotlin has no `String.format`). */
internal fun hex2(value: Int): String = (value and 0xFF).toString(16).padStart(2, '0')

/** Printable ASCII text of [len] bytes of [b] from [from]; other bytes become `?`. Common Kotlin has no US-ASCII `String(...)`. */
internal fun asciiOf(b: ByteArray, from: Int, len: Int): String = buildString(len) {
    for (i in from until from + len) {
        val c = b[i].toInt() and 0xFF
        append(if (c in 0x20..0x7E) c.toChar() else '?')
    }
}
