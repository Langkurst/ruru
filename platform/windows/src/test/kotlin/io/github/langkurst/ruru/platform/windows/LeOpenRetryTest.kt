package io.github.langkurst.ruru.platform.windows

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LeOpenRetryTest {
    private fun noDevice() = WinBleException(WinBleProtocol.Failure("no-device", "not advertising"))

    /** A retry on a clock that only moves when it pauses. */
    private class Fake {
        var now = 0L
        val pauses = ArrayList<Long>()
        val logs = ArrayList<String>()
        val retry = LeOpenRetry(nowMs = { now }, pause = { now += it; pauses += it }, log = { logs += it })
    }

    @Test
    fun succeedsOnTheThirdTryAndLogsOnce() = runBlocking {
        val f = Fake()
        var calls = 0
        val result = f.retry.run { if (++calls < 3) throw noDevice() else "open" }
        assertEquals("open", result)
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), f.pauses)
        assertEquals(1, f.logs.size)
    }

    @Test
    fun givesUpWithinTwentySecondsAndReportsTheFailure() = runBlocking {
        val f = Fake()
        var calls = 0
        val e = assertFailsWith<WinBleException> { f.retry.run<String> { calls++; throw noDevice() } }
        assertEquals("no-device", e.failure.code)
        assertTrue(f.now <= 20_000, "waited ${f.now} ms")
        assertTrue(f.now >= 15_000, "gave up too early at ${f.now} ms")
        assertEquals(calls - 1, f.pauses.size)
        assertEquals(1, f.logs.size)
        assertTrue(f.pauses.all { it <= LeOpenRetry.MAX_DELAY_MS })
    }

    @Test
    fun otherFailuresAreNotRetried() = runBlocking {
        val f = Fake()
        var calls = 0
        assertFailsWith<WinBleException> {
            f.retry.run<String> { calls++; throw WinBleException(WinBleProtocol.Failure("not-paired", "wants pairing")) }
        }
        assertFailsWith<IOException> { f.retry.run<String> { calls++; throw IOException("helper stopped") } }
        assertEquals(2, calls)
        assertTrue(f.pauses.isEmpty() && f.logs.isEmpty())
    }

    @Test
    fun aFirstTimeSuccessWaitsAndLogsNothing() = runBlocking {
        val f = Fake()
        assertEquals(5, f.retry.run { 5 })
        assertTrue(f.pauses.isEmpty() && f.logs.isEmpty())
    }
}
