package io.github.langkurst.ruru.android.eq

import io.github.langkurst.ruru.android.service.ServiceNeed
import io.github.langkurst.ruru.android.service.ServiceNeeds
import io.github.langkurst.ruru.app.eq.EqBackendStatus
import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.dsp.ProEqState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Android backend end to end against fake effects and a fake output route. */
class AndroidEqBackendTest {
    private class FakeRoute(bluetooth: Boolean) : OutputRoute {
        val flow = MutableStateFlow(bluetooth)
        override val bluetooth: StateFlow<Boolean> = flow
        override val sampleRate: Int? = 48_000
        override fun refresh() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val registry = SessionRegistry()
    private val needs = ServiceNeeds()
    private val effects = FakeEffects()
    private val settings = MemorySettingsStore()
    private val route = FakeRoute(bluetooth = true)
    private val on = ProEqState(enabled = true)

    private fun backend() = AndroidEqBackend(
        settings, scope, registry, needs, route, DynamicsProcessingEngine(32, effects), { 0L }, Dispatchers.Unconfined,
    )

    @AfterTest
    fun stop() = scope.cancel()

    private fun running() = effects.created.filter { !it.released }.map { it.sessionId }.toSet()

    @Test
    fun `off by default is ready with no effect and no service`() {
        val backend = backend()
        registry.open(5, null)
        assertEquals(EqBackendStatus.Ready, backend.status.value)
        assertEquals(emptySet(), running())
        assertEquals(emptySet(), needs.needs.value)
        assertTrue(backend.hasLimiter)
    }

    @Test
    fun `on puts an effect on each announced session and asks for the service`() = runBlocking {
        val backend = backend()
        backend.apply(on)
        assertEquals(EqBackendStatus.On, backend.status.value)
        assertEquals(setOf(ServiceNeed.Eq), needs.needs.value)
        assertEquals(emptySet(), running(), "no app has announced its sound yet")

        registry.open(5, "player")
        registry.open(6, null)
        assertEquals(setOf(5, 6), running())

        registry.close(5)
        assertEquals(setOf(6), running(), "a closed session's effect is freed")
    }

    @Test
    fun `a changed state is applied to the running effect`() = runBlocking {
        val backend = backend()
        registry.open(5, null)
        backend.apply(on)
        val effect = effects.created.single()
        assertEquals(1, effect.plans.size)
        backend.apply(on.copy(limiterEnabled = false))
        assertEquals(2, effect.plans.size)
        assertFalse(effect.plans.last().limiter.enabled)
        assertEquals(1, effects.created.size, "no second effect")
    }

    @Test
    fun `leaving Bluetooth headphones pauses it and coming back runs it again`() = runBlocking {
        val backend = backend()
        registry.open(5, null)
        backend.apply(on)
        assertEquals(setOf(5), running())
        assertFalse(backend.paused.value)

        route.flow.value = false // "This phone" picked in the output switcher
        assertEquals(emptySet(), running(), "the speaker is not filtered")
        assertTrue(backend.paused.value)
        assertEquals(EqBackendStatus.On, backend.status.value, "still on, just paused")
        assertEquals(setOf(ServiceNeed.Eq), needs.needs.value, "the service stays, ready for the headphones")

        route.flow.value = true
        assertEquals(setOf(5), running(), "the player's session was kept through the short drop")
        assertFalse(backend.paused.value)
    }

    @Test
    fun `off frees every effect and lets go of the service`() = runBlocking {
        val backend = backend()
        registry.open(5, null)
        backend.apply(on)
        backend.off()
        assertEquals(emptySet(), running())
        assertEquals(EqBackendStatus.Ready, backend.status.value)
        assertEquals(emptySet(), needs.needs.value)
        registry.open(6, null)
        assertEquals(emptySet(), running(), "a session announced while off gets nothing")
    }

    @Test
    fun `applying a state that is not enabled is the same as off`() = runBlocking {
        val backend = backend()
        registry.open(5, null)
        backend.apply(on)
        backend.apply(on.copy(enabled = false))
        assertEquals(emptySet(), running())
        assertEquals(EqBackendStatus.Ready, backend.status.value)
        assertEquals(emptySet(), needs.needs.value)
    }

    @Test
    fun `a phone without the effect is unavailable and does not keep a service`() = runBlocking {
        effects.failCreate = { _, _ -> IllegalArgumentException("Effect type not supported") }
        val backend = backend()
        registry.open(5, null)
        backend.apply(on)
        assertIs<EqBackendStatus.Unavailable>(backend.status.value)
        assertEquals(emptySet(), needs.needs.value)
    }

    @Test
    fun `the global option adds session 0, is remembered, and switching it off frees it`() = runBlocking {
        val backend = backend()
        backend.apply(on)
        assertEquals(emptySet(), running())
        backend.setUseGlobalSession(true)
        assertEquals(setOf(0), running())
        assertTrue(settings.getBoolean(AndroidEqBackend.KEY_GLOBAL_SESSION, false))
        backend.setUseGlobalSession(false)
        assertEquals(emptySet(), running())
        assertFalse(settings.getBoolean(AndroidEqBackend.KEY_GLOBAL_SESSION, true))
    }

    @Test
    fun `the global option set earlier is read when the backend starts`() = runBlocking {
        settings.putBoolean(AndroidEqBackend.KEY_GLOBAL_SESSION, true)
        val backend = backend()
        assertTrue(backend.useGlobalSession.value)
        backend.apply(on)
        assertEquals(setOf(0), running())
    }
}
