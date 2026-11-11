package io.github.langkurst.ruru.session

import io.github.langkurst.ruru.link.LogLevel
import io.github.langkurst.ruru.link.Logging

/**
 * Where a session's log lines go. Injected so tests can keep their output quiet. Lines carry no
 * personal data beyond the headset's Bluetooth address.
 */
interface SessionLog {
    fun d(msg: String)
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
}

/** The app-wide [Logging.sink] under [tag]. Not copied into the connection report. */
class SinkSessionLog(private val tag: String) : SessionLog {
    override fun d(msg: String) = Logging.sink(LogLevel.DEBUG, tag, msg, null)
    override fun i(msg: String) = Logging.sink(LogLevel.INFO, tag, msg, null)
    override fun w(msg: String, t: Throwable?) = Logging.sink(LogLevel.WARN, tag, msg, t)
}
