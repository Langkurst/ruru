package io.github.langkurst.ruru.android.eq

import io.github.langkurst.ruru.dsp.DynamicsProcessingPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake effect that fails the way Android's DynamicsProcessing does (no Android needed). */
internal class FakeEffect(val sessionId: Int, val bandCount: Int) : SessionEffect {
    /** Thrown by the next apply: what another app's higher-priority effect or a dead audioserver causes. */
    var applyFailure: RuntimeException? = null
    var released = false
    val plans = ArrayList<DynamicsProcessingPlan.Plan>()

    override fun apply(plan: DynamicsProcessingPlan.Plan, enabled: Boolean) {
        if (released) throw IllegalStateException("AudioEffect: effect not initialized")
        applyFailure?.let { throw it }
        plans += plan
    }

    override fun release() {
        released = true
    }
}

internal class FakeEffects : SessionEffectFactory {
    val created = ArrayList<FakeEffect>()

    /** Decides per create call whether (and how) it fails; null = it works. */
    var failCreate: (sessionId: Int, bandCount: Int) -> RuntimeException? = { _, _ -> null }

    /** Whether a freshly created effect then refuses its parameters. */
    var failApply: (bandCount: Int) -> RuntimeException? = { null }

    override fun create(sessionId: Int, bandCount: Int): SessionEffect {
        failCreate(sessionId, bandCount)?.let { throw it }
        return FakeEffect(sessionId, bandCount).also {
            it.applyFailure = failApply(bandCount)
            created += it
        }
    }
}

/** The engine's attach, re-apply and fallback rules against fake effects. */
class DynamicsProcessingEngineTest {
    private val planner = Planner { n ->
        DynamicsProcessingPlan.Plan(
            bands = List(n) { DynamicsProcessingPlan.Band(cutoffHz = 100.0 * (it + 1), gainDb = 0.0) },
            inputGainDb = -3.0,
            limiter = DynamicsProcessingPlan.LimiterParams(enabled = true),
            sampleRate = 48_000,
        )
    }

    private val effects = FakeEffects()
    private val engine = DynamicsProcessingEngine(32, effects)

    @Test
    fun `attach creates one effect per session and reuses it`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        assertTrue(engine.attach(7, planner, enabled = true))
        assertEquals(1, effects.created.size)
        assertEquals(2, effects.created.single().plans.size, "the second attach re-applies the plan")
        assertEquals(setOf(7), engine.attached)
        assertEquals(32, effects.created.single().bandCount)
    }

    @Test
    fun `a phone that caps the bands gets a coarser layout`() {
        effects.failCreate = { _, bands -> if (bands > 16) IllegalArgumentException("BAD_VALUE") else null }
        assertTrue(engine.attach(7, planner, enabled = true))
        assertEquals(16, effects.created.single().bandCount)
        assertTrue(engine.status.value.isAvailable)
    }

    @Test
    fun `a layout the effect refuses to apply is released and the next one tried`() {
        effects.failApply = { bands -> if (bands > 10) UnsupportedOperationException("too many") else null }
        assertTrue(engine.attach(7, planner, enabled = true))
        val sizes = effects.created.map { it.bandCount }
        assertEquals(listOf(32, 16, 10), sizes)
        assertTrue(effects.created.take(2).all { it.released }, "the refused effects were freed")
        assertFalse(effects.created.last().released)
    }

    @Test
    fun `a dead effect is rebuilt on the next attach`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        effects.created.single().applyFailure = IllegalStateException("audioserver died")
        assertTrue(engine.attach(7, planner, enabled = true))
        assertEquals(2, effects.created.size)
        assertTrue(effects.created.first().released)
        assertNull(engine.status.value.warning)
    }

    @Test
    fun `an effect another app took over is reported and tried again next time`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        effects.created.single().applyFailure = UnsupportedOperationException("taken over")
        effects.failApply = { UnsupportedOperationException("taken over") }
        assertFalse(engine.attach(7, planner, enabled = true))
        assertEquals(EngineWarning.TakenOver, engine.status.value.warning)
        assertTrue(engine.status.value.isAvailable, "the phone supports the effect, someone else holds the session")

        engine.retain(emptySet()) // the player closed the session
        assertNull(engine.status.value.warning)
    }

    @Test
    fun `a phone without the effect says so, and not once a session works`() {
        effects.failCreate = { _, _ -> IllegalArgumentException("Effect type not supported") }
        assertFalse(engine.attach(7, planner, enabled = true))
        assertFalse(engine.status.value.isAvailable)
        assertEquals(EngineWarning.Unsupported, engine.status.value.warning)

        effects.failCreate = { _, _ -> null }
        assertTrue(engine.attach(8, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)
        assertNull(engine.status.value.warning)
    }

    @Test
    fun `one refused session next to a working one is only a quirk of that app`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        effects.failCreate = { session, _ -> if (session == 9) IllegalArgumentException("invalid session") else null }
        assertFalse(engine.attach(9, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)
        assertNull(engine.status.value.warning)
    }

    @Test
    fun `the global mix being refused is its own warning and goes when the option is switched off`() {
        effects.failCreate = { session, _ -> if (session == 0) RuntimeException("Cannot initialize effect engine") else null }
        assertFalse(engine.attach(0, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)
        assertEquals(EngineWarning.GlobalRefused, engine.status.value.warning)
        engine.clearWarning()
        assertNull(engine.status.value.warning)
    }

    @Test
    fun `retain frees effects outside the wanted sessions and detachAll frees the rest`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        assertTrue(engine.attach(8, planner, enabled = true))
        engine.retain(setOf(8))
        assertEquals(setOf(8), engine.attached)
        assertTrue(effects.created.first { it.sessionId == 7 }.released)
        assertFalse(effects.created.first { it.sessionId == 8 }.released)
        engine.detachAll()
        assertEquals(emptySet(), engine.attached)
        assertTrue(effects.created.all { it.released })
    }
}
