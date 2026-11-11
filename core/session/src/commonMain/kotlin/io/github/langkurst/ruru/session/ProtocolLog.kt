package io.github.langkurst.ruru.session

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.Logging

/** Log for the typed protocol layer. No personal data beyond the headphones' Bluetooth address. */
internal object ProtocolLog {
    const val TAG = "Ruru/Protocol"
    fun d(msg: String) { LogRing.add('D', "Protocol", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Protocol", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Protocol", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
}
