package io.github.langkurst.ruru.android.ble

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.LeTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GattTransportTest {
    private val service = "0000febe-0000-1000-8000-00805f9b34fb"
    private val writeChar = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"
    private val otherChar = "11111111-2222-3333-4444-555555555555"
    private val spec = GattLinkSpec("Test", service, writeChar, listOf(writeChar), writeWithResponse = true, mtu = 247)

    /** A port that answers every operation at once, the way a healthy headset does. */
    private class FakePort(
        private val grantedMtu: Int = 247,
        private val props: Map<UUID, Int> = emptyMap(),
        private val refuseDiscover: Boolean = false,
        private val failWriteStatus: Int = 0,
    ) : GattPort {
        lateinit var transport: GattTransport
        val writes = mutableListOf<ByteArray>()
        val writeTypes = mutableListOf<Boolean>()
        val enabled = mutableListOf<Pair<UUID, Boolean>>()
        var closed = 0
        var balanced = 0

        override fun discoverServices(): Boolean {
            if (refuseDiscover) return false
            transport.onServicesDiscovered(0)
            return true
        }

        override fun requestHighPriority() = true
        override fun requestBalancedPriority(): Boolean { balanced++; return true }
        override fun requestMtu(mtu: Int): Boolean { transport.onMtuChanged(minOf(mtu, grantedMtu), 0); return true }
        override fun properties(service: UUID, characteristic: UUID): Int? = props[characteristic]
        override fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean): Boolean {
            enabled += characteristic to indicate
            transport.onDescriptorWrite(0)
            return true
        }

        override fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
            writes += value
            writeTypes += withResponse
            transport.onCharacteristicWrite(failWriteStatus)
            return true
        }

        override fun close() { closed++ }
    }

    private fun both() = mapOf(UUID.fromString(writeChar) to (GattTransport.PROPERTY_WRITE or GattTransport.PROPERTY_NOTIFY))

    private fun opened(port: FakePort, spec: GattLinkSpec = this.spec, configure: (GattTransport) -> Unit = {}): GattTransport {
        val t = GattTransport(port, spec, GattTransport.Timeouts(connectMs = 500, discoverMs = 500, mtuMs = 500, descriptorMs = 500, writeMs = 500))
        port.transport = t
        configure(t)
        t.onConnectionStateChange(0, true)
        runBlocking { t.open() }
        return t
    }

    @Test
    fun `setup asks for the MTU, switches notifications on and hands the radio back`() {
        val port = FakePort(props = both())
        val t = opened(port)
        assertTrue(t.isConnected)
        assertEquals(247, t.mtu)
        assertEquals(listOf(UUID.fromString(writeChar) to false), port.enabled)
        assertEquals(1, port.balanced)
    }

    @Test
    fun `a long frame is cut to the ATT payload and every piece waits for its write`() {
        val port = FakePort(grantedMtu = 23, props = both())
        val t = opened(port)
        val frame = ByteArray(50) { it.toByte() }
        runBlocking { t.write(frame) }
        assertEquals(listOf(20, 20, 10), port.writes.map { it.size })
        assertContentEquals(frame, port.writes.reduce { a, b -> a + b })
        assertTrue(port.writeTypes.all { it })
    }

    @Test
    fun `a write that Android reports as failed raises an IOException`() {
        val t = opened(FakePort(props = both(), failWriteStatus = 133))
        assertFailsWith<IOException> { runBlocking { t.write(byteArrayOf(1, 2, 3)) } }
    }

    @Test
    fun `notifications arrive in order and only from the spec's characteristics`() {
        val port = FakePort(props = both())
        val t = opened(port)
        t.onCharacteristicChanged(UUID.fromString(writeChar), byteArrayOf(1))
        t.onCharacteristicChanged(UUID.fromString(otherChar), byteArrayOf(9))
        t.onCharacteristicChanged(UUID.fromString(writeChar), byteArrayOf())
        t.onCharacteristicChanged(UUID.fromString(writeChar), byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf(1), runBlocking { t.incoming.first() })
    }

    @Test
    fun `a sink takes the notifications instead of incoming`() {
        val got = mutableListOf<ByteArray>()
        val t = opened(FakePort(props = both())) { it.sink = { b -> got += b } }
        t.onCharacteristicChanged(UUID.fromString(writeChar), byteArrayOf(4, 5))
        assertEquals(1, got.size)
        assertContentEquals(byteArrayOf(4, 5), got[0])
    }

    @Test
    fun `a drop after setup tells the owner once and ends the stream`() {
        val reasons = mutableListOf<String>()
        val port = FakePort(props = both())
        val t = opened(port) { it.onDrop = { r -> reasons += r } }
        t.onConnectionStateChange(8, false)
        assertEquals(1, reasons.size)
        assertFalse(t.isConnected)
        assertFailsWith<IOException> { runBlocking { t.incoming.first() } }
        assertTrue(port.closed >= 1)
    }

    @Test
    fun `a missing write characteristic fails the setup and releases the client`() {
        val port = FakePort(props = emptyMap())
        val t = GattTransport(port, spec, GattTransport.Timeouts(connectMs = 500, discoverMs = 500, mtuMs = 500, descriptorMs = 500, writeMs = 500))
        port.transport = t
        t.onConnectionStateChange(0, true)
        assertFailsWith<GattSetupException> { runBlocking { t.open() } }
        assertTrue(port.closed >= 1)
    }

    @Test
    fun `Android refusing service discovery is a setup failure`() {
        val port = FakePort(props = both(), refuseDiscover = true)
        val t = GattTransport(port, spec)
        port.transport = t
        t.onConnectionStateChange(0, true)
        assertFailsWith<GattSetupException> { runBlocking { t.open() } }
    }

    @Test
    fun `the write type follows what the characteristic offers`() {
        val withAndWithout = GattTransport.PROPERTY_WRITE or GattTransport.PROPERTY_WRITE_NO_RESPONSE
        assertEquals(true, GattTransport.chooseWriteType(true, withAndWithout))
        assertEquals(false, GattTransport.chooseWriteType(false, withAndWithout))
        assertEquals(false, GattTransport.chooseWriteType(true, GattTransport.PROPERTY_WRITE_NO_RESPONSE))
        assertEquals(true, GattTransport.chooseWriteType(false, GattTransport.PROPERTY_WRITE))
        assertNull(GattTransport.chooseWriteType(true, GattTransport.PROPERTY_NOTIFY))
        assertEquals(false, GattTransport.chooseIndicate(GattTransport.PROPERTY_NOTIFY or GattTransport.PROPERTY_INDICATE))
        assertEquals(true, GattTransport.chooseIndicate(GattTransport.PROPERTY_INDICATE))
        assertNull(GattTransport.chooseIndicate(GattTransport.PROPERTY_WRITE))
    }

    @Test
    fun `chunks are the MTU minus the three header bytes and never below the minimum`() {
        assertEquals(20, GattChunks.payloadSize(23))
        assertEquals(20, GattChunks.payloadSize(5))
        assertEquals(244, GattChunks.payloadSize(247))
        assertEquals(emptyList(), GattChunks.split(ByteArray(0), 247))
        assertEquals(listOf(244, 6), GattChunks.split(ByteArray(250), 247).map { it.size })
    }

    @Test
    fun `a firmware service can never be the spec of a link`() {
        assertFailsWith<IllegalArgumentException> {
            GattLinkSpec("OTA", "66666666-0000-1000-8000-00805f9b34fb", writeChar, listOf(writeChar))
        }
    }

    @Test
    fun `Bose over LE puts the piece header on each write and joins notifications back`() {
        val port = FakePort(grantedMtu = 23, props = both())
        lateinit var le: LeTransport
        val t = opened(port) { g ->
            le = LeTransport(maxWrite = { g.maxWrite }, writePiece = { g.write(it) }, onClose = { g.close() })
            g.sink = { le.deliver(it) }
        }
        val packet = ByteArray(45) { (it + 1).toByte() }
        runBlocking { le.write(packet) }
        // Writes of 20 bytes: a header and 19 bytes of the packet, three pieces for 45 bytes.
        assertEquals(listOf(20, 20, 8), port.writes.map { it.size })
        assertEquals(listOf(0x20, 0x21, 0x22), port.writes.map { it[0].toInt() and 0xFF })
        assertContentEquals(packet, port.writes.map { it.copyOfRange(1, it.size) }.reduce { a, b -> a + b })
        // The headset answers in the same pieces.
        for (w in port.writes) t.onCharacteristicChanged(UUID.fromString(writeChar), w)
        assertContentEquals(packet, runBlocking { le.incoming.first() })
    }
}
