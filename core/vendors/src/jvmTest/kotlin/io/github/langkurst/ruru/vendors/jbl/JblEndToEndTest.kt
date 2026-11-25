package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.VendorRegistry
import io.github.langkurst.ruru.vendors.jbl.JblCommands as C
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real JBL stack ([JblVendor] route, [JblConnection] with its write guard, [JblSession] as the
 * controller drives it, [JblStateMapping]) against a [VirtualJbl] reached through a fake GATT link
 * ([FakeGatt]) that enforces what a platform's GATT transport does: one write per frame to the
 * Harman write characteristic, one notification per frame from the Harman notify characteristic.
 * The numbers in the captured replies are jbl-control's Wave Buds 2 (MIT).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JblEndToEndTest {

    @BeforeTest
    fun quiet() {
        Logging.sink = { _, _, _, _ -> }
    }

    /**
     * What a GATT platform's [ByteTransport] does with a [GattLinkSpec]: it opens the spec's service
     * on a headset that has exactly the characteristics in [server], writes each frame to the write
     * characteristic in pieces no longer than the ATT payload, and hands over notifications. A
     * piece of a frame would not decode on the virtual headset, so the test also proves that every
     * JBL frame fits one write.
     */
    private class FakeGatt(private val unit: VirtualJbl, val spec: GattLinkSpec, server: Set<String>, val attPayload: Int = 509) : ByteTransport {
        private val inner = VirtualJblTransport(unit)
        val writes = mutableListOf<ByteArray>()

        init {
            // The platform's service lookup: the service must exist, and so must the characteristics the spec names.
            check(spec.service in server) { "service ${spec.service} not found" }
            check(spec.write in server && spec.notify.all { it in server }) { "characteristic not found" }
        }

        override val isConnected: Boolean get() = inner.isConnected
        override val incoming: Flow<ByteArray> get() = inner.incoming

        override suspend fun write(bytes: ByteArray) {
            assertTrue(bytes.size <= attPayload, "a ${bytes.size}-byte frame would need more than one ATT write")
            writes += bytes
            inner.write(bytes)
        }

        override fun close() = inner.close()
    }

    /** The headset's GATT table: the Harman service, its three characteristics, and a firmware service that must never be opened. */
    private val harmanServer: Set<String> = setOf(JblVendor.HARMAN.service, JblVendor.HARMAN.write) + JblVendor.HARMAN.notify
    private val firmwareService = "66666666-6666-6666-6666-666666666666"

    private class Rig(val unit: VirtualJbl, val gatt: FakeGatt, val session: JblSession, val conn: JblConnection) {
        var state = HeadphoneState()
        fun control(id: String) = state.vendorControls.first { it.id == id }
        suspend fun run(command: HeadsetCommand) { state = session.run(command, state) }
        suspend fun control(id: String, value: Int) = run(HeadsetCommand.SetVendorControl(id, value))
        fun sent(): List<JblFrame> = unit.received.toList()
    }

    /** One connection the way the controller makes it: the vendor's route, the platform's transport, the vendor's session. */
    private suspend fun TestScope.connect(unit: VirtualJbl = VirtualJbl(), name: String = unit.name, start: Boolean = true): Rig {
        val vendor = assertNotNull(VendorRegistry.forFamily(DeviceFamily.resolve(null, null, name, emptyList())))
        assertEquals(JblVendor, vendor)
        val route = assertNotNull(vendor.route(HeadsetCandidate("k", name, true), setOf(RouteKind.GATT)))
        assertIs<LinkRoute.Gatt>(route)
        val gatt = FakeGatt(unit, route.spec, harmanServer + firmwareService)
        // The session as the vendor makes it (its connection is reachable for the guard checks).
        val session = assertIs<JblSession>(JblVendor.session(gatt, backgroundScope))
        val r = Rig(unit, gatt, session, session.conn)
        backgroundScope.launch { session.pushes.collect { p -> session.onPush(p, r.state)?.let { r.state = it } } }
        testScheduler.runCurrent()
        if (start) r.state = session.start(r.state)
        testScheduler.runCurrent()
        return r
    }

    // ---- Route ----------------------------------------------------------------------------------

    @Test fun `the route is the Harman service over GATT, never a firmware service`() = runTest {
        val route = assertIs<LinkRoute.Gatt>(JblVendor.route(HeadsetCandidate("k", "JBL Wave Buds 2", true), setOf(RouteKind.GATT, RouteKind.RFCOMM_SERVICE)))
        assertEquals(JblVendor.HARMAN, route.spec)
        assertEquals("65786365-6c70-6f69-6e74-2e636f6d0000", route.spec.service)
        assertFalse(GattLinkSpec.isFirmwareService(route.spec.service))
        assertTrue(route.spec.writeWithResponse)
        assertNull(JblVendor.route(HeadsetCandidate("k", "JBL Wave Buds 2", true), setOf(RouteKind.RFCOMM_SERVICE, RouteKind.BOSE_RFCOMM)), "a system without GATT opens nothing")
        // The platform's GATT lookup of a headset that lacks the service fails before a byte is written.
        val e = assertFailsWith<IllegalStateException> { FakeGatt(VirtualJbl(), route.spec, setOf(firmwareService)) }
        assertTrue("not found" in e.message!!)
    }

    @Test fun `the vendor claims the names JblModel does, the LE twin included`() {
        for (name in listOf("JBL Wave Buds 2", "JBL Wave Buds 2-LE", "LE-JBL Tour One M2", "JBL LIVE660NC", "JBL Tune 230NC TWS")) assertTrue(JblVendor.detect(name), name)
        for (name in listOf("JBL Tune 770NC", "JBL Tour One M3", "JBL Charge 5", "Wave Buds 2", null)) assertFalse(JblVendor.detect(name), "$name")
        assertTrue(JblVendor.sessionReady)
        assertEquals(DeviceFamily.JBL, DeviceFamily.resolve(null, null, "JBL Wave Buds 2", emptyList()))
    }

    // ---- Connect, read, change, read back --------------------------------------------------------

    @Test fun `connect reads a Wave Buds 2 into HeadphoneState`() = runTest {
        val r = connect()
        assertEquals(JblModel.WAVE_BUDS_2, r.session.model)
        val s = r.state
        assertEquals(listOf("Noise cancelling", "Ambient Aware", "Off", "TalkThru"), s.modes.map { it.name })
        assertEquals(listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF, ModeMeaning.CUSTOM), s.modes.map { it.meaning })
        assertEquals(JblStateMapping.MODE_AMBIENT, s.currentModeIndex)
        // Left bud 100 %, right bud in its case (unknown), case 100 %.
        assertEquals(100, s.batteryPercent)
        assertEquals(100, s.battery?.left?.percent)
        assertNull(s.battery?.right?.percent)
        assertEquals(100, s.battery?.case?.percent)
        assertEquals("JBL Wave Buds 2", s.info?.modelName)
        assertEquals("2.7.0", s.info?.firmwareVersion)
        with(s.capabilities) {
            assertFalse(verified)
            assertEquals(FormFactor.EARBUDS, formFactor)
            assertEquals(JblStateMapping.SUPPORT_NOTE, supportNote)
            assertFalse(editableModes || favoriteModes || spatialAudio)
        }
        // The Wave Buds 2 never answers the level read: only the EQ preset and voice prompts are drawn.
        assertEquals(listOf("jbl_eq_preset", "jbl_voice_prompts"), s.vendorControls.map { it.id })
        assertEquals(0, r.control("jbl_eq_preset").value)
        assertEquals(1, r.control("jbl_voice_prompts").value)
        assertTrue(r.unit.notificationsOn, "pushes were switched on")
        // The only writes were reads and the push registration.
        assertTrue(r.sent().all { JblWriteGuard.isRead(it) })
        r.conn.close()
    }

    @Test fun `change one control and one mode, then read both back on a new connection`() = runTest {
        val unit = VirtualJbl(ancLevel = 3)
        val first = connect(unit)
        assertEquals(listOf("jbl_anc_level", "jbl_eq_preset", "jbl_voice_prompts"), first.state.vendorControls.map { it.id })
        assertEquals(3, first.control("jbl_anc_level").value)

        // One control: the EQ preset "Bass" (index 3 of the app's list).
        val bass = C.EQ_PRESETS.indexOfFirst { it.label == "Bass" }
        first.control("jbl_eq_preset", bass)
        assertEquals(3, unit.eqCategory)
        assertEquals(C.eqPreset(3)!!.gainsDb, unit.eqGains)
        assertEquals(bass, first.control("jbl_eq_preset").value, "read back on the same link")
        // One mode: Noise cancelling.
        first.run(HeadsetCommand.SetMode(JblStateMapping.MODE_ANC))
        assertEquals(C.WIRE_ANC, unit.mode)
        assertEquals(JblStateMapping.MODE_ANC, first.state.currentModeIndex)
        first.conn.close()

        // A new connection to the same headset reads what the headset now holds.
        val second = connect(unit)
        assertEquals(bass, second.control("jbl_eq_preset").value)
        assertEquals(JblStateMapping.MODE_ANC, second.state.currentModeIndex)
        assertEquals(3, second.control("jbl_anc_level").value)
        // And puts it back.
        second.control("jbl_eq_preset", 0)
        second.run(HeadsetCommand.SetMode(JblStateMapping.MODE_AMBIENT))
        assertEquals(C.EQ_OFF, unit.eqCategory)
        assertEquals(C.WIRE_AMBIENT, unit.mode)
        second.conn.close()
        val third = connect(unit)
        assertEquals(0, third.control("jbl_eq_preset").value)
        assertEquals(JblStateMapping.MODE_AMBIENT, third.state.currentModeIndex)
        third.conn.close()
    }

    @Test fun `the level and voice prompts are written and read back`() = runTest {
        val unit = VirtualJbl(ancLevel = 2)
        val r = connect(unit)
        r.control("jbl_anc_level", 6)
        assertEquals(6, unit.ancLevel)
        assertEquals(6, r.control("jbl_anc_level").value)
        r.control("jbl_voice_prompts", 0)
        assertFalse(unit.voicePrompts)
        assertEquals(0, r.control("jbl_voice_prompts").value)
        // Out-of-range values are refused before anything is sent.
        val before = unit.received.size
        assertFailsWith<HeadphoneCommandException> { r.control("jbl_anc_level", 9) }
        assertFailsWith<HeadphoneCommandException> { r.control("jbl_eq_preset", 99) }
        assertFailsWith<HeadphoneCommandException> { r.control("jbl_nothing", 1) }
        assertEquals(before, unit.received.size)
        r.conn.close()
    }

    @Test fun `a mode the buds decline is reported, not shown as changed`() = runTest {
        val unit = VirtualJbl(bothBudsIn = false)
        val r = connect(unit)
        val e = assertFailsWith<HeadphoneCommandException> { r.run(HeadsetCommand.SetMode(JblStateMapping.MODE_ANC)) }
        assertTrue("stayed in Ambient Aware" in e.message!!, e.message)
        assertTrue("both buds" in e.message!!)
        assertEquals(C.WIRE_AMBIENT, unit.mode)
        r.conn.close()
    }

    @Test fun `a tap on the buds and a battery change reach the state as pushes`() = runTest {
        val r = connect()
        r.unit.pushMode(C.WIRE_TALKTHRU)
        testScheduler.runCurrent()
        assertEquals(JblStateMapping.MODE_TALKTHRU, r.state.currentModeIndex)
        r.unit.pushBattery(40, 30, 80)
        testScheduler.runCurrent()
        assertEquals(30, r.state.batteryPercent, "the lower bud is the headline")
        assertEquals(80, r.state.battery?.case?.percent)
        r.conn.close()
    }

    @Test fun `the heartbeat is echoed once the device info is known`() = runTest {
        val r = connect()
        r.unit.pushHeartbeat()
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(1, r.unit.heartbeatEchoes)
        r.conn.close()
    }

    @Test fun `the battery poll re-reads the batteries and nothing else`() = runTest {
        val r = connect()
        val before = r.unit.received.size
        r.unit.left = 55
        r.unit.right = 66
        r.state = r.session.pollBattery(r.state)
        assertEquals(55, r.state.batteryPercent)
        val polled = r.unit.received.drop(before)
        assertEquals(listOf(C.INFO), polled.map { it.command })
        r.conn.close()
    }

    // ---- Other stacks and strangers -------------------------------------------------------------------

    @Test fun `a headset of JBL's newer protocol is refused after nothing but the device info was seen`() = runTest {
        val unit = VirtualJbl(name = "JBL Tour One M3", productId = 0x2101)
        val r = connect(unit, name = "JBL Wave Buds 2", start = false)
        val e = assertFailsWith<ExplainedConnectException> { r.session.start(HeadphoneState()) }
        assertEquals(Words("jbl_newer_protocol"), e.words)
        assertFalse(e.retry)
        assertTrue(unit.received.isEmpty(), "not one byte was written to it")
        r.conn.close()
    }

    @Test fun `an Airoha model is refused the same way`() = runTest {
        val unit = VirtualJbl(productId = 0x20B7)
        val r = connect(unit, start = false)
        val e = assertFailsWith<ExplainedConnectException> { r.session.start(HeadphoneState()) }
        assertEquals(Words("jbl_airoha"), e.words)
        assertTrue(unit.received.isEmpty())
        r.conn.close()
    }

    @Test fun `a link that opens and stays silent is the maker's app holding it`() = runTest {
        val silent = object : ByteTransport {
            private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
            val written = mutableListOf<ByteArray>()
            override var isConnected = true
            override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
            override suspend fun write(bytes: ByteArray) { written += bytes }
            override fun close() { isConnected = false; inbox.close() }
        }
        val session = JblVendor.session(silent, backgroundScope)
        val e = assertFailsWith<HeadphoneCommandException> { session.start(HeadphoneState()) }
        assertEquals("The headphones didn't answer", e.message)
        assertTrue(silent.written.all { JblWriteGuard.isAllowed(JblFrame.decodeWhole(it)!!) })
        assertTrue(silent.written.all { it[1].toInt() and 0xFF == C.DEVICE_INFO_GET }, "nothing but the device-info ask went out")
        session.close()
        assertNull(session.awaitClosed(), "closed by us, not by the link")
    }

    // ---- What is not a JBL setting ----------------------------------------------------------------------

    @Test fun `commands that are not JBL settings send nothing`() = runTest {
        val r = connect()
        val before = r.unit.received.size
        val others = listOf<HeadsetCommand>(
            HeadsetCommand.PowerOff, HeadsetCommand.SetDeviceName("x"), HeadsetCommand.SetFavorites(setOf(0)),
            HeadsetCommand.SwitchDevice("00:00:00:00:00:00"), HeadsetCommand.SetVolume(3), HeadsetCommand.SetVoicePrompts(false),
        )
        for (c in others) assertFailsWith<HeadphoneCommandException>("$c") { r.run(c) }
        assertEquals(before, r.unit.received.size)
        r.conn.close()
    }

    @Test fun `the Lab reads each documented read once and every one is a read`() = runTest {
        val r = connect()
        val profile = assertNotNull(r.session.labProfile())
        assertEquals("JBL H-AA", profile.title)
        assertEquals(JblReads.ALL.size, profile.reads.size)
        val before = r.unit.received.size
        val lines = r.session.scanLab()
        assertEquals(JblReads.ALL.size, lines.size)
        assertTrue(r.unit.received.drop(before).all { JblWriteGuard.isRead(it) })
        // A read the Wave Buds 2 never answers shows as such, and the device info shows only its size.
        assertTrue(lines.any { it.contains("no answer") })
        assertTrue(lines.none { "Wave Buds" in it || "4a 42 4c" in it.lowercase() }, "no name or address in the report")
        r.conn.close()
    }

    @Test fun `the log holds sizes and ids, never the headset's name`() = runTest {
        io.github.langkurst.ruru.link.LogRing.clear()
        val r = connect()
        r.control("jbl_eq_preset", 1)
        r.conn.close()
        val log = io.github.langkurst.ruru.link.LogRing.tail(400).joinToString("\n")
        assertTrue(log.isNotEmpty())
        assertFalse("Wave Buds" in log, log)
        assertFalse("50:1B" in log.uppercase() || "6A:2F" in log.uppercase(), log)
    }
}
