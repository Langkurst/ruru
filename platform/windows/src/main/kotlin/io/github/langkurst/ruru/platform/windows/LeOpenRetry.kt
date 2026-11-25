package io.github.langkurst.ruru.platform.windows

import kotlinx.coroutines.delay

/**
 * Headphones stop advertising and taking new Bluetooth LE links for a while after an LE session
 * (the hardware notes), so "no-device" from the helper is worth waiting out before reporting it.
 * Retries only that failure, with growing pauses, for at most [totalMs]; logs once. The clock and
 * the pause are parameters so a test can run it without waiting.
 */
internal class LeOpenRetry(
    private val totalMs: Long = TOTAL_MS,
    private val firstDelayMs: Long = FIRST_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    suspend fun <T> run(open: () -> T): T {
        val start = nowMs()
        var wait = firstDelayMs
        var logged = false
        while (true) {
            try {
                return open()
            } catch (e: WinBleException) {
                if (!retryable(e) || nowMs() - start + wait > totalMs) throw e
                if (!logged) {
                    logged = true
                    log("the headphones are not taking an LE link right now; trying again for up to ${totalMs / 1000} s")
                }
                pause(wait)
                wait = minOf(wait * 2, maxDelayMs)
            }
        }
    }

    companion object {
        const val TOTAL_MS = 20_000L
        const val FIRST_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 4_000L

        /** Only "not advertising / no device"; a refused pairing or a helper fault will not get better by waiting. */
        fun retryable(e: WinBleException): Boolean = e.failure.code == "no-device"
    }
}
