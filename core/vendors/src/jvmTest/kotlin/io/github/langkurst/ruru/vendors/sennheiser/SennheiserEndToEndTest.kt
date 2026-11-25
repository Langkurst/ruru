package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.UnresponsiveLinkException
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.VendorRegistry
import io.github.langkurst.ruru.vendors.gaia.GaiaCommand
import io.github.langkurst.ruru.vendors.gaia.GaiaFrame
import io.github.langkurst.ruru.vendors.gaia.GaiaFrameBuffer
import io.github.langkurst.ruru.vendors.gaia.GaiaVendor
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserCommands as C
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
 * The real Sennheiser stack ([SennheiserConnection] with its GAIA queue and write guard,
 * [SennheiserSession] as the controller drives it, [SennheiserStateMapping]) against a
 * [VirtualSennheiser] that behaves like the Momentum 4 in momentum4-control's captures, ANC side
 * effects included (Earshot's SennheiserEndToEndTest, MIT).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SennheiserEndToEndTest {

    /** What the controller does: one state, pushes folded in, commands run one at a time. */
    private class Rig(val unit: VirtualSennheiser, val transport: VirtualSennheiserTransport, val conn: SennheiserConnection, val session: SennheiserSession) {
        var state = HeadphoneState()
        fun control(id: String) = state.vendorControls.first { it.id == id }
        suspend fun run(command: HeadsetCommand) { state = session.run(command, state) }
        suspend fun control(id: String, value: Int) = run(HeadsetCommand.SetVendorControl(id, value))
        fun sent(): List<Int> = unit.received.filter { it.vendor == C.VENDOR }.map { it.command }
    }

    private suspend fun TestScope.rig(unit: VirtualSennheiser = VirtualSennheiser(), name: String? = "MOMENTUM 4", start: Boolean = true): Rig {
        val transport = VirtualSennheiserTransport(unit)
        val conn = SennheiserConnection(transport, backgroundScope)
        val session = SennheiserSession(conn, name, clock = { 0L })
        val r = Rig(unit, transport, conn, session)
        backgroundScope.launch { session.pushes.collect { p -> session.onPush(p, r.state)?.let { r.state = it } } }
        testScheduler.runCurrent()
        if (start) r.state = session.start(r.state)
        testScheduler.runCurrent()
        return r
    }

    // ---- Connect ------------------------------------------------------------------------------

    @Test fun `connect discovers, identifies, registers and reads a Momentum 4 into HeadphoneState`() = runTest {
        val r = rig()
        val s = r.session.status.value
        assertEquals("M4AEBT Black", s.modelId)
        assertEquals(SennheiserModel.MOMENTUM_4, r.session.model)
        assertEquals("3.37.3", s.firmware)
        assertEquals(VirtualSennheiser.M4_FEATURES, s.features)
        assertEquals(VirtualSennheiser.M4_FEATURES, r.conn.features, "the guard now knows what the headset has")

        val state = r.state
        assertEquals(listOf("Noise cancelling", "Transparency", "Off"), state.modes.map { it.name })
        assertEquals(SennheiserStateMapping.MODE_ANC, state.currentModeIndex)
        assertEquals(NoiseSettings(cancellingLevel = 10, ancEnabled = true), state.noise)
        assertEquals(80, state.batteryPercent)
        assertEquals(false, state.isCharging)
        assertEquals("Sennheiser MOMENTUM 4", state.info?.modelName)
        assertEquals("3.37.3", state.info?.firmwareVersion)
        assertEquals(HeadphoneEq(0, 0, 0), state.eq)
        assertEquals(-6..6, state.eqRange)
        with(state.capabilities) {
            assertFalse(verified)
            assertTrue(noiseCancelling && ancToggle && liveNoiseControl && modesOnHome)
            assertFalse(editableModes || favoriteModes || windBlock || spatialAudio)
            assertEquals(FormFactor.HEADPHONES, formFactor)
            assertEquals(SennheiserStateMapping.SUPPORT_NOTE, supportNote)
        }
        assertEquals(
            listOf(
                "sennheiser_adaptive", "sennheiser_anti_wind", "sennheiser_comfort", "sennheiser_pause_in_transparency", "sennheiser_on_head",
                "sennheiser_smart_pause", "sennheiser_auto_answer", "sennheiser_comfort_call", "sennheiser_sidetone", "sennheiser_auto_off",
                "sennheiser_bass_boost",
            ),
            state.vendorControls.map { it.id },
        )
        assertEquals(listOf("sennheiser_adaptive", "sennheiser_anti_wind"), state.vendorControls.filter { it.onHome }.map { it.id })
        assertEquals(1, r.control("sennheiser_anti_wind").value) // Max
        assertEquals(1, r.control("sennheiser_auto_off").value) // 900 s = 15 min
        assertEquals(2, r.control("sennheiser_sidetone").value) // High
        assertEquals(1, r.control("sennheiser_on_head").value)

        // Pushes registered for battery, ANC, transparency, wear, audio and EQ; never for multipoint or buttons.
        val registered = r.unit.received.filter { it.command == C.REGISTER_NOTIFICATION }.map { it.payload.single().toInt() }
        assertEquals(listOf(3, 13, 12, 2, 4, 8), registered)
        assertEquals(listOf(3), r.session.status.value.wear, "the stray wear response after registering feature 2 was applied")
    }

    @Test fun `the only Qualcomm commands are the version and feature reads`() = runTest {
        val r = rig()
        val qualcomm = r.unit.received.filter { it.vendor == GaiaVendor.QUALCOMM }.map { it.command }.toSet()
        assertEquals(setOf(0x0000, 0x0001), qualcomm)
        assertTrue(r.unit.received.all { it.type == GaiaCommand.TYPE_COMMAND })
    }

    // ---- Modes and noise (the Momentum 4 side effects) ----------------------------------------

    @Test fun `transparency then noise cancelling, with the level side effects read back`() = runTest {
        val r = rig(VirtualSennheiser(level = 30))
        r.run(HeadsetCommand.SetMode(SennheiserStateMapping.MODE_TRANSPARENCY))
        testScheduler.runCurrent()
        assertTrue(r.unit.transparencyOn)
        assertEquals(100, r.unit.level, "transparency on sets the level to 100")
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, r.state.currentModeIndex)
        assertEquals(0, r.state.noise?.cancellingLevel)

        r.unit.received.clear()
        r.run(HeadsetCommand.SetMode(SennheiserStateMapping.MODE_ANC))
        testScheduler.runCurrent()
        // Transparency off first (restores 30), then ANC on (level to 0): both written, then the group re-read.
        assertEquals(listOf(C.TH_SET, C.ANC_SET, C.ANC_GET, C.TH_GET, C.ANC_LEVEL_GET, C.ANC_MODE_GET), r.sent())
        assertEquals(SennheiserStateMapping.MODE_ANC, r.state.currentModeIndex)
        assertEquals(10, r.state.noise?.cancellingLevel)
        assertFalse(r.unit.transparencyOn)
    }

    @Test fun `off, then the slider brings ANC back at the chosen level`() = runTest {
        val r = rig()
        r.run(HeadsetCommand.SetMode(SennheiserStateMapping.MODE_OFF))
        testScheduler.runCurrent()
        assertFalse(r.unit.ancOn)
        assertEquals(SennheiserStateMapping.MODE_OFF, r.state.currentModeIndex)

        r.unit.received.clear()
        r.run(HeadsetCommand.SetNoise(NoiseSettings(cancellingLevel = 7, ancEnabled = true)))
        testScheduler.runCurrent()
        // ANC on resets the level to 0 on the headset, so the level goes after it.
        val writes = r.unit.received.filter { it.command == C.ANC_SET || it.command == C.ANC_LEVEL_SET }
        assertEquals(listOf(C.ANC_SET, C.ANC_LEVEL_SET), writes.map { it.command })
        assertEquals(30, writes[1].payload.single().toInt())
        assertEquals(30, r.unit.level)
        assertEquals(NoiseSettings(cancellingLevel = 7, ancEnabled = true), r.state.noise)
    }

    @Test fun `a mode-table control switches transparency off and the level to 0, and the screen shows it`() = runTest {
        val r = rig(VirtualSennheiser(transparencyOn = true, level = 100))
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, r.state.currentModeIndex)
        r.control("sennheiser_anti_wind", 2)
        testScheduler.runCurrent()
        val modeWrite = r.unit.received.last { it.command == C.ANC_MODE_SET }
        assertEquals(listOf(1, 2), modeWrite.payload.map { it.toInt() }, "a single pair, as the Momentum 4 accepts")
        assertEquals(2, r.control("sennheiser_anti_wind").value) // Auto
        assertEquals(SennheiserStateMapping.MODE_ANC, r.state.currentModeIndex)
        assertEquals(10, r.state.noise?.cancellingLevel)
    }

    // ---- Settings -----------------------------------------------------------------------------

    @Test fun `every setting round-trips through the headset`() = runTest {
        val r = rig()
        val targets = mapOf(
            "adaptive" to 1, "comfort" to 1, "pause_in_transparency" to 1, "on_head" to 0, "smart_pause" to 0,
            "auto_answer" to 1, "comfort_call" to 0, "sidetone" to 1, "auto_off" to 2, "bass_boost" to 1,
        )
        for ((short, value) in targets) {
            val id = "sennheiser_$short"
            r.control(id, value)
            testScheduler.runCurrent()
            assertEquals(value, r.control(id).value, id)
        }
        assertEquals(1800, r.unit.autoOffSeconds, "auto-off pushes nothing; the re-read showed it")
        assertEquals(1, r.unit.sidetone)
        assertTrue(r.unit.bassBoost)
        assertFailsWith<HeadphoneCommandException> { r.control("sennheiser_sidetone", 4) }
        assertFailsWith<HeadphoneCommandException> { r.control("sennheiser_power_off", 1) }
        assertFailsWith<HeadphoneCommandException> { r.control("sennheiser_transparency_level", 1) } // earbuds only
    }

    @Test fun `the three-band EQ writes only the bands of the third that changed`() = runTest {
        val r = rig(VirtualSennheiser(eqGains = mutableListOf(0, 0, 22, 22, 10)))
        val before = assertNotNull(r.state.eq)
        assertEquals(HeadphoneEq(0, 2, 2), before)
        r.unit.received.clear()
        r.run(HeadsetCommand.SetEq(before.copy(bass = 4)))
        testScheduler.runCurrent()
        val writes = r.unit.received.filter { it.command == C.EQ_BAND_SET }.map { it.payload.map { b -> b.toInt() } }
        assertEquals(listOf(listOf(0, 40), listOf(1, 40)), writes)
        assertEquals(listOf(40, 40, 22, 22, 10), r.unit.eqGains)
        assertEquals(HeadphoneEq(4, 2, 2), r.state.eq)
        // Clamped to the headset's +-6 dB.
        r.run(HeadsetCommand.SetEq(HeadphoneEq(4, 2, 9)))
        assertEquals(listOf(40, 40, 22, 60, 60), r.unit.eqGains)
    }

    @Test fun `generation-1 EQ firmware gets no EQ at all`() = runTest {
        val r = rig(VirtualSennheiser(features = VirtualSennheiser.M4_FEATURES + (8 to 2)))
        assertNull(r.state.eq)
        assertTrue(r.unit.received.none { GaiaCommand.feature(it.command) == 8 && it.vendor == C.VENDOR })
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetEq(HeadphoneEq(1, 0, 0))) }
        assertFalse(r.state.vendorControls.any { it.id == "sennheiser_bass_boost" })
    }

    @Test fun `pushes from the headset reach the state`() = runTest {
        val r = rig()
        r.transport.push(GaiaFrame(C.VENDOR, GaiaCommand.notification(C.BATTERY_GET), byteArrayOf(41)))
        r.transport.push(GaiaFrame(C.VENDOR, GaiaCommand.notification(C.TH_GET), byteArrayOf(1)))
        testScheduler.runCurrent()
        assertEquals(41, r.state.batteryPercent)
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, r.state.currentModeIndex)
        // A push that changes nothing gives no new state.
        assertNull(r.session.onPush(GaiaFrame(C.VENDOR, GaiaCommand.notification(C.BATTERY_GET), byteArrayOf(41)), r.state))
    }

    @Test fun `the battery poll re-reads only the battery`() = runTest {
        val r = rig(VirtualSennheiser(battery = 80))
        r.unit.battery = 55
        r.unit.received.clear()
        r.state = r.session.pollBattery(r.state)
        assertEquals(55, r.state.batteryPercent)
        assertEquals(listOf(C.BATTERY_GET), r.sent())
    }

    // ---- What Ruru does not run ----------------------------------------------------------------

    @Test fun `commands a Sennheiser session does not run throw and send nothing`() = runTest {
        val r = rig()
        r.unit.received.clear()
        for (command in SennheiserSafetyTest.unsupportedCommands()) {
            assertFailsWith<HeadphoneCommandException>(command.toString()) { r.run(command) }
        }
        assertTrue(r.unit.received.isEmpty(), "nothing was sent: ${r.unit.received}")
    }

    // ---- What never leaves the PC or phone ----------------------------------------------------

    @Test fun `the block-list never reaches the headset, even with every feature reported`() = runTest {
        val r = rig()
        r.conn.features = (0..30).associateWith { 6 }
        r.unit.received.clear()
        val blocked = listOf(
            C.VENDOR to 0x0040, C.VENDOR to 0x0200, C.VENDOR to 0x0201, C.VENDOR to 0x060F, C.VENDOR to 0x1C00,
            C.VENDOR to 0x1403, C.VENDOR to 0x1405, C.VENDOR to 0x1406, C.VENDOR to 0x2009,
            GaiaVendor.QUALCOMM to 0x0C00, GaiaVendor.QUALCOMM to 0x0C01, GaiaVendor.QUALCOMM to 0x0C02,
            GaiaVendor.QUALCOMM to 0x0803, GaiaVendor.QUALCOMM to 0x0804, GaiaVendor.QUALCOMM to 0x0009, GaiaVendor.QUALCOMM to 0x000A,
        )
        for ((vendor, command) in blocked) {
            assertFailsWith<HeadphoneCommandException> { r.conn.request(vendor, command, byteArrayOf(1)) }
        }
        for (function in listOf(8, 19)) {
            assertFailsWith<HeadphoneCommandException> { r.conn.request(C.VENDOR, 0x1600, byteArrayOf(0, 1, function.toByte())) }
        }
        assertTrue(r.unit.received.isEmpty(), "nothing was written: ${r.unit.received}")
        assertTrue(r.conn.isOpen, "a refusal is not a dead link")
    }

    @Test fun `before the feature list only discovery goes out`() = runTest {
        val r = rig(start = false)
        assertFailsWith<HeadphoneCommandException> { r.conn.call(C.BATTERY_GET) }
        assertFailsWith<HeadphoneCommandException> { r.conn.call(C.ANC_SET, byteArrayOf(1)) }
        assertTrue(r.unit.received.isEmpty())
        r.conn.call(C.SUPPORTED_FEATURES)
        assertEquals(listOf(C.SUPPORTED_FEATURES), r.sent())
    }

    @Test fun `a headset that refuses its feature list is left alone`() = runTest {
        val unit = VirtualSennheiser()
        // A unit whose Sennheiser feature list answers "command not supported".
        val transport = object : ByteTransport {
            private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
            private val frames = GaiaFrameBuffer()
            override val isConnected = true
            override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
            override suspend fun write(bytes: ByteArray) {
                for (f in frames.feed(bytes)) {
                    unit.received += f
                    val reply = if (f.vendor == C.VENDOR && f.command == C.SUPPORTED_FEATURES) {
                        GaiaFrame(f.vendor, GaiaCommand.error(f.command), byteArrayOf(1))
                    } else unit.answer(f).first()
                    inbound.trySend(reply.encode())
                }
            }
            override fun close() { inbound.close() }
        }
        val conn = SennheiserConnection(transport, backgroundScope)
        val session = SennheiserSession(conn, "MOMENTUM 4", clock = { 0L })
        val state = session.start(HeadphoneState())
        assertNull(session.status.value.features)
        assertTrue(unit.received.all { it.command == 0x0000 || it.command == C.SUPPORTED_FEATURES }, "${unit.received}")
        assertEquals(SennheiserStateMapping.NO_FEATURES_NOTE, state.capabilities.supportNote)
        assertTrue(state.modes.isEmpty())
        assertFailsWith<HeadphoneCommandException> { session.run(HeadsetCommand.SetMode(0), state) }
    }

    @Test fun `a silent link fails each request and closes as busy after three`() = runTest {
        val conn = SennheiserConnection(SilentTransport(), backgroundScope)
        repeat(3) { assertFailsWith<HeadphoneCommandException> { conn.request(GaiaVendor.QUALCOMM, 0x0000) } }
        assertFalse(conn.isOpen)
        assertIs<UnresponsiveLinkException>(conn.awaitClosed())
    }

    @Test fun `a socket nobody answers on is refused by the probe, and nothing but the version read is sent`() = runTest {
        val transport = SilentTransport()
        val conn = SennheiserConnection(transport, backgroundScope)
        val session = SennheiserSession(conn, "MOMENTUM 4")
        assertFailsWith<HeadphoneCommandException> { session.start(HeadphoneState()) }
        assertEquals(2, transport.written.size, "the version read, once more with a longer wait")
        assertTrue(transport.written.all { GaiaFrameBuffer().feed(it).single().let { f -> f.vendor == GaiaVendor.QUALCOMM && f.command == 0x0000 } })
    }

    private class SilentTransport : ByteTransport {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val written = mutableListOf<ByteArray>()
        override val isConnected = true
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
        override suspend fun write(bytes: ByteArray) { written += bytes }
        override fun close() { inbound.close() }
    }

    // ---- Other families -----------------------------------------------------------------------

    @Test fun `true wireless earbuds get modes, anti-wind and a transparency level, but no slider`() = runTest {
        val unit = VirtualSennheiser(modelId = "MTW4 Black Copper")
        val r = rig(unit, name = "MOMENTUM TW 4")
        assertEquals(SennheiserModel.MOMENTUM_TW4, r.session.model)
        assertEquals(FormFactor.EARBUDS, r.state.capabilities.formFactor)
        assertFalse(r.state.capabilities.noiseCancelling)
        assertEquals(listOf("sennheiser_anti_wind", "sennheiser_transparency_level"), r.state.vendorControls.filter { it.onHome }.map { it.id })
        assertNull(r.control("sennheiser_anti_wind").options, "a switch on earbuds")
        assertFalse(r.state.vendorControls.any { it.id in setOf("sennheiser_adaptive", "sennheiser_on_head", "sennheiser_comfort_call") })

        r.unit.received.clear()
        r.run(HeadsetCommand.SetMode(SennheiserStateMapping.MODE_TRANSPARENCY))
        // Earbuds: transparency on by itself, no ANC write first.
        assertEquals(C.TH_SET, r.sent().first())
        assertFalse(C.ANC_SET in r.sent())
        r.control("sennheiser_transparency_level", 1)
        assertEquals(1, r.control("sennheiser_transparency_level").value)
    }

    @Test fun `the Accentum gets two switches through its modes and no mode-table control`() = runTest {
        val r = rig(VirtualSennheiser(modelId = "ACAEBT Black"), name = "ACCENTUM")
        assertEquals(SennheiserModel.ACCENTUM, r.session.model)
        assertFalse(r.state.capabilities.noiseCancelling)
        assertFalse(r.state.vendorControls.any { it.id in setOf("sennheiser_anti_wind", "sennheiser_adaptive", "sennheiser_comfort", "sennheiser_smart_pause") })
        assertTrue(r.unit.received.none { it.command == C.ANC_MODE_GET || it.command == C.ANC_MODE_SET }, "its mode table is never touched")
        r.run(HeadsetCommand.SetMode(SennheiserStateMapping.MODE_OFF))
        assertEquals(SennheiserStateMapping.MODE_OFF, r.state.currentModeIndex)
    }

    // ---- Through the vendor, as the controller opens it ----------------------------------------

    @Test fun `Sennheiser is registered, ready, and its demo headset is claimed by its own rule`() {
        assertEquals(DeviceFamily.SENNHEISER, DeviceFamily.forName("MOMENTUM 4"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("MOMENTUM TW 2"), "GAIA v1/v2 stays generic")
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("CX Plus True Wireless"), "BLE-only stays generic")
        assertEquals(DeviceFamily.SENNHEISER, DeviceFamily.fromKey("sennheiser"))
        assertTrue(SennheiserVendor.sessionReady)
        assertSame(SennheiserVendor, VendorRegistry.forFamily(DeviceFamily.SENNHEISER))
        val demo = assertNotNull(SennheiserVendor.virtual())
        assertEquals("Sennheiser MOMENTUM 4", demo.name)
        assertEquals(DeviceFamily.SENNHEISER, DeviceFamily.forName(demo.name))
    }

    @Test fun `a change made on one connection reads back on a new one`() = runTest {
        // The virtual headset keeps its settings across links, as the demo and a real headset do.
        val demo = assertNotNull(SennheiserVendor.virtual())
        var state = HeadphoneState()

        val first = SennheiserVendor.session(demo.open(), backgroundScope)
        assertEquals(DeviceFamily.SENNHEISER, first.family)
        state = first.start(state)
        val before = state.vendorControls.first { it.id == "sennheiser_sidetone" }.value
        assertEquals(2, before)
        state = first.run(HeadsetCommand.SetVendorControl("sennheiser_sidetone", 1), state)
        assertEquals(1, state.vendorControls.first { it.id == "sennheiser_sidetone" }.value)
        first.close()
        assertFalse(first.isOpen)

        // A brand new link and session: the value is read from the headset, not remembered by the app.
        val second = SennheiserVendor.session(demo.open(), backgroundScope)
        val fresh = second.start(HeadphoneState())
        assertEquals(1, fresh.vendorControls.first { it.id == "sennheiser_sidetone" }.value)
        // Put it back, as every write test does.
        val restored = second.run(HeadsetCommand.SetVendorControl("sennheiser_sidetone", 2), fresh)
        assertEquals(2, restored.vendorControls.first { it.id == "sennheiser_sidetone" }.value)
        second.close()
    }

    @Test fun `the Lab lists and scans only reads`() = runTest {
        val r = rig()
        val profile = assertNotNull(r.session.labProfile())
        assertEquals("Sennheiser GAIA v3", profile.title)
        assertTrue(profile.reads.isNotEmpty())
        r.unit.received.clear()
        val lines = r.session.scanLab()
        assertEquals(profile.reads.size, lines.size)
        assertTrue(r.unit.received.isNotEmpty())
        // GETs only: a command on the guard's read list, or Qualcomm's discovery reads.
        for (f in r.unit.received) {
            if (f.vendor == C.VENDOR) assertTrue(f.command in SennheiserWriteGuard.READS, "${GaiaCommand.name(f.vendor, f.command)} is not a read")
            else assertTrue(f.command in setOf(0x0000, 0x0001, 0x0002))
        }
    }
}
