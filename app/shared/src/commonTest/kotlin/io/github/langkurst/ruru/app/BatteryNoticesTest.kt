package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.app.BatteryNotices.Notice.CHARGED
import io.github.langkurst.ruru.app.BatteryNotices.Notice.LOW
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryNoticesTest {
    /** Feeds readings in order and returns what each one produced. */
    private fun BatteryNotices.feed(vararg readings: Pair<Int?, Boolean?>) = readings.map { (p, c) -> next(p, c) }

    @Test
    fun `a charge that finishes is announced once`() {
        val n = BatteryNotices()
        assertEquals(listOf(null, null, CHARGED, null, null), n.feed(95 to true, 99 to true, 100 to true, 100 to true, 100 to true))
    }

    @Test
    fun `full headphones already on the charger when Ruru starts say nothing`() {
        assertEquals(listOf(null, null), BatteryNotices().feed(100 to true, 100 to true))
    }

    @Test
    fun `after unplugging, the next full charge is announced again`() {
        val n = BatteryNotices()
        assertEquals(
            listOf(null, CHARGED, null, null, null, CHARGED),
            n.feed(98 to true, 100 to true, 100 to false, 97 to false, 97 to true, 100 to true),
        )
    }

    @Test
    fun `a battery running low is announced once, not at every reading`() {
        val n = BatteryNotices()
        assertEquals(listOf(null, null, LOW, null, null), n.feed(20 to false, 16 to false, 15 to false, 12 to false, 9 to false))
    }

    @Test
    fun `low again after a charge above the mark is announced again`() {
        val n = BatteryNotices()
        assertEquals(
            listOf(null, LOW, null, null, null, LOW),
            n.feed(16 to false, 15 to false, 15 to true, 30 to true, 30 to false, 15 to false),
        )
    }

    @Test
    fun `low while on the charger says nothing`() {
        assertEquals(listOf(null, null), BatteryNotices().feed(16 to true, 15 to true))
    }

    @Test
    fun `starting already low says nothing, the screen shows it in the alarm colour`() {
        assertEquals(listOf(null, null), BatteryNotices().feed(10 to false, 9 to false))
    }

    @Test
    fun `a reconnect is never mistaken for a change`() {
        val n = BatteryNotices()
        // Disconnected (no reading) at 99, back at 100 while charging: no "charged" for a change not seen.
        assertEquals(listOf(null, null, null), n.feed(99 to true, null to null, 100 to true))
    }

    @Test
    fun `headphones that don't report charging are treated as not charging`() {
        val n = BatteryNotices()
        assertEquals(listOf(null, LOW, null), n.feed(16 to null, 15 to null, 100 to null))
    }
}
