package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The GATT pipe against a pretend helper on two pipes: what Ruru writes, and what the "helper" answers. */
class WinGattTransportTest {
    private class FakeHelper(maxPdu: Int = 23) {
        private val toRuru = PipedOutputStream()
        private val fromRuru = PipedInputStream(64 * 1024)
        private val ruruIn = PipedInputStream(toRuru, 64 * 1024)
        private val ruruOut = PipedOutputStream(fromRuru)
        var stopped = 0
        val transport = WinGattTransport(ruruIn, ruruOut, WinBleProtocol.Opened(maxPdu, true), "test", stopProcess = { stopped++ })

        fun send(type: Char, payload: ByteArray = ByteArray(0)) {
            toRuru.write(WinBleProtocol.encodeFrame(type, payload))
            toRuru.flush()
        }

        fun nextFromRuru(): WinBleProtocol.Frame = WinBleProtocol.readFrame(fromRuru)!!

        fun end() = toRuru.close()
    }

    @Test
    fun writeWaitsForEachResultAndCutsToThePdu(): Unit = runBlocking {
        val h = FakeHelper(maxPdu = 23)
        h.transport.start()
        val bytes = ByteArray(30) { it.toByte() }
        val writing = async(Dispatchers.IO) { h.transport.write(bytes) }
        val first = h.nextFromRuru()
        assertEquals('W', first.type)
        assertEquals(20, first.payload.size)
        assertFalse(writing.isCompleted, "the second piece waits for the first one's result")
        h.send('K', byteArrayOf(0))
        val second = h.nextFromRuru()
        assertContentEquals(bytes.copyOfRange(20, 30), second.payload)
        h.send('K', byteArrayOf(0))
        withTimeout(2_000) { writing.await() }
    }

    @Test
    fun refusedWriteThrows(): Unit = runBlocking {
        val h = FakeHelper()
        h.transport.start()
        val writing = async(Dispatchers.IO) { runCatching { h.transport.write(byteArrayOf(1, 2, 3)) } }
        h.nextFromRuru()
        h.send('K', byteArrayOf(1) + "write refused (protocol error 3)".toByteArray())
        val e = withTimeout(2_000) { writing.await() }.exceptionOrNull()
        assertTrue(e is IOException && "protocol error 3" in e.message.orEmpty(), "got $e")
    }

    @Test
    fun notificationsArriveInOrderAndADropEndsTheStream(): Unit = runBlocking {
        val h = FakeHelper()
        h.transport.start()
        h.send('N', byteArrayOf(1))
        h.send('M', byteArrayOf(0, 247.toByte()))
        h.send('N', byteArrayOf(2, 3))
        h.send('D', "the headphones disconnected".toByteArray())
        val got = ArrayList<ByteArray>()
        val e = runCatching { withTimeout(2_000) { h.transport.incoming.collect { got += it } } }.exceptionOrNull()
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2, 3)), got.map { it.toList() })
        assertTrue(e is IOException && e.message == "the headphones disconnected", "got $e")
        assertFalse(h.transport.isConnected)
        assertEquals(244, h.transport.maxWrite, "the new PDU from the M frame")
        assertEquals(1, h.stopped)
        assertFailsWith<IOException> { h.transport.write(byteArrayOf(1)) }
    }

    @Test
    fun helperExitEndsTheStreamWithAnError(): Unit = runBlocking {
        val h = FakeHelper()
        h.transport.start()
        h.end()
        val e = runCatching { withTimeout(2_000) { h.transport.incoming.toList() } }.exceptionOrNull()
        assertTrue(e is IOException, "got $e")
    }

    @Test
    fun closeSendsQAndEndsQuietly(): Unit = runBlocking {
        val h = FakeHelper()
        h.transport.start()
        h.transport.close()
        assertEquals('Q', h.nextFromRuru().type)
        assertEquals(1, h.stopped)
        assertEquals(emptyList(), withTimeout(2_000) { h.transport.incoming.toList() })
        h.transport.close()
        assertEquals(1, h.stopped, "closing twice stops the helper once")
    }

    @Test
    fun boseLeAddsThePieceHeaderBothWays(): Unit = runBlocking {
        val h = FakeHelper(maxPdu = 23)
        val le = WindowsHeadsetPlatform.boseLeOver(h.transport)
        // [0.1] BMAP version, a GET: one piece, header 0x00.
        val writing = async(Dispatchers.IO) { le.write(byteArrayOf(0x00, 0x01, 0x01, 0x00)) }
        val sent = h.nextFromRuru()
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x01, 0x01, 0x00), sent.payload)
        h.send('K', byteArrayOf(0))
        withTimeout(2_000) { writing.await() }
        // A reply in two pieces (header 0x10, then 0x11) comes out as one packet.
        h.send('N', byteArrayOf(0x10, 0x00, 0x01, 0x03))
        h.send('N', byteArrayOf(0x11, 0x03, 0x31, 0x2E, 0x30))
        assertContentEquals(byteArrayOf(0x00, 0x01, 0x03, 0x03, 0x31, 0x2E, 0x30), withTimeout(2_000) { le.incoming.first() })
        le.close()
        assertEquals('Q', h.nextFromRuru().type)
    }

    @Test
    fun boseLeLongPacketIsCutToTheLink(): Unit = runBlocking {
        val h = FakeHelper(maxPdu = 23)
        val le = WindowsHeadsetPlatform.boseLeOver(h.transport)
        val packet = ByteArray(25) { it.toByte() }
        val writing = async(Dispatchers.IO) { le.write(packet) }
        val first = h.nextFromRuru().payload
        h.send('K', byteArrayOf(0))
        val second = h.nextFromRuru().payload
        h.send('K', byteArrayOf(0))
        withTimeout(2_000) { writing.await() }
        assertEquals(20, first.size)
        assertEquals(0x10, first[0].toInt(), "piece 0 of 2")
        assertEquals(0x11, second[0].toInt(), "piece 1 of 2")
        assertContentEquals(packet, first.copyOfRange(1, first.size) + second.copyOfRange(1, second.size))
        le.close()
    }

    @Test
    fun routesFollowTheHelper() {
        assertEquals(setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE), WindowsHeadsetPlatform(ble = null).routes)
        assertEquals(
            setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.BOSE_LE, RouteKind.GATT),
            WindowsHeadsetPlatform(ble = WinBleHelper(File("missing-helper.exe"))).routes,
        )
    }

    @Test
    fun leRoutesRefuseBeforeStartingAnything(): Unit = runBlocking {
        val classic = HeadsetCandidate(key = "a1b2c3d4e5f6", name = "Bose QC Ultra Headphones", connected = true)
        // No helper: refused as a route the platform does not offer.
        assertFailsWith<RouteNotOfferedException> { WindowsHeadsetPlatform(ble = null).open(classic, LinkRoute.BoseLe) }
        val uuid = "0000fe59-0000-1000-8000-00805f9b34fb"
        assertFailsWith<RouteNotOfferedException> { WindowsHeadsetPlatform(ble = null).open(classic, LinkRoute.Gatt(io.github.langkurst.ruru.link.GattLinkSpec("t", uuid, uuid, listOf(uuid)))) }
        // With a helper (that doesn't exist, so starting it would fail differently): a classic key is never handed over.
        val e = assertFailsWith<IOException> { WindowsHeadsetPlatform(ble = WinBleHelper(File("missing-helper.exe"))).open(classic, LinkRoute.BoseLe) }
        assertEquals("Bose QC Ultra Headphones is not in Windows' Bluetooth LE list", e.message)
    }

    @Test
    fun leListPutsConnectedAndPlainNamesFirst() {
        val ordered = WindowsHeadsetPlatform.orderLe(listOf(
            WinBleProtocol.Device("0000000000000001", false, "Bose QC Ultra Headphones"),
            WinBleProtocol.Device("0000000000000002", true, "LE-Bose QC Ultra Headphones"),
            WinBleProtocol.Device("0000000000000003", true, "Bose QC Ultra Headphones"),
        ))
        assertEquals(listOf("0000000000000003", "0000000000000002", "0000000000000001"), ordered.map { it.key })
    }
}
