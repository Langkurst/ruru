package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.SupportTier
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.VendorRegistry
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SoundcoreSession] as the controller drives it (start, run, pushes, poll) over the real
 * connection, framing, state reader, write builders and mappings, against a [VirtualSoundcore]
 * (Earshot's SoundcoreEndToEndTest, SoundcoreModelsEndToEndTest and SoundcoreRepositoryTest, MIT).
 * The session is handed a bare byte pipe, as the controller hands it one, so it has to learn the
 * model from the state, which is also what the second half of these tests prove.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreEndToEndTest {

    /** What the controller does: one state, pushes folded in, commands run one at a time. */
    private class Rig(val unit: VirtualSoundcore, val conn: SoundcoreConnection, val session: SoundcoreSession, val transport: AnonymousVirtualSoundcoreTransport) {
        var state = HeadphoneState()
        fun control(id: String) = state.vendorControls.first { it.id == id }
        suspend fun run(command: HeadsetCommand) { state = session.run(command, state) }
        suspend fun control(id: String, value: Int) = run(HeadsetCommand.SetVendorControl(id, value))
    }

    private suspend fun TestScope.rig(unit: VirtualSoundcore, start: Boolean = true): Rig {
        val transport = AnonymousVirtualSoundcoreTransport(unit)
        val conn = SoundcoreConnection(transport, backgroundScope, minWriteGapMs = 0)
        val session = SoundcoreSession(conn, backgroundScope, clock = { testScheduler.currentTime })
        val r = Rig(unit, conn, session, transport)
        backgroundScope.launch { session.pushes.collect { p -> session.onPush(p, r.state)?.let { r.state = it } } }
        if (start) r.state = session.start(r.state)
        return r
    }

    // ---- Routing ------------------------------------------------------------------------------

    @Test fun `Soundcore is registered with a session, a demo Sleep A30 and the UUID route`() {
        assertTrue(SoundcoreVendor.sessionReady)
        assertSame(SoundcoreVendor, VendorRegistry.forFamily(DeviceFamily.SOUNDCORE))
        assertEquals("Soundcore Sleep A30", SoundcoreVendor.virtual()!!.name)
        assertEquals(DeviceFamily.SOUNDCORE, DeviceFamily.forName(SoundcoreVendor.virtual()!!.name))
        // Every model with a layout has a demo under a name that detection claims.
        for (model in SoundcoreModel.entries.filter { it.layout != null }) {
            assertEquals(DeviceFamily.SOUNDCORE, DeviceFamily.forName(SoundcoreVendor.virtual(model).name), model.code)
        }
        assertEquals(DeviceFamily.JBL, DeviceFamily.forName("JBL Tour Pro 2"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("soundcore Boom 2"))
    }

    // ---- Sleep A30 -----------------------------------------------------------------------------

    @Test fun `Sleep A30 - the state is read into two modes, per-bud battery and firmware`() = runTest {
        val r = rig(VirtualSoundcore(leftBattery = 7, rightBattery = 5, ancOn = true))
        val s = r.state
        assertEquals(SoundcoreModel.SLEEP_A30, r.session.model)
        assertEquals(listOf(0 to "Noise cancelling", 1 to "Off"), s.modes.map { it.index to it.name })
        assertEquals(listOf(ModeMeaning.QUIET, ModeMeaning.OFF), s.modes.map { it.meaning })
        assertEquals(SoundcoreStateMapping.MODE_ANC, s.currentModeIndex)
        assertEquals(60, s.batteryPercent) // the lower bud: 5 -> 60 %
        assertEquals(80, s.battery?.left?.percent)
        assertEquals("01.91", s.info?.firmwareVersion)
        assertEquals("Soundcore Sleep A30", s.info?.productName)
        assertEquals(true, s.touchControlsEnabled)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        // Ruru has not run on these earbuds: Experimental, like every maker but the QC Ultra Headphones (1st Gen).
        assertFalse(s.capabilities.verified || s.capabilities.tested)
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(s.capabilities))
        assertEquals(SoundcoreStateMapping.SUPPORT_NOTE, s.capabilities.supportNote)
        // Home shows the switch, not the two stand-in modes; the modes still drive the tile and the automations.
        assertFalse(s.capabilities.modesOnHome)
        assertTrue(s.capabilities.listeningModes)
        assertEquals(listOf("soundcore_anc", "soundcore_listening_mode"), s.vendorControls.filter { it.onHome }.map { it.id })
        assertEquals(10, s.vendorControls.size)
        assertTrue(s.vendorControls.all { it.id.startsWith("soundcore_") })
        assertTrue(r.conn.writesUnlocked)
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), r.unit.received.map { it.command })
        r.conn.close()
    }

    @Test fun `Sleep A30 - noise cancelling off and on, tap controls, refresh`() = runTest {
        val r = rig(VirtualSoundcore(ancOn = true))
        r.run(HeadsetCommand.SetMode(SoundcoreStateMapping.MODE_OFF))
        assertFalse(r.unit.ancOn)
        assertEquals(SoundcoreStateMapping.MODE_OFF, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = true)))
        assertTrue(r.unit.ancOn)
        assertEquals(SoundcoreStateMapping.MODE_ANC, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetTouchControls(false))
        assertTrue(r.unit.tapControlsDisabled)
        assertEquals(false, r.state.touchControlsEnabled)
        r.run(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = false)))
        assertFalse(r.unit.ancOn)
        assertEquals(
            "The Sleep A30 has two modes: noise cancelling and off",
            assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(2)) }.message,
        )
        r.unit.leftBattery = 3
        r.state = r.session.pollBattery(r.state)
        assertEquals(40, r.state.batteryPercent)
        r.conn.close()
    }

    @Test fun `Sleep A30 - every control round-trips through the earbuds' state`() = runTest {
        val r = rig(VirtualSoundcore())
        for (control in r.state.vendorControls.toList()) {
            val target = if (control.value == 1) 0 else 1
            r.control(control.id, target)
            assertEquals(target, r.control(control.id).value, control.id)
        }
        assertFailsWith<HeadphoneCommandException> { r.control("soundcore_power_off", 1) }
        r.conn.close()
    }

    @Test fun `Sleep A30 - pushes apply by themselves`() = runTest {
        val r = rig(VirtualSoundcore())
        r.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(2, 3), inbound = true))
        testScheduler.runCurrent()
        assertEquals(30, r.state.batteryPercent)
        assertEquals(30 to 40, r.state.battery?.left?.percent to r.state.battery?.right?.percent)
        // A push of a kind nobody handles changes nothing.
        r.transport.push(SoundcorePacket(0x0F01, byteArrayOf(1), inbound = true))
        testScheduler.runCurrent()
        assertEquals(30, r.state.batteryPercent)
        r.conn.close()
    }

    @Test fun `Sleep A30 - unpair, power off, find and unknown writes never leave the phone`() = runTest {
        val r = rig(VirtualSoundcore())
        for (command in listOf(0x0B83, 0x0189, 0x1089, 0x0681)) {
            assertFailsWith<HeadphoneCommandException> { r.conn.request(command, byteArrayOf(1)) }
        }
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.PowerOff) }
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), r.unit.received.map { it.command }, "only the start-up read was sent")
        r.conn.close()
    }

    @Test fun `silent earbuds - the read fails after the retries and the session reports it`() = runTest {
        val silent = object : ByteTransport {
            private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
            override var isConnected = true
            override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
            override suspend fun write(bytes: ByteArray) {}
            override fun close() { isConnected = false; inbound.close() }
        }
        val conn = SoundcoreConnection(silent, backgroundScope, minWriteGapMs = 0)
        val session = SoundcoreSession(conn, backgroundScope)
        assertFailsWith<HeadphoneCommandException> { session.start(HeadphoneState()) }
        assertTrue(conn.isOpen, "Soundcore never closes a silent link itself")
        conn.close()
    }

    // ---- Newer models: Liberty 4 NC --------------------------------------------------------------

    @Test fun `Liberty 4 NC - the model is learned from the state, then three modes, batteries and controls`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC, leftBattery = 3, rightBattery = 5, caseBattery = 4)
        val r = rig(unit)
        assertEquals(SoundcoreModel.LIBERTY_4_NC, r.session.model, "the bare pipe named no model; the serial did")
        assertTrue(r.conn.writesUnlocked)
        val s = r.state
        assertEquals(listOf("Noise cancelling", "Transparency", "Normal"), s.modes.map { it.name })
        assertEquals(listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF), s.modes.map { it.meaning })
        assertEquals(SoundcoreModelMapping.MODE_NC, s.currentModeIndex)
        assertEquals(60, s.batteryPercent) // the lower bud: 3 on the 0..5 scale
        assertEquals(60, s.battery?.left?.percent)
        assertEquals(100, s.battery?.right?.percent)
        assertEquals(80, s.battery?.case?.percent)
        assertEquals("01.23", s.info?.firmwareVersion)
        assertEquals("Soundcore Liberty 4 NC", s.info?.productName)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertTrue(s.capabilities.modesOnHome && s.capabilities.listeningModes)
        assertFalse(s.capabilities.verified)
        assertFalse(s.capabilities.noiseCancelling, "the Bose noise card stays hidden: the level is a vendor control")
        assertEquals(SoundcoreModelMapping.EXPERIMENTAL, s.capabilities.supportNote)
        assertEquals(
            listOf(
                "soundcore_nc_type", "soundcore_nc_level", "soundcore_transparency_type", "soundcore_wind", "soundcore_touch_tone",
                "soundcore_gaming_mode", "soundcore_leak_compensation", "soundcore_multipoint", "soundcore_low_battery_prompt", "soundcore_auto_off",
            ),
            s.vendorControls.map { it.id },
        )
        assertEquals(listOf("Off", "10 min", "20 min", "30 min", "1 hour"), s.vendorControls.last().options)
        assertEquals(2, r.control("soundcore_nc_level").value, "manual level 3 is option index 2 of 1..5")
        r.conn.close()
    }

    @Test fun `Liberty 4 NC - modes change through the layout writes, the rest is refused`() = runTest {
        val r = rig(VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC))
        r.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_TRANSPARENCY))
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = false)))
        assertEquals(SoundcoreModelMapping.MODE_NORMAL, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = true)))
        assertEquals(SoundcoreModelMapping.MODE_NC, r.state.currentModeIndex)
        r.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_NORMAL))
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(7)) }
        for (refused in listOf(HeadsetCommand.SetTouchControls(false), HeadsetCommand.SetEq(HeadphoneEq()), HeadsetCommand.PowerOff, HeadsetCommand.SetAutoOff(5), HeadsetCommand.SetVolume(3))) {
            assertEquals(SoundcoreSession.UNSUPPORTED, assertFailsWith<HeadphoneCommandException> { r.run(refused) }.message, refused.toString())
        }
        r.conn.close()
    }

    @Test fun `Liberty 4 NC - every control round-trips and only documented commands reach the earbuds`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val r = rig(unit)
        // Ambient modes in turn; the other mode fields ride along untouched.
        for (mode in listOf(1, 2, 0)) {
            r.run(HeadsetCommand.SetMode(mode))
            assertEquals(mode, r.state.currentModeIndex)
            assertEquals(2, r.control("soundcore_nc_level").value)
        }
        // In noise cancelling: level, wind, type.
        r.control("soundcore_nc_level", 4)
        assertEquals(4, r.control("soundcore_nc_level").value)
        r.control("soundcore_wind", 1)
        assertEquals(1, r.control("soundcore_wind").value)
        r.control("soundcore_nc_type", 1)
        assertEquals(1, r.control("soundcore_nc_type").value)
        assertFailsWith<HeadphoneCommandException> { r.control("soundcore_nc_level", 0) } // adaptive: no manual level
        r.control("soundcore_nc_type", 0)
        // In transparency: its type.
        r.run(HeadsetCommand.SetMode(1))
        r.control("soundcore_transparency_type", 1)
        assertEquals(1, r.control("soundcore_transparency_type").value)
        // The switches and the auto power-off choice.
        for (id in listOf("soundcore_touch_tone", "soundcore_gaming_mode", "soundcore_leak_compensation", "soundcore_multipoint", "soundcore_low_battery_prompt")) {
            r.control(id, 1)
            assertEquals(1, r.control(id).value, id)
            r.control(id, 0)
            assertEquals(0, r.control(id).value, id)
        }
        r.control("soundcore_auto_off", 3)
        assertEquals(3, r.control("soundcore_auto_off").value)
        r.control("soundcore_auto_off", 0)
        assertEquals(0, r.control("soundcore_auto_off").value)
        assertEquals(2, unit.autoOffIndex, "switching it off keeps the duration")
        val allowed = setOf(
            SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.SET_SOUND_MODES, SoundcoreCommands.SET_TOUCH_TONE,
            SoundcoreCommands.SET_GAMING_MODE_10, SoundcoreCommands.SET_LEAK_COMPENSATION, SoundcoreCommands.SET_MULTIPOINT,
            SoundcoreCommands.SET_LOW_BATTERY_PROMPT, SoundcoreCommands.SET_AUTO_OFF,
        )
        assertTrue(unit.received.all { it.command in allowed }, "${unit.received.map { it.command.toString(16) }}")
        r.conn.close()
    }

    @Test fun `Liberty 4 NC - change one control and read it back on a new connection`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val first = rig(unit)
        first.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_TRANSPARENCY))
        first.control("soundcore_touch_tone", 1)
        first.conn.close()
        // A second connection to the same earbuds sees what the first one left.
        val second = rig(unit)
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, second.state.currentModeIndex)
        assertEquals(1, second.control("soundcore_touch_tone").value)
        assertEquals(0, second.control("soundcore_gaming_mode").value, "the others are as they were")
        second.conn.close()
    }

    @Test fun `an earbud that acknowledges and ignores a write is reported`() = runTest {
        val r = rig(VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC, ignoreWrites = true))
        val e = assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_NORMAL)) }
        assertEquals(SoundcoreSession.NOT_APPLIED, e.message)
        assertEquals(SoundcoreModelMapping.MODE_NC, r.state.currentModeIndex)
        assertFailsWith<HeadphoneCommandException> { r.control("soundcore_touch_tone", 1) }
        r.conn.close()
    }

    @Test fun `a bare ack to the first state request is answered by asking again`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC, bareAckFirst = true)
        val r = rig(unit)
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_STATE), unit.received.map { it.command })
        assertEquals("Soundcore Liberty 4 NC", r.state.info?.productName)
        r.conn.close()
    }

    @Test fun `pushes update battery, charging and the mode block without a re-read`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val r = rig(unit)
        val reads = unit.received.size
        r.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(2, 5, 5), inbound = true))
        r.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_CHARGING, byteArrayOf(1, 0), inbound = true))
        val block = SoundModes(SoundModeCodec.V2_7_TRANSPORT, unit.modeBlock).withAmbient(AmbientMode.TRANSPARENCY).raw
        r.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_SOUND_MODES, block, inbound = true))
        testScheduler.runCurrent()
        assertEquals(40, r.state.batteryPercent)
        assertEquals(100, r.state.battery?.case?.percent)
        assertEquals(true, r.state.isCharging)
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, r.state.currentModeIndex)
        assertEquals(reads, unit.received.size, "nothing was asked")
        // A mode push of the wrong size is ignored.
        r.transport.push(SoundcorePacket(SoundcoreCommands.PUSH_SOUND_MODES, ByteArray(3), inbound = true))
        testScheduler.runCurrent()
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, r.state.currentModeIndex)
        r.conn.close()
    }

    // ---- Headphones and read-only models ----------------------------------------------------------

    @Test fun `Space One Pro headphones map one battery, the same three modes and their own controls`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.SPACE_ONE_PRO, leftBattery = 7)
        val r = rig(unit)
        val s = r.state
        assertEquals(80, s.batteryPercent)
        assertEquals(80, s.battery?.overall?.percent)
        assertNull(s.battery?.left)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertEquals(listOf("soundcore_nc_type", "soundcore_nc_level", "soundcore_wind", "soundcore_multipoint", "soundcore_auto_off"), s.vendorControls.map { it.id })
        assertEquals(listOf("Off", "30 min", "1 hour", "90 min", "2 hours"), s.vendorControls.last().options)
        // Space One Pro keeps byte 2 (a fixed 1) as it is when the mode changes.
        unit.modeBlock = byteArrayOf(0, 0x31, 1, 0, 0, 2)
        r.state = r.session.pollBattery(r.state) // the session re-reads the state: the block it builds from is the new one
        r.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_NORMAL))
        assertEquals(listOf<Byte>(2, 0x31, 1, 0, 0, 2), unit.modeBlock.toList())
        r.conn.close()
    }

    @Test fun `Space 2 is read-only, its mode shows and every change is refused before it leaves the phone`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.SPACE_2)
        unit.modeBlock = byteArrayOf(1, 0x3F, 0xFF.toByte(), 0, 0, 1)
        val r = rig(unit)
        val s = r.state
        assertFalse(r.conn.writesUnlocked)
        assertEquals(SoundcoreModelMapping.MODE_TRANSPARENCY, s.currentModeIndex)
        assertTrue(s.vendorControls.isEmpty())
        assertEquals(SoundcoreModelMapping.READ_ONLY_NOTE, s.capabilities.supportNote)
        assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_NORMAL)) }
        assertFailsWith<HeadphoneCommandException> { r.conn.request(SoundcoreCommands.SET_SOUND_MODES, ByteArray(6)) }
        assertFailsWith<HeadphoneCommandException> { r.conn.request(SoundcoreCommands.SESSION_INIT) }
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), unit.received.map { it.command })
        r.conn.close()
    }

    @Test fun `a model whose table asks for the session init gets it once its state matched`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_5)
        val r = rig(unit)
        assertEquals(
            listOf(SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.SESSION_INFO, SoundcoreCommands.SESSION_INIT),
            unit.received.map { it.command },
        )
        r.conn.close()
        // A model that doesn't ask for it never gets it.
        val plain = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        rig(plain).conn.close()
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), plain.received.map { it.command })
    }

    @Test fun `a model with no layout shows battery and firmware and says it only reads`() = runTest {
        // A Life Note 3 state laid out with the G2T header and no mode block Ruru knows.
        val body = ByteArray(120)
        body[1] = 1; body[2] = 5; body[3] = 4
        "00.99".encodeToByteArray().copyInto(body, 6); "00.99".encodeToByteArray().copyInto(body, 11)
        "3933A1B2C3D4E5F6".encodeToByteArray().copyInto(body, 16)
        val state = SoundcoreStateReader.read(SoundcoreModel.LIFE_NOTE_3, body)!!
        val mapped = SoundcoreModelMapping.apply(HeadphoneState(), state, now = 0)
        assertEquals(80, mapped.batteryPercent)
        assertEquals("Soundcore Life Note 3", mapped.info?.productName)
        assertTrue(mapped.modes.isEmpty())
        assertEquals(SoundcoreModelMapping.BATTERY_ONLY_NOTE, mapped.capabilities.supportNote)
        assertEquals(SoundcoreModel.LIFE_NOTE_3, SoundcoreStateReader.identify(body))
    }

    @Test fun `an unreadable state keeps the name and says nothing is shown`() {
        val mapped = SoundcoreModelMapping.unreadable(HeadphoneState(), SoundcoreModel.P40I, now = 0)
        assertEquals("Soundcore P40i", mapped.info?.productName)
        assertTrue(mapped.modes.isEmpty())
        assertFalse(mapped.capabilities.listeningModes)
        assertEquals(SoundcoreModelMapping.UNREADABLE_NOTE, mapped.capabilities.supportNote)
    }

    // ---- Identification and its failures ----------------------------------------------------------

    @Test fun `a state whose serial names no model Ruru drives is refused with the explained message, after one read`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val liar = object : ByteTransport {
            val inner = AnonymousVirtualSoundcoreTransport(unit)
            override val isConnected get() = inner.isConnected
            override val incoming get() = inner.incoming
            override suspend fun write(bytes: ByteArray) = inner.write(bytes)
            override fun close() = inner.close()
        }
        // Rewrite the serial in every state the earbuds send: digits no model has.
        val conn = SoundcoreConnection(
            object : ByteTransport by liar {
                override val incoming: Flow<ByteArray> = liar.incoming.let { flow ->
                    kotlinx.coroutines.flow.flow {
                        flow.collect { chunk ->
                            val p = SoundcoreFrameBuffer().feed(chunk).first()
                            val body = p.body.copyOf().also { "9999".encodeToByteArray().copyInto(it, 16) }
                            emit(SoundcorePacket(p.command, body, inbound = true).encode())
                        }
                    }
                }
            },
            backgroundScope, minWriteGapMs = 0,
        )
        val session = SoundcoreSession(conn, backgroundScope)
        val e = assertFailsWith<ExplainedConnectException> { session.start(HeadphoneState()) }
        assertEquals(Words(SoundcoreSession.UNKNOWN_MODEL), e.words)
        assertFalse(e.retry)
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), unit.received.map { it.command }, "nothing but the state request was sent")
        assertNull(conn.model)
        conn.close()
    }

    @Test fun `a model named by the transport but contradicted by the serial stays read-only`() = runTest {
        // The demo transport says P40i; the earbuds' state carries a Liberty 5's digits.
        val unit = VirtualSoundcore(SoundcoreModel.P40I)
        val sent = mutableListOf<SoundcorePacket>()
        val t = object : ByteTransport, SoundcoreModelTransport {
            val inbound = Channel<ByteArray>(Channel.UNLIMITED)
            override val soundcoreModel = SoundcoreModel.P40I
            override val isConnected = true
            override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
            override suspend fun write(bytes: ByteArray) {
                for (p in SoundcoreFrameBuffer().feed(bytes)) {
                    sent += p
                    val body = unit.stateBody().also { "3957".encodeToByteArray().copyInto(it, 16) }
                    inbound.trySend(SoundcorePacket(p.command, body, inbound = true).encode())
                }
            }
            override fun close() { inbound.close() }
        }
        val conn = SoundcoreConnection(t, backgroundScope, minWriteGapMs = 0)
        val session = SoundcoreSession(conn, backgroundScope)
        val state = session.start(HeadphoneState())
        assertEquals(SoundcoreModel.P40I, session.model)
        assertFalse(conn.writesUnlocked)
        assertEquals(SoundcoreModelMapping.READ_ONLY_NOTE, state.capabilities.supportNote)
        assertFailsWith<HeadphoneCommandException> { session.run(HeadsetCommand.SetMode(SoundcoreModelMapping.MODE_NORMAL), state) }
        assertEquals(listOf(SoundcoreCommands.REQUEST_STATE), sent.map { it.command })
        conn.close()
    }

    @Test fun `every model with a layout can be emulated, found by its own serial and read back by its own reader`() = runTest {
        for (model in SoundcoreModel.entries.filter { it.layout != null }) {
            val unit = VirtualSoundcore(model)
            val state = assertNotNull(SoundcoreStateReader.read(model, unit.stateBody()), model.code)
            assertEquals(model.canWrite, state.writable, model.code)
            assertEquals(AmbientMode.NOISE_CANCELLING, state.ambient, model.code)
            assertEquals(model.serialPrefix, state.serial?.take(4), model.code)
            // Through the vendor's own session builder, from a bare pipe.
            val session = SoundcoreVendor.session(AnonymousVirtualSoundcoreTransport(VirtualSoundcore(model)), backgroundScope)
            val shown = session.start(HeadphoneState())
            val expected = if (model == SoundcoreModel.R60I_NC) SoundcoreModel.P31I else model
            assertEquals(expected.displayName, shown.info?.productName, model.code)
            session.close()
        }
    }
}
