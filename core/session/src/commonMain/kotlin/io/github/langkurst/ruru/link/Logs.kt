package io.github.langkurst.ruru.link

import io.github.langkurst.ruru.core.HeadphoneCommandException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Log levels, numbered like Android's so a platform sink can pass them straight through. */
object LogLevel {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
}

/** Where log lines go: each app sets this (Android's log, a file on desktop). Default: nowhere. */
typealias LogSink = (priority: Int, tag: String, msg: String, t: Throwable?) -> Unit

object Logging {
    @Volatile
    var sink: LogSink = { _, _, _, _ -> }
}

/**
 * The last few hundred connection and protocol log lines, kept in memory for the connection
 * report a user can paste into a bug report. Nothing is written to disk.
 */
object LogRing : SynchronizedObject() {
    private const val CAPACITY = 400
    private val lines = ArrayDeque<String>(CAPACITY)

    @OptIn(ExperimentalTime::class)
    fun add(level: Char, tag: String, msg: String) {
        // "2026-11-11T00:12:34.567Z" -> "00:12:34.567" (UTC)
        val time = Clock.System.now().toString().substringAfter('T').take(12)
        synchronized(this) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast("$time $level $tag $msg")
        }
    }

    fun tail(n: Int = 150): List<String> = synchronized(this) { lines.toList().takeLast(n) }

    fun clear() { synchronized(this) { lines.clear() } }
}

/** Transport-layer log. No personal data beyond the headphones' Bluetooth address. */
internal object BtLog {
    const val TAG = "Ruru/Bt"
    fun d(msg: String) { LogRing.add('D', "Bt", msg); Logging.sink(LogLevel.DEBUG, TAG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Bt", msg); Logging.sink(LogLevel.INFO, TAG, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Bt", msg); Logging.sink(LogLevel.WARN, TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { LogRing.add('E', "Bt", msg); Logging.sink(LogLevel.ERROR, TAG, msg, t) }
}

/**
 * A failure as one line for logs and the connection report: "IOException: read failed" for a
 * platform exception, just the message for Ruru's own.
 */
internal fun Throwable.describeForReport(): String {
    val text = message ?: "no message"
    val own = this is RfcommConnectException || this is UnresponsiveLinkException ||
        this is ExplainedConnectException || this is HeadphoneCommandException
    return if (own) text else "${this::class.simpleName}: $text"
}
