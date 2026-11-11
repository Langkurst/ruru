package io.github.langkurst.ruru.session

/** Keeps the lines, prints nothing. */
class RecordingLog : SessionLog {
    val lines = mutableListOf<String>()
    override fun d(msg: String) { lines += "D $msg" }
    override fun i(msg: String) { lines += "I $msg" }
    override fun w(msg: String, t: Throwable?) { lines += "W $msg" }
}
