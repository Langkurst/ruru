package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every frame the app's controller writes to Bose headphones during one scripted session, against
 * the simulated 1st Gen, compared with a list recorded from Ruru 0.2.1's controller before the
 * vendor layer (0.3, milestone A2) was put under it. The list is the proof that moving the
 * controller onto `HeadsetSession` changed nothing on the wire: the same frames in the same order.
 *
 * To record the list again (only when a change to the Bose frames is intended): delete
 * `bose-session-frames.txt` from the test resources and run this test once; it writes the file
 * under `build/` and fails, and the file then goes into the resources by hand.
 */
class BoseFramesGoldenTest {
    private class RecordingPlatform(private val device: VirtualQcUltra2) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        override val routes = setOf(RouteKind.BOSE_RFCOMM)
        override val checksIdentity = true
        val transports = ArrayList<VirtualTransport>()
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
        override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
            device.powerOn()
            return VirtualTransport(device, random = Random(7)).also { transports += it }
        }
        override suspend fun confirm(candidate: HeadsetCandidate, address: String?): Words? = null
    }

    @Test
    fun theControllerWritesTheSameFramesAsBeforeTheVendorLayer() = runBlocking {
        Logging.sink = { _, _, _, _ -> }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val platform = RecordingPlatform(VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1))
            val c = HeadsetController(platform, scope).also { it.start() }
            val start = c.awaitState { it.phase == Phase.Connected && it.headset.eq != null && !it.headset.isBusy }.headset
            settle()

            val originalMode = start.currentModeIndex ?: fail("no current mode")
            val other = start.modes.first { it.isConfigured && it.index != originalMode }.index
            val eq = start.eq!!
            val nudged = eq.copy(bass = if (eq.bass < 10) eq.bass + 1 else eq.bass - 1)

            c.step { setMode(other) }
            c.step { setEq(nudged) }
            c.step { setNoise(NoiseSettings(cancellingLevel = 6)) }
            c.step { setSidetone(Sidetone.LOW) }
            c.step { setMultipoint(start.multipointEnabled != true) }
            c.step { setAutoPlayPause(start.autoPlayPause != true) }
            c.step { setAutoAnswer(start.autoAnswer != true) }
            c.step { setAutoOff(20) }
            c.step { setShortcut(ButtonAction.BATTERY_LEVEL) }
            c.step { setVoicePrompts(start.voicePrompts?.enabled != true) }
            c.step { setMode(originalMode) }
            c.step { setEq(eq) }
            c.stop()

            assertEquals(1, platform.transports.size, "the script ran on one connection")
            val frames = platform.transports.single().sentRequests().map { p -> p.encode().joinToString("") { "%02x".format(it) } }
            val golden = javaClass.getResource("/frames/bose-session-frames.txt")?.readText()
            if (golden == null) {
                val out = File("build/bose-session-frames.txt")
                out.writeText(frames.joinToString("\n", postfix = "\n"))
                fail("no recorded frames yet; wrote ${frames.size} to ${out.absolutePath}")
            }
            val expected = golden.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            assertTrue(expected.size > 20, "the recorded list looks too short: ${expected.size}")
            assertEquals(expected.joinToString("\n"), frames.joinToString("\n"))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun HeadsetController.awaitState(predicate: (AppState) -> Boolean): AppState =
        withTimeout(15_000) { state.first(predicate) }

    /** One command, then wait until it is done and any push it caused has arrived. */
    private suspend fun HeadsetController.step(command: HeadsetController.() -> Unit) {
        command()
        delay(150)
        awaitState { !it.headset.isBusy }
        settle()
    }

    // Longer than the simulated headset's 250 ms tick, which delivers the pushes a change causes.
    private suspend fun settle() = delay(700)
}
