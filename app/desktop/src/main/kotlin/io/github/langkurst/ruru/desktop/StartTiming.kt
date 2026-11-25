package io.github.langkurst.ruru.desktop

/**
 * Opt-in start-up timing: RURU_TIMING=1 prints, on stderr, how many milliseconds after the process
 * started each step happened, and how long after the last request to open the window. Off by default;
 * it costs nothing then.
 */
internal object StartTiming {
    val on: Boolean = System.getenv("RURU_TIMING") == "1"
    private val started: Long = runCatching { ProcessHandle.current().info().startInstant().get().toEpochMilli() }
        .getOrDefault(System.currentTimeMillis())
    @Volatile private var requested = 0L

    /** The window was asked for (a launch, a tray click, a second start of the program). */
    fun request() {
        if (on) requested = System.currentTimeMillis()
    }

    fun mark(what: String) {
        if (!on) return
        val now = System.currentTimeMillis()
        val sinceRequest = if (requested > 0) " (${now - requested} ms after the open request)" else ""
        System.err.println("RURU_TIMING ${now - started} ms: $what$sinceRequest at=$now")
    }
}
