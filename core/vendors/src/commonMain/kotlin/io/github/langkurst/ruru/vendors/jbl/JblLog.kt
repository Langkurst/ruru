package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.Logging

/**
 * Log tag `Ruru/JBL` for the JBL code (connection, session). Lines carry command ids, sizes and
 * decoded settings, never raw payloads: the device info and the TLV hold the headset's name and
 * Bluetooth address, and the connection report is what people paste into public bug reports. Every
 * line also goes into [LogRing].
 */
internal object JblLog {
    const val TAG = "Ruru/JBL"
    fun d(msg: String) { LogRing.add('D', "JBL", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "JBL", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "JBL", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
}
