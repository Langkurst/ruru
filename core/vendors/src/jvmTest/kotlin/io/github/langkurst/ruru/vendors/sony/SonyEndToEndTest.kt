package io.github.langkurst.ruru.vendors.sony

import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.VendorRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Tandem link ([SonyConnection]), [SonySession] as the controller drives it (start, run, pushes)
 * and [SonyStateMapping] against a [VirtualSony] WH-1000XM5 (Earshot's SonyEndToEndTest, MIT).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SonyEndToEndTest {

    /** What the controller does: one state, pushes folded in, commands run one at a time. */
    private class Rig(val unit: VirtualSony, val conn: SonyConnection, val session: SonySession) {
        var state = HeadphoneState()
        fun control(id: String) = state.vendorControls.first { it.id == id }
        suspend fun run(command: HeadsetCommand) { state = session.run(command, state) }
        suspend fun control(id: String, value: Int) = run(HeadsetCommand.SetVendorControl(id, value))
    }

    private suspend fun TestScope.rig(unit: VirtualSony = VirtualSony(), start: Boolean = true): Rig {
        val conn = SonyConnection(VirtualSonyTransport(unit), backgroundScope)
        val session = SonySession(conn, backgroundScope, clock = { testScheduler.currentTime })
        val r = Rig(unit, conn, session)
        backgroundScope.launch { session.pushes.collect { m -> session.onPush(m, r.state)?.let { r.state = it } } }
        if (start) r.state = session.start(r.state)
        return r
    }

    @Test fun `Sony is registered, claims v2 names by name and leaves the WH-1000XM4 generic`() {
        assertEquals(DeviceFamily.SONY, DeviceFamily.forName("WH-1000XM5"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("WH-1000XM4"), "MDR v1 stays generic")
        assertEquals(DeviceFamily.SONY, DeviceFamily.fromKey("sony"))
        assertTrue(SonyVendor.sessionReady)
        assertSame(SonyVendor, VendorRegistry.forFamily(DeviceFamily.SONY))
        assertEquals("Sony WH-1000XM5", SonyVendor.virtual()!!.name)
        assertEquals(DeviceFamily.SONY, DeviceFamily.forName(SonyVendor.virtual()!!.name))
    }

    @Test fun `an MDR v1 headset is refused with the explained message and nothing else is sent`() = runTest {
        val unit = VirtualSony(mdrV1 = true)
        val r = rig(unit, start = false)
        val e = assertFailsWith<ExplainedConnectException> { r.session.start(HeadphoneState()) }
        assertEquals(Words("sony_v1_only"), e.words)
        assertFalse(e.retry)
        assertEquals(listOf(0x00), unit.received.map { it.command }, "only the protocol-info read was sent")
        r.conn.close()
    }

    @Test fun `a silent socket is reported as busy-style failure after the short probe`() = runTest {
        val unit = VirtualSony().apply { silent = true }
        val r = rig(unit, start = false)
        assertFailsWith<HeadphoneCommandException> { r.session.start(HeadphoneState()) }
        assertFalse(r.conn.isOpen)
    }

    @Test fun `connect reads the whole headset into HeadphoneState`() = runTest {
        val r = rig()
        val s = r.state
        assertEquals("Sony WH-1000XM5", s.info?.productName)
        assertEquals("1.1.3", s.info?.firmwareVersion)
        assertEquals("1000X", s.info?.family)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertFalse(s.capabilities.verified)
        assertTrue(s.capabilities.supportNote!!.startsWith("Experimental"))
        assertEquals(70, s.batteryPercent)
        assertEquals(false, s.isCharging)
        assertEquals(70, s.battery?.overall?.percent)
        // Noise is a mode: slot 0 NC, 1 ambient, 2 off; no level slider of its own.
        assertEquals(listOf(0 to "Noise cancelling", 1 to "Ambient sound", 2 to "Off"), s.modes.map { it.index to it.name })
        assertEquals(0, s.currentModeIndex)
        assertTrue(s.capabilities.listeningModes && s.capabilities.modesOnHome)
        assertFalse(s.capabilities.noiseCancelling)
        assertEquals(true, s.noise?.ancEnabled)

        assertEquals(
            listOf(
                "sony_ambient_level", "sony_focus_on_voice", "sony_eq_preset",
                "sony_eq_band_0", "sony_eq_band_1", "sony_eq_band_2", "sony_eq_band_3", "sony_eq_band_4", "sony_eq_band_5",
                "sony_auto_power_off", "sony_speak_to_chat", "sony_pause_when_taken_off", "sony_dsee", "sony_connection_priority",
                "sony_voice_guidance", "sony_general_d1", "sony_general_d2",
            ),
            s.vendorControls.map { it.id },
        )
        assertEquals(listOf("sony_ambient_level", "sony_focus_on_voice"), s.vendorControls.filter { it.onHome }.map { it.id })
        val level = r.control("sony_ambient_level")
        assertEquals(1..20, level.range, "the range comes from the capability reply")
        assertEquals(15, level.value)
        assertEquals(12, r.control("sony_eq_preset").options?.size)
        assertEquals("Off", r.control("sony_eq_preset").let { it.options!![it.value!!] })
        assertEquals("Clear Bass", r.control("sony_eq_band_0").title)
        assertEquals(-10..10, r.control("sony_eq_band_0").range)
        assertEquals("dB", r.control("sony_eq_band_0").unit)
        assertEquals(listOf("5 min", "30 min", "1 hour", "3 hours", "When taken off", "Off"), r.control("sony_auto_power_off").options)
        assertEquals("When taken off", r.control("sony_auto_power_off").let { it.options!![it.value!!] })
        assertEquals("DSEE Extreme", r.control("sony_dsee").title)
        assertEquals("Connect to 2 devices at once", r.control("sony_general_d1").title)
        assertEquals("Touch controls", r.control("sony_general_d2").title)
        assertEquals(1, r.control("sony_voice_guidance").value)

        // Init: no usage logging, no update, no reset, no test, nothing off the allow-list; prompts switched on.
        assertTrue(r.unit.received.none { it.table == SonyTable.T1 && it.command in setOf(0xC4, 0x0F, 0xF4) + (0x30..0x3F) })
        assertTrue(r.unit.received.all { SonyWriteGuard.isAllowed(it) })
        assertTrue(r.unit.alertsEnabled)
        r.conn.close()
    }

    @Test fun `connect, read state, change one control, read back on a new connection`() = runTest {
        // The virtual headset the demo uses: one unit behind several connections, as a reconnect does.
        val headset = SonyVendor.virtual()!!
        val first = SonyVendor.session(headset.open(), backgroundScope)
        var state = first.start(HeadphoneState())
        assertEquals(15, state.vendorControls.first { it.id == "sony_ambient_level" }.value)
        state = first.run(HeadsetCommand.SetVendorControl("sony_ambient_level", 6), state)
        assertEquals(6, state.vendorControls.first { it.id == "sony_ambient_level" }.value, "the change is read back, not assumed")
        first.close()

        val second = SonyVendor.session(headset.open(), backgroundScope)
        val again = second.start(HeadphoneState())
        assertEquals(6, again.vendorControls.first { it.id == "sony_ambient_level" }.value, "a new connection sees what the headset kept")
        second.run(HeadsetCommand.SetVendorControl("sony_ambient_level", 15), again) // put it back
        second.close()
    }

    @Test fun `the three modes reach the headset and come back as the current slot`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetMode(1))
        assertEquals(1, r.unit.ncMode)
        assertTrue(r.unit.ncEffect)
        assertEquals(1, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetMode(2))
        assertFalse(r.unit.ncEffect)
        assertEquals(2, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetMode(0))
        assertEquals(0, r.unit.ncMode)
        assertEquals(0, r.state.currentModeIndex)
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(5)) }
        // Automations speak NoiseSettings: ANC off is the Off mode, on again is noise cancelling.
        r.run(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = false)))
        assertEquals(2, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = true)))
        assertEquals(0, r.state.currentModeIndex)
        r.conn.close()
    }

    @Test fun `vendor controls round-trip through the headset`() = runTest {
        val r = rig()
        r.control("sony_ambient_level", 5)
        assertEquals(5, r.unit.ambientLevel)
        assertEquals(5, r.control("sony_ambient_level").value)
        r.control("sony_focus_on_voice", 1)
        assertTrue(r.unit.focusOnVoice)

        // A preset: the headset applies its own bands.
        val bass = r.control("sony_eq_preset").options!!.indexOf("Bass boost")
        r.control("sony_eq_preset", bass)
        assertEquals(0x16, r.unit.eqPreset)
        assertEquals(6, r.control("sony_eq_band_0").value) // Clear Bass +6 in the Bass boost preset
        // Editing a band moves to the Manual slot and keeps the others.
        r.control("sony_eq_band_5", -4)
        assertEquals(0xA0, r.unit.eqPreset)
        assertEquals(listOf(16, 14, 11, 10, 10, 6), r.unit.eqBands)
        assertEquals("Manual", r.control("sony_eq_preset").let { it.options!![it.value!!] })

        r.control("sony_auto_power_off", 1)
        assertEquals(0x01, r.unit.autoOff)
        assertEquals("30 min", r.control("sony_auto_power_off").let { it.options!![it.value!!] })
        r.control("sony_auto_power_off", 5)
        assertEquals(0x11, r.unit.autoOff)
        assertEquals(0x01, r.unit.autoOffLast, "switching off remembers the last timer")

        r.control("sony_speak_to_chat", 1)
        assertTrue(r.unit.speakToChat)
        r.control("sony_pause_when_taken_off", 0)
        assertFalse(r.unit.pauseWhenTakenOff)
        r.control("sony_dsee", 0)
        assertFalse(r.unit.dsee)
        r.control("sony_connection_priority", 1)
        assertEquals(1, r.unit.connectionMode)
        r.control("sony_voice_guidance", 0)
        assertFalse(r.unit.voiceGuidance)
        assertEquals(0, r.control("sony_voice_guidance").value)
        r.control("sony_general_d2", 0)
        assertFalse(r.unit.touchPanel)
        assertEquals(0, r.control("sony_general_d2").value)
        assertFailsWith<HeadphoneCommandException> { r.control("factory_reset", 1) }
        r.conn.close()
    }

    @Test fun `a confirmation prompt after the user's change is accepted`() = runTest {
        val r = rig()
        r.control("sony_general_d1", 0)
        testScheduler.runCurrent()
        assertFalse(r.unit.multipoint)
        assertEquals(listOf(0x07 to true), r.unit.alertAnswers)
        r.conn.close()
    }

    @Test fun `a prompt the user didn't cause is declined`() = runTest {
        val r = rig()
        testScheduler.advanceTimeBy(SonySession.ALERT_WINDOW_MS + 1_000)
        r.unit.push(SonyTable.T1, byteArrayOf(0x99.toByte(), 0x00, 0x07, 0x01))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_000)
        assertEquals(listOf(0x07 to false), r.unit.alertAnswers)
        r.conn.close()
    }

    @Test fun `the headset's own button and battery pushes update the state`() = runTest {
        val r = rig()
        // NC/AMB button: ambient, level 8.
        r.unit.push(SonyTable.T1, byteArrayOf(0x69, 0x17, 0x01, 0x01, 0x01, 0x00, 0x08))
        r.unit.push(SonyTable.T1, byteArrayOf(0x25, 0x00, 0x41, 0x01))
        testScheduler.runCurrent()
        val s = r.state
        assertEquals(1, s.currentModeIndex)
        assertEquals(8, r.control("sony_ambient_level").value)
        assertEquals(65, s.batteryPercent)
        assertEquals(true, s.isCharging)
        r.conn.close()
    }

    @Test fun `a battery poll re-reads the battery and nothing else`() = runTest {
        val r = rig()
        r.unit.batteryPercent = 40
        val before = r.unit.received.size
        r.state = r.session.pollBattery(r.state)
        assertEquals(40, r.state.batteryPercent)
        assertEquals(listOf(0x22), r.unit.received.drop(before).map { it.command })
        r.conn.close()
    }

    @Test fun `a lost ACK during a write is resent and the setting is applied once`() = runTest {
        val r = rig()
        val before = r.unit.received.size
        r.unit.dropAcks = 1
        r.control("sony_ambient_level", 3)
        assertEquals(3, r.unit.ambientLevel)
        assertEquals(3, r.control("sony_ambient_level").value)
        val writes = r.unit.received.drop(before).filter { it.command == 0x68 }
        assertEquals(1, writes.size, "the resent SET was ACKed again but not applied twice")
        r.conn.close()
    }

    @Test fun `power off sends the user power-off and the link drops`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.PowerOff)
        testScheduler.runCurrent()
        assertTrue(r.unit.poweredOff)
        assertEquals(listOf(0x24, 0x03, 0x01), r.unit.received.last().payload.map { it.toInt() and 0xFF })
        assertNull(r.conn.awaitClosed())
        assertFalse(r.conn.isOpen)
        assertFalse(r.session.isOpen)
    }

    @Test fun `an unknown protocol version is read-only`() = runTest {
        val r = rig(VirtualSony(protocolVersion = 0x04000001))
        assertEquals(SonyStateMapping.READ_ONLY_NOTE, r.state.capabilities.supportNote)
        assertEquals(15, r.control("sony_ambient_level").value, "reads still work")
        val before = r.unit.received.size
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(1)) }
        assertFailsWith<HeadphoneCommandException> { r.control("sony_dsee", 0) }
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.PowerOff) }
        assertEquals(before, r.unit.received.size, "nothing was written")
        assertFalse(r.unit.alertsEnabled)
        r.conn.close()
    }

    @Test fun `commands Sony has no equivalent for throw and send nothing`() = runTest {
        val r = rig()
        val before = r.unit.received.size
        for (c in SonySafetyTest.unsupportedCommands()) {
            val e = assertFailsWith<HeadphoneCommandException>(c.toString()) { r.run(c) }
            assertEquals(SonySession.UNSUPPORTED, e.message)
        }
        assertEquals(before, r.unit.received.size)
        r.conn.close()
    }

    @Test fun `the Lab lists and scans only GET reads`() = runTest {
        val r = rig()
        val profile = assertNotNull(r.session.labProfile())
        assertEquals("Sony MDR v2", profile.title)
        assertTrue(profile.reads.size > 10)
        val before = r.unit.received.size
        val lines = r.session.scanLab()
        assertEquals(profile.reads.size, lines.size)
        assertTrue(lines.none { it.contains("refused") })
        val sent = r.unit.received.drop(before)
        assertTrue(sent.all { SonyReads.isRead(it) }, "every Lab frame is a read")
        assertTrue(lines.first { it.contains("Capability info") }.endsWith("bytes"), "the headset's own address is not printed")
        assertIs<SonySession>(r.session)
        r.conn.close()
    }
}
