package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.Logging

/**
 * Log tag `Ruru/Sennheiser` for the Sennheiser code (connection, session). Lines carry command ids,
 * sizes and decoded settings, never raw payloads: the device name and the paired-device list are
 * payloads, and the connection report is what people paste into public bug reports. Every line
 * also goes into [LogRing].
 */
internal object SennheiserLog {
    const val TAG = "Ruru/Sennheiser"
    fun d(msg: String) { LogRing.add('D', "Sennheiser", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Sennheiser", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Sennheiser", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
}
