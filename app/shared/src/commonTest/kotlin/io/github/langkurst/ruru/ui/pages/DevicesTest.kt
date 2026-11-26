package io.github.langkurst.ruru.ui.pages

import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.DemoPlatform
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.PairedDevice
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.WriteGuard
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.FunctionAddress
import io.github.langkurst.ruru.protocol.bose.QcUltra2
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Devices page: what it lists, when it offers "Switch to this", and that nothing in Ruru can unpair a device. */
class DevicesTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        Logging.sink = { _, _, _, _ -> }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun state(vararg devices: PairedDevice) = HeadphoneState(pairedDevices = devices.toList())

    @Test
    fun `the page lists what the headphones report and offers a switch only to a connected device that is not playing`() {
        val rows = deviceRows(
            state(
                PairedDevice("A", "Laptop", isActiveSource = true, isThisPhone = true, isConnected = true),
                PairedDevice("B", "Phone", isConnected = true),
                PairedDevice("C", "Old tablet", isConnected = false),
                PairedDevice("D", null, isConnected = null),
            ),
        )
        assertEquals(listOf("Laptop", "Phone", "Old tablet", null), rows.map { it.name })
        assertEquals(listOf(false, true, false, true), rows.map { it.canSwitch }, "playing, connected, absent, unknown")
        assertEquals(listOf(true, false, false, false), rows.map { it.thisDevice })
    }

    @Test
    fun `a blank name is shown as unnamed and the page is hidden without paired devices`() {
        assertNull(deviceRows(state(PairedDevice("A", "   "))).single().name)
        assertFalse(hasDevices(HeadphoneState()))
        assertTrue(hasDevices(state(PairedDevice("A", "Phone"))))
    }

    @Test
    fun `the page is in the sidebar only when the headphones report paired devices`() {
        val pages = PageAvailability(system = SystemKind.Windows, hasProEq = false)
        assertTrue(DeskPageDevices in pages.deskPages(sound = true, settings = true, devices = true, tools = true))
        assertFalse(DeskPageDevices in pages.deskPages(sound = true, settings = true, devices = false, tools = true))
    }

    /** Hands the controller a simulated headset and keeps the transport so the frames it was sent can be read. */
    private class RecordingPlatform(val device: VirtualQcUltra2) : HeadsetPlatform {
        override val name = "Test"
        override val supported = true
        override val routes = setOf(RouteKind.BOSE_RFCOMM)
        var last: VirtualTransport? = null
        override suspend fun headsets() = listOf(HeadsetCandidate("t", "Test QC Ultra", true))
        override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
            device.powerOn()
            return VirtualTransport(device).also { last = it }
        }
    }

    private suspend fun HeadsetController.await(predicate: (AppState) -> Boolean): AppState =
        withTimeout(20_000) { state.first(predicate) }

    @Test
    fun `the demo headset has two made-up devices, switching moves the sound, and no unpairing frame is ever sent`() = runBlocking {
        val device = VirtualQcUltra2(profile = DemoPlatform.PROFILE)
        // Same two devices as the demo; the platform here only adds the transport recording.
        device.pairedDevices.clear()
        device.pairedDevices += VirtualQcUltra2.Peer("AA:BB:CC:00:00:01", "Laptop")
        device.pairedDevices += VirtualQcUltra2.Peer("AA:BB:CC:00:00:02", "Phone")
        device.activeSourceMac = "AA:BB:CC:00:00:01"
        val platform = RecordingPlatform(device)
        val c = HeadsetController(platform, scope).also { it.start() }
        val connected = c.await { it.phase == Phase.Connected && it.headset.pairedDevices.size == 2 }
        val rows = deviceRows(connected.headset)
        assertEquals(listOf("Laptop", "Phone"), rows.map { it.name })
        val phone = rows.single { it.canSwitch }
        assertEquals("Phone", phone.name)

        c.switchDevice(phone.address)
        val after = c.await { s -> s.headset.pairedDevices.firstOrNull { it.address == phone.address }?.isActiveSource == true }
        assertEquals(listOf(false, true), deviceRows(after.headset).map { it.playing })
        c.setTouchControls(false)
        c.await { it.headset.touchControlsEnabled == false }
        c.stop()

        // Every frame this session sent: none of them can unpair, clear, disconnect or reset anything.
        val sent = assertNotNull(platform.last).sentRequests()
        assertTrue(sent.any { it.address == QcUltra2.ROUTING && it.operator == BmapOperator.START }, "the switch frame was sent")
        val forbidden = setOf(QcUltra2.DM_REMOVE_DEVICE, QcUltra2.DM_DISCONNECT, FunctionAddress(4, 7), FunctionAddress(7, 5), FunctionAddress(7, 6), FunctionAddress(7, 11))
        for (packet in sent) {
            assertFalse(packet.address in forbidden, "sent $packet")
            assertNull(WriteGuard.refusalReason(packet), "the guard refuses nothing that Ruru sends: $packet")
        }
    }

    @Test
    fun `the demo shows two made-up devices and the touch switch, never real names`() = runBlocking {
        val c = HeadsetController(DemoPlatform(), scope, demo = true).also { it.start() }
        val s = c.await { it.phase == Phase.Connected && it.headset.pairedDevices.size == 2 && it.headset.touchControlsEnabled != null }
        assertEquals(listOf("Laptop", "Phone"), s.headset.pairedDevices.map { it.name })
        assertEquals(listOf(true, false), s.headset.pairedDevices.map { it.isActiveSource })
        assertEquals(true, s.headset.touchControlsEnabled)
        c.stop()
    }

    @Test
    fun `a removal frame is refused by the guard whatever asks for it`() {
        for (op in listOf(BmapOperator.START, BmapOperator.SET_GET, BmapOperator.SET)) {
            val packet = BmapPacket(QcUltra2.DM_REMOVE_DEVICE.block, QcUltra2.DM_REMOVE_DEVICE.function, op, byteArrayOf(1, 2, 3, 4, 5, 6))
            assertNotNull(WriteGuard.refusalReason(packet), op.name)
        }
    }

    private companion object {
        val DeskPageDevices = io.github.langkurst.ruru.app.DeskPage.Devices
    }
}
