package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.jbl.JblCommands as C
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lists every kind of command a JBL session can put on the wire and checks that none of them is
 * factory reset, power off, firmware transfer or anything else outside the research. A "kind" is
 * command id plus its sub-command byte (the bytes after that are values). The forbidden list below
 * is written out again here on purpose: it does not read the guard's own, so loosening the guard
 * makes this test fail. [the forbidden list really catches what it names] proves the checker
 * itself can fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JblSafetyTest {

    @BeforeTest
    fun quiet() {
        Logging.sink = { _, _, _, _ -> }
    }

    companion object {
        /** One of every [HeadsetCommand] that JBL does not run: the `when` has no else, so a new command fails to compile here. */
        fun unsupportedCommands(): List<HeadsetCommand> {
            val all = listOf(
                HeadsetCommand.SetMode(0), HeadsetCommand.SaveMode(5, "x", NoiseSettings()), HeadsetCommand.RemoveMode(5),
                HeadsetCommand.SetFavorites(setOf(0)), HeadsetCommand.SetNoise(NoiseSettings()), HeadsetCommand.SetEq(HeadphoneEq(0, 0, 0)),
                HeadsetCommand.SetSidetone(Sidetone.LOW), HeadsetCommand.SetAutoPlayPause(true), HeadsetCommand.SetAutoAnswer(true),
                HeadsetCommand.SetMultipoint(true), HeadsetCommand.SetAutoOff(5), HeadsetCommand.SetVoicePrompts(true),
                HeadsetCommand.SetShortcut(ButtonAction.BATTERY_LEVEL), HeadsetCommand.SetTouchControls(true),
                HeadsetCommand.SetDeviceName("x"), HeadsetCommand.SetLeAudio(true), HeadsetCommand.SetLatency(100),
                HeadsetCommand.SetVolume(5), HeadsetCommand.Transport(TransportControl.PAUSE),
                HeadsetCommand.SwitchDevice("00:00:00:00:00:00"), HeadsetCommand.SetVendorControl("x", 0), HeadsetCommand.PowerOff,
            )
            return all.filter { runsOnJbl(it) == false }
        }

        /** True where [JblSession.run] sends something, false where it throws. Exhaustive on purpose. */
        private fun runsOnJbl(c: HeadsetCommand): Boolean = when (c) {
            is HeadsetCommand.SetMode, is HeadsetCommand.SetVendorControl -> true
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
            is HeadsetCommand.SetNoise, is HeadsetCommand.SetEq, is HeadsetCommand.SetSidetone,
            is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer, is HeadsetCommand.SetMultipoint,
            is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts, is HeadsetCommand.SetShortcut,
            is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName, is HeadsetCommand.SetLeAudio,
            is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume, is HeadsetCommand.Transport,
            is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> false
        }

        /** `91/11` style, for readable failures: command id and first payload byte, in hex. */
        fun kind(f: JblFrame): String = "%02x/%s".format(f.command, f.sub?.let { "%02x".format(it) } ?: "--")
    }

    /** Commands that must never leave, by command id, written out again (JBL notes §4; jbl-control blocks 0x95 too). */
    private val forbidden = mapOf(
        0x95 to "factory reset", 0x97 to "power off", 0xF0 to "firmware transfer", 0xA4 to "case OTA launch",
        0xA3 to "screensaver delete", 0x15 to "clear analytics", 0x74 to "factory ANC tuning",
        0x61 to "sync app status (purpose not traced)", 0xB0 to "LE Audio / Auracast (purpose not traced)",
    )

    /** The only command ids a JBL session may use at all. */
    private val knownCommands = setOf(
        C.NOTIFY_ON, C.DEVICE_INFO_GET, C.LISTENING, C.INFO, C.EQ, C.VOICE_PROMPTS, C.BATTERY, C.HEARTBEAT, C.SERIAL, C.VOICE_AWARE, C.FIND, C.SAV,
    )

    private fun isForbidden(f: JblFrame): String? = when {
        f.command in forbidden -> forbidden.getValue(f.command)
        f.command !in knownCommands -> "a command id outside the research"
        else -> null
    }

    /**
     * Drives every command, every control value, every mode, the Lab and the battery poll on a
     * headset of [unit]'s kind, so the headset sees every kind the session can send.
     */
    private suspend fun TestScope.kindsSent(unit: VirtualJbl): List<JblFrame> {
        val conn = JblConnection(VirtualJblTransport(unit), backgroundScope)
        val session = JblSession(conn, unit.name, clock = { 0L })
        var state: HeadphoneState = session.start(HeadphoneState())
        // A headset that lacks a control makes some commands fail: that is fine, only what is sent matters.
        suspend fun tryRun(c: HeadsetCommand) { try { state = session.run(c, state) } catch (_: HeadphoneCommandException) { } }
        session.labProfile()
        session.scanLab()
        for (i in 0..4) tryRun(HeadsetCommand.SetMode(i))
        for (control in state.vendorControls) {
            val values = when {
                control.options != null -> control.options!!.indices.toList()
                control.range != null -> (control.range!!.first - 1)..(control.range!!.last + 1)
                else -> listOf(0, 1)
            }
            for (v in values) tryRun(HeadsetCommand.SetVendorControl(control.id, v))
        }
        // A control the headset never drew must not send anything either.
        tryRun(HeadsetCommand.SetVendorControl("jbl_power_off", 1))
        for (command in unsupportedCommands()) tryRun(command)
        session.pollBattery(state)
        // The headset's own heartbeat is echoed once the device info is known.
        unit.pushHeartbeat()
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        conn.close()
        return unit.received.toList()
    }

    /** One virtual headset per layout: the captured earbuds, ones that answer the level, headphones, one that declines modes. */
    private val headsets = listOf(
        "Wave Buds 2" to { VirtualJbl() },
        "Wave Buds 2 with a level" to { VirtualJbl(ancLevel = 4) },
        "Live 660NC" to { VirtualJbl(name = "JBL Live 660NC", productId = 0x2033, ancLevel = 1) },
        "Tour One M2, one bud out" to { VirtualJbl(name = "JBL Tour One M2", productId = 0x2085, bothBudsIn = false, mode = null) },
    )

    @Test fun `every outgoing JBL command kind is on the allow-list and none is reset, power off or firmware`() = runTest {
        val kinds = sortedSetOf<String>()
        for ((name, make) in headsets) {
            val sent = kindsSent(make())
            assertTrue(sent.isNotEmpty(), name)
            for (f in sent) {
                assertEquals(null, isForbidden(f), "$name: ${kind(f)} is ${isForbidden(f)}")
                assertTrue(JblWriteGuard.isAllowed(f), "$name: ${kind(f)} is not on the allow-list")
                kinds += kind(f)
            }
        }
        println("JBL command kinds sent: $kinds")
        assertEquals(EXPECTED_KINDS, kinds, "the list of command kinds the JBL session sends changed: review each new one against the guard")
    }

    @Test fun `nothing a JBL headset announces can make the session send a forbidden kind`() = runTest {
        // Headsets that claim every mode, every battery id and a level, whatever model they are.
        val all = listOf(
            VirtualJbl(name = "JBL Wave Buds 2", ancLevel = 7, mode = C.WIRE_TALKTHRU),
            VirtualJbl(name = "JBL Tour One", productId = 0x2021, ancLevel = 7),
        )
        for (unit in all) for (f in kindsSent(unit)) assertEquals(null, isForbidden(f), kind(f))
    }

    @Test fun `the forbidden list really catches what it names`() {
        // The checker above is only worth something if it fails on the dangerous commands, and the guard must agree.
        fun f(command: Int, vararg p: Int) = JblFrame(command, ByteArray(p.size) { p[it].toByte() })
        val dangerous = listOf(
            f(0x95), f(0x95, 0x01), // factory reset
            f(0x97), f(0x97, 0x01), // power off
            f(0xF0, 0x00), f(0xF0, 0x01, 0x02), // firmware transfer
            f(0xA4, 0x01), f(0xA3, 0x01), // case OTA launch, screensaver delete
            f(0x15, 0x01), f(0x74, 0x01), // clear analytics, factory ANC tuning
            f(0x61, 0x01), f(0xB0, 0x01), // not traced
            f(0x34, 0x01), f(0x77, 0x00, 0x03, 0x08), // voice assistant, gestures: not in the research
        )
        for (frame in dangerous) {
            assertNotNull(isForbidden(frame), kind(frame))
            assertFalse(JblWriteGuard.isAllowed(frame), "the guard refuses ${kind(frame)}")
        }
        // And the connection, the one write path, never lets one reach the link.
        runTest {
            val unit = VirtualJbl()
            val conn = JblConnection(VirtualJblTransport(unit), backgroundScope)
            for (frame in dangerous) assertFailsWith<HeadphoneCommandException>(kind(frame)) { conn.send(frame) }
            assertTrue(unit.received.isEmpty(), "none of them left")
            conn.close()
        }
    }

    @Test fun `the checker flags a recorded list that holds a dangerous frame`() {
        // The shape of the first test, run on a list with one bad frame in it: it must come out with a finding.
        val recorded = listOf(C.modeGet(), C.eqGet(), JblFrame(0x95, byteArrayOf(0x01)), C.voicePromptsGet())
        val findings = recorded.mapNotNull { isForbidden(it) }
        assertEquals(listOf("factory reset"), findings)
        assertNull(recorded.filter { it.command != 0x95 }.firstNotNullOfOrNull { isForbidden(it) })
    }

    @Test fun `the expected kinds are reads and writes of settings, each one named`() {
        // Reviewed by hand: pushes on, the device-info ask and the reads; three settings are written.
        assertEquals(EXPECTED_KINDS, (READS + WRITES).toSortedSet())
        assertTrue(READS.intersect(WRITES.toSet()).isEmpty())
        // The writes: the listening mode, its level and the EQ preset (and voice prompts).
        assertEquals(setOf("91/10", "91/20", "a2/03", "93/03"), WRITES.toSet())
        assertTrue(EXPECTED_KINDS.all { it.substringBefore('/').toInt(16) in knownCommands })
    }

    private val READS = listOf(
        "9b/01", "11/--", "91/11", "91/21", "06/01", "a2/01", "93/04", "25/00", "b1/01", "94/01", "98/01", "23/01", "80/01",
    )

    private val WRITES = listOf("91/10", "91/20", "a2/03", "93/03")

    private val EXPECTED_KINDS = sortedSetOf(
        "06/01", "11/--", "23/01", "25/00", "80/01", "91/10", "91/11", "91/20", "91/21", "93/03", "93/04", "94/01", "98/01", "9b/01", "a2/01", "a2/03", "b1/01",
    )
}
