package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.SupportTier
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.apple.AppleStrings
import io.github.langkurst.ruru.vendors.apple.VirtualAirPods
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AirPods and Beats through the real controller: the battery comes from adverts, and no transport is
 * ever opened for them, whatever routes the system offers.
 */
class AirPodsControllerTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        Logging.sink = { _, _, _, _ -> }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    /** The demo's AirPods, but offering every route kind and counting every open. */
    private class Counting(private val inner: VirtualAirPods, override val listensForAdverts: Boolean = true) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        override val routes = RouteKind.entries.toSet()
        @Volatile var opens = 0
        @Volatile var listens = 0
        override suspend fun headsets() = inner.headsets()
        override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
            opens++
            error("nothing may be opened for AirPods")
        }
        override suspend fun adverts(): List<HeadsetAdvert> = inner.adverts().also { listens++ }
    }

    private fun controller(platform: HeadsetPlatform) =
        HeadsetController(platform, scope, advertPauseMs = 20).also { it.start() }

    private suspend fun HeadsetController.await(predicate: (AppState) -> Boolean): AppState =
        withTimeout(15_000) { state.first(predicate) }

    @Test
    fun `adverts become the battery and nothing is opened`() = runBlocking {
        val platform = Counting(VirtualAirPods())
        val c = controller(platform)
        val s = c.await { it.phase == Phase.Connected && it.headset.batteryPercent != null }
        withTimeout(15_000) { while (platform.listens < 3) delay(10) } // it keeps listening
        assertEquals(80, s.headset.batteryPercent)
        assertEquals(90, s.headset.battery?.right?.percent)
        assertEquals(60, s.headset.battery?.case?.percent)
        assertEquals("AirPods Pro", s.headset.connection.deviceName)
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(s.headset))
        assertTrue(s.headset.capabilities.batteryOnly)
        assertEquals(0, platform.opens, "no transport is opened for AirPods")
        c.stop()
    }

    @Test
    fun `a command on AirPods says not connected and sends nothing`() = runBlocking {
        val platform = Counting(VirtualAirPods())
        val c = controller(platform)
        c.await { it.phase == Phase.Connected }
        c.setMode(1)
        val s = c.await { it.message != null }
        assertEquals(Words("message_disconnected"), s.message)
        assertEquals(0, platform.opens)
        c.stop()
    }

    @Test
    fun `a system that cannot hear adverts says so and opens nothing`() = runBlocking {
        val platform = Counting(VirtualAirPods(), listensForAdverts = false)
        val c = controller(platform)
        val failed = c.await { it.phase is Phase.Failed }
        assertEquals(Words(AppleStrings.NOTE_NOT_POSSIBLE), (failed.phase as Phase.Failed).reason)
        assertEquals(0, platform.opens)
        assertEquals(0, platform.listens, "it doesn't even listen")
        assertFalse(failed.headset.isConnected)
        c.stop()
    }
}
