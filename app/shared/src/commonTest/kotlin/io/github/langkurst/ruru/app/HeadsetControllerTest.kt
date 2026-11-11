package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The app's controller against the simulated 1st Gen, in real time (a few seconds in all). */
class HeadsetControllerTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        Logging.sink = { _, _, _, _ -> }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class FlakyPlatform(val device: VirtualQcUltra2) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        var connected = true
        var opens = 0
        var last: VirtualTransport? = null
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", connected))
        override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
            opens++
            device.powerOn()
            return VirtualTransport(device).also { last = it }
        }
    }

    private fun controller(platform: HeadsetPlatform) = HeadsetController(platform, scope).also { it.start() }

    private suspend fun HeadsetController.await(timeoutMs: Long = 15_000, predicate: (AppState) -> Boolean): AppState =
        withTimeout(timeoutMs) { state.first(predicate) }

    @Test
    fun `connects to the demo headset and shows the 1st Gen as tested`() = runBlocking {
        val c = controller(DemoPlatform())
        val s = c.await { it.phase == Phase.Connected }
        assertEquals("QuietComfort Ultra Headphones", s.headset.info?.modelName)
        assertFalse(s.headset.capabilities.liveNoiseControl)
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Commute", "Focus"), s.headset.modes.filter { it.isPreset || it.isConfigured }.map { it.name })
        assertNotNull(s.headset.batteryPercent)
        c.stop()
    }

    @Test
    fun `mode, noise and equaliser changes reach the headset`() = runBlocking {
        val c = controller(DemoPlatform())
        c.await { it.phase == Phase.Connected }
        c.setMode(3) // Commute
        c.await { it.headset.currentModeIndex == 3 }
        c.setNoise(NoiseSettings(cancellingLevel = 2))
        val noisy = c.await { it.headset.noise?.cancellingLevel == 2 }
        assertEquals(null, noisy.message)
        c.setEq(HeadphoneEq(bass = 4))
        c.await { it.headset.eq == HeadphoneEq(bass = 4) }
        c.stop()
    }

    @Test
    fun `a built-in mode explains why its noise level cannot change`() = runBlocking {
        val c = controller(DemoPlatform())
        c.await { it.phase == Phase.Connected && it.headset.currentModeIndex == 0 }
        c.setNoise(NoiseSettings(cancellingLevel = 3))
        val s = c.await { it.message != null }
        assertTrue(s.message!!.isNotBlank())
        c.stop()
    }

    @Test
    fun `saving and removing a custom mode`() = runBlocking {
        val c = controller(DemoPlatform())
        c.await { it.phase == Phase.Connected }
        c.saveMode(5, "Gym", NoiseSettings(cancellingLevel = 6))
        val saved = c.await { s -> s.headset.modes.any { it.index == 5 && it.name == "Gym" } }
        assertEquals(6, saved.headset.modes.first { it.index == 5 }.settings?.cancellingLevel)
        c.removeMode(5)
        c.await { s -> s.headset.modes.none { it.index == 5 && it.name == "Gym" } }
        c.stop()
    }

    @Test
    fun `reconnects by itself after the link drops`() = runBlocking {
        val platform = FlakyPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1))
        val c = controller(platform)
        c.await { it.phase == Phase.Connected }
        platform.last!!.close()
        c.await { it.phase != Phase.Connected }
        c.await { it.phase == Phase.Connected }
        assertTrue(platform.opens >= 2)
        c.stop()
    }

    @Test
    fun `no connected headset is reported, then found when it connects`() = runBlocking {
        val platform = FlakyPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1)).apply { connected = false }
        val c = controller(platform)
        val none = c.await { it.phase is Phase.NoHeadset }
        assertIs<Phase.NoHeadset>(none.phase)
        assertEquals("Test QC Ultra", (none.phase as Phase.NoHeadset).pairedName)
        platform.connected = true
        c.retryNow()
        c.await { it.phase == Phase.Connected }
        c.stop()
    }

    @Test
    fun `connection report masks Bluetooth addresses`() {
        val text = ConnectionReport.build(AppState(platformName = "Test"), "0.0.0")
        assertFalse(Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b").containsMatchIn(text.replace("xx:xx:xx:xx:xx:xx", "")))
        assertTrue(text.startsWith("Ruru 0.0.0 on Test"))
    }
}
