package io.github.langkurst.ruru.android.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The list of announced audio sessions, and how long they are kept. */
class SessionRegistryTest {
    private var clock = 1_000L
    private val registry = SessionRegistry { clock }

    private fun ids() = registry.sessions.value.map { it.sessionId }

    @Test
    fun `an announced session is listed once, newest last, and a close removes it`() {
        registry.open(11, "one")
        registry.open(12, null)
        registry.open(11, "one") // announced again: moves to the end, no duplicate
        assertEquals(listOf(12, 11), ids())
        registry.close(12)
        assertEquals(listOf(11), ids())
        registry.close(99) // unknown: nothing happens
        assertEquals(listOf(11), ids())
    }

    @Test
    fun `session 0 and negative ids are never listed`() {
        registry.open(0, "x")
        registry.open(-1, "x")
        assertEquals(emptyList(), ids())
    }

    @Test
    fun `old sessions go, except one that carries an effect, which may be older`() {
        registry.open(11, "audiobook")
        registry.open(12, null)
        val opened = clock
        registry.pruneOlderThan(6 * HOUR, opened + 7 * HOUR, attached = setOf(11), attachedMaxAgeMillis = 24 * HOUR)
        assertEquals(listOf(11), ids(), "only the session without an effect goes")
        registry.pruneOlderThan(6 * HOUR, opened + 25 * HOUR, attached = setOf(11), attachedMaxAgeMillis = 24 * HOUR)
        assertEquals(emptyList(), ids(), "a player that died without closing still goes in the end")
    }

    @Test
    fun `a short Bluetooth drop keeps the sessions and a long one forgets them once`() {
        val grace = BluetoothLossGrace(graceMillis = 120_000)
        assertFalse(grace.shouldForgetSessions(present = true, nowMillis = 0))
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 1_000), "just lost")
        assertFalse(grace.shouldForgetSessions(present = true, nowMillis = 30_000), "back within the grace")
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 600_000))
        assertTrue(grace.shouldForgetSessions(present = false, nowMillis = 800_000), "still gone after the grace")
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 900_000), "only once")
    }

    @Test
    fun `a session that carried an effect survives a short drop past the normal age limit`() {
        val aging = SessionAging(registry, graceMillis = 120_000, maxAgeMillis = 6 * HOUR, carriedMaxAgeMillis = 24 * HOUR)
        registry.open(11, "sleepsounds")
        val opened = clock
        aging.prune(btPresent = true, elapsedMillis = 0, wallMillis = opened + HOUR)
        aging.noteAttached(setOf(11))
        // Bluetooth drops for a second at hour 7: the effect is released but the session is still listed.
        aging.prune(btPresent = false, elapsedMillis = 7 * HOUR, wallMillis = opened + 7 * HOUR)
        aging.noteAttached(emptySet())
        assertEquals(listOf(11), ids())
        aging.prune(btPresent = true, elapsedMillis = 7 * HOUR + 1_000, wallMillis = opened + 7 * HOUR + 1_000)
        assertEquals(listOf(11), ids())
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
