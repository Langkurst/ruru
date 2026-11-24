package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    /** Fails the first open with the given explanation, then connects like FlakyPlatform. */
    private class ExplainedPlatform(
        val device: VirtualQcUltra2,
        private val explanation: String,
        private val retry: Boolean,
    ) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        var opens = 0
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
        override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
            opens++
            if (opens == 1) throw ExplainedConnectException(explanation, retry)
            device.powerOn()
            return VirtualTransport(device)
        }
    }

    /** Holds the first open() until the test lets it fail with the given explanation. */
    private class HeldOpenPlatform(
        val device: VirtualQcUltra2,
        private val explanation: String,
    ) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        var opens = 0
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
        override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
            opens++
            if (opens == 1) {
                holding.complete(Unit)
                release.await()
                throw ExplainedConnectException(explanation, retry = false)
            }
            device.powerOn()
            return VirtualTransport(device)
        }
    }

    /** Asks for an identity check and decides with [verdict]; records what confirm was given. */
    private class IdentityPlatform(
        val device: VirtualQcUltra2,
        private val checks: Boolean,
        private val verdict: (String?) -> String?,
    ) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        override val checksIdentity = checks
        var confirms = 0
        var confirmedAddress: String? = null
        var last: VirtualTransport? = null
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
        override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
            device.powerOn()
            return VirtualTransport(device).also { last = it }
        }
        override suspend fun confirm(candidate: HeadsetCandidate, address: String?): String? {
            confirms++
            confirmedAddress = address
            return verdict(address)
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
        val message = s.message!!
        assertTrue(message.isNotBlank())
        assertFalse("[" in message, "protocol text reached the screen: $message")
        assertFalse("slot" in message.lowercase(), "the word slot reached the screen: $message")
        c.stop()
    }

    @Test
    fun `refused changes are explained in plain words`() {
        assertEquals("The headphones did not answer. Try again.", HeadsetController.plainMessage("[1.7]: no reply from the headphones"))
        assertEquals("The headphones did not answer. Try again.", HeadsetController.plainMessage("[31.3]: no reply"))
        for (raw in listOf("[31.6]: Invalid length", "[1.7]: error code 9", "Unexpected reply from the headphones ([1.7]): short", "Command failed", null, "")) {
            val shown = HeadsetController.plainMessage(raw)
            assertTrue(shown.startsWith("The headphones did not accept that change"), "for $raw: $shown")
            assertFalse("[" in shown, shown)
        }
        assertEquals("The headphones disconnected. Ruru reconnects by itself.", HeadsetController.plainMessage("Connection to the headphones was lost"))
        assertEquals("The headphones disconnected. Ruru reconnects by itself.", HeadsetController.plainMessage("The headphones stopped answering"))
        assertEquals("That mode is empty. Add a mode first, then choose it.", HeadsetController.plainMessage("Slot 7 is empty: save a custom mode there before selecting it"))
        assertEquals("Only your own modes can be removed.", HeadsetController.plainMessage("Only custom slots (4-10) can be reset"))
        assertEquals(
            "Quiet is built into the headphones and can't be changed. Add a mode of your own instead.",
            HeadsetController.plainMessage("Quiet is a built-in mode and cannot be changed; save to a custom slot instead"),
        )
        assertEquals("Ruru can't control these headphones", HeadsetController.plainMessage("Ruru can't control these headphones"))
    }

    @Test
    fun `a platform that shows nothing says why, instead of saying nothing is paired`() = runBlocking {
        val blocked = object : HeadsetPlatform {
            override val name = "Android"
            override val supported = true
            override val blocked = "Ruru needs Android's Nearby devices permission"
            override suspend fun headsets() = emptyList<HeadsetCandidate>()
            override suspend fun open(candidate: HeadsetCandidate): ByteTransport = error("never opened")
        }
        val c = controller(blocked)
        val s = c.await { it.phase is Phase.NoHeadset }
        assertEquals("Ruru needs Android's Nearby devices permission", (s.phase as Phase.NoHeadset).blocked)
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
    fun `a timeout inside open is a failed attempt, not the end of the loop`() = runBlocking {
        val device = VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1)
        val platform = object : HeadsetPlatform {
            override val name = "Test"
            override val supported = true
            var opens = 0
            override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
            override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
                opens++
                if (opens == 1) withTimeout(10) { awaitCancellation() }
                device.powerOn()
                return VirtualTransport(device)
            }
        }
        val c = controller(platform)
        c.await { it.phase == Phase.Connected }
        assertTrue(platform.opens >= 2)
        c.stop()
    }

    @Test
    fun `stop still ends the loop`() = runBlocking {
        val platform = object : HeadsetPlatform {
            override val name = "Test"
            override val supported = true
            var opens = 0
            override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
            override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
                opens++
                awaitCancellation()
            }
        }
        val c = controller(platform)
        withTimeout(5_000) { while (platform.opens == 0) delay(10) }
        c.stop()
        delay(250)
        assertEquals(1, platform.opens)
        c.start()
        withTimeout(5_000) { while (platform.opens < 2) delay(10) }
        c.stop()
    }

    @Test
    fun `a pairing problem is shown as explained and waits for Try again`() = runBlocking {
        val platform = ExplainedPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1), "Pair message", retry = false)
        val c = controller(platform)
        val failed = c.await { it.phase is Phase.Failed }
        assertEquals("Pair message", (failed.phase as Phase.Failed).reason)
        delay(2_500) // longer than the first backoff, so a retry would already have happened
        assertEquals(1, platform.opens)
        c.retryNow()
        c.await { it.phase == Phase.Connected }
        assertTrue(platform.opens >= 2)
        c.stop()
    }

    @Test
    fun `a Try again tap made before a failure doesn't skip the wait`() = runBlocking {
        val platform = HeldOpenPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1), "Pair message")
        val c = controller(platform)
        platform.holding.await()
        c.retryNow() // made while open() is still waiting, before the failure is known
        platform.release.complete(Unit)
        val failed = c.await { it.phase is Phase.Failed }
        assertEquals("Pair message", (failed.phase as Phase.Failed).reason)
        delay(2_500) // longer than the first backoff, so a stale tap would have reopened by now
        assertEquals(1, platform.opens)
        c.retryNow()
        c.await { it.phase == Phase.Connected }
        assertTrue(platform.opens >= 2)
        c.stop()
    }

    @Test
    fun `an explained retryable failure retries by itself`() = runBlocking {
        val platform = ExplainedPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1), "Pair message", retry = true)
        val c = controller(platform)
        c.await { it.phase == Phase.Connected }
        assertTrue(platform.opens >= 2)
        c.stop()
    }

    @Test
    fun `a platform that asks for identity is only sent the address read before a mismatch ends the attempt`() = runBlocking {
        val platform = IdentityPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1), checks = true) { "Not these" }
        val c = controller(platform)
        val failed = c.await { it.phase is Phase.Failed }
        assertEquals("Not these", (failed.phase as Phase.Failed).reason)
        assertEquals(1, platform.confirms)
        val sent = platform.last!!.sentRequests()
        assertEquals(listOf(QcUltra2.MAC_ADDRESS), sent.map { it.address })
        assertTrue(sent.all { it.operator == BmapOperator.GET })
        c.stop()
    }

    @Test
    fun `a platform that asks for identity connects when confirm accepts and gets the headphones' address`() = runBlocking {
        val device = VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1)
        val platform = IdentityPlatform(device, checks = true) { null }
        val c = controller(platform)
        c.await { it.phase == Phase.Connected }
        assertEquals(device.macAddress, platform.confirmedAddress)
        assertEquals(1, platform.confirms)
        c.stop()
    }

    @Test
    fun `a platform that does not ask for identity never has confirm called`() = runBlocking {
        val platform = IdentityPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1), checks = false) { "Not these" }
        val c = controller(platform)
        c.await { it.phase == Phase.Connected }
        assertEquals(0, platform.confirms)
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

    @Test
    fun `connection report after connecting holds no address of the headphones in any form`() = runBlocking {
        val device = VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1)
        val c = controller(FlakyPlatform(device))
        val s = c.await { it.phase == Phase.Connected }
        val text = ConnectionReport.build(s, "0.0.0")
        c.stop()
        val colons = device.macAddress.lowercase()
        val bare = colons.replace(":", "")
        assertTrue(text.contains("Recent log:"), text)
        assertFalse(text.lowercase().contains(colons), "the headphones' address with colons is in the report")
        assertFalse(text.lowercase().contains(bare), "the headphones' address as bare hex is in the report")
    }
}
