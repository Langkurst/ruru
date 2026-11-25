package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.Logging

/**
 * Log tag `Ruru/Bragi` for the Skullcandy code (connection, session). Lines carry service and
 * method numbers, sizes and decoded settings, never raw payloads: the serial number and the
 * pairing keys travel in payloads, and the connection report is what people paste into public bug
 * reports. Every line also goes into [LogRing].
 */
internal object BragiLog {
    const val TAG = "Ruru/Bragi"
    fun d(msg: String) { LogRing.add('D', "Bragi", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Bragi", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Bragi", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
}
