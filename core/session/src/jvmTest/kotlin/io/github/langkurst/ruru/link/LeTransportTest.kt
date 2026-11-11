package io.github.langkurst.ruru.link

import io.github.langkurst.ruru.protocol.hexToBytes
import io.github.langkurst.ruru.protocol.toHex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LeTransportTest {
    @Test
    fun `a short packet goes out as one write with header 0x00`() {
        val pieces = LeFraming.split("00010100".hexToBytes(), maxWrite = 20)
        assertEquals(listOf("0000010100"), pieces.map { it.toHex() })
    }

    @Test
    fun `a long packet is split into numbered pieces that fit the write size`() {
        val packet = ByteArray(51) { it.toByte() }
        val pieces = LeFraming.split(packet, maxWrite = 20)
        assertEquals(listOf(0x20, 0x21, 0x22), pieces.map { it[0].toInt() and 0xFF })
        assertEquals(listOf(20, 20, 14), pieces.map { it.size })
        val joiner = LeFraming.Joiner()
        val out = pieces.map { joiner.feed(it) }
        assertNull(out[0])
        assertNull(out[1])
        assertContentEquals(packet, out[2])
    }

    @Test
    fun `the 52-byte notification the 1st Gen sent on 11 November joins to its 51-byte reply`() {
        // GET [31.6] slot 0 over LE, read at 18:41; the serial channel read the same 47-byte payload.
        val payload = "0000010000015175696574000000000000000000000000000000000000000000000000000000000000000000000000"
        val notification = ("00" + "1f06032f" + payload).hexToBytes()
        assertEquals(52, notification.size)
        val packet = LeFraming.Joiner().feed(notification)!!
        assertEquals("1f06032f$payload", packet.toHex())
    }

    @Test
    fun `a piece out of order drops that message, and the next one still joins`() {
        val joiner = LeFraming.Joiner()
        assertNull(joiner.feed("21aabb".hexToBytes())) // piece 1 with no piece 0
        assertNull(joiner.feed("20aa".hexToBytes()))
        assertNull(joiner.feed("22cc".hexToBytes())) // piece 2 where piece 1 should be
        assertContentEquals("0102".hexToBytes(), joiner.feed("000102".hexToBytes()))
    }

    @Test
    fun `a packet too big for sixteen pieces is refused rather than sent wrong`() {
        assertFailsWith<IllegalArgumentException> { LeFraming.split(ByteArray(16 * 19 + 1), maxWrite = 20) }
        assertEquals(16, LeFraming.split(ByteArray(16 * 19), maxWrite = 20).size)
    }

    @Test
    fun `the transport writes headed pieces and reads whole packets`() = runBlocking {
        val written = ArrayList<String>()
        val t = LeTransport(maxWrite = { 4 }, writePiece = { written += it.toHex() })
        t.write("1f060101".hexToBytes() + "00".hexToBytes())
        assertEquals(listOf("101f0601", "110100"), written)
        t.deliver("10aabbcc".hexToBytes())
        t.deliver("11dd".hexToBytes())
        t.deliver("00ee".hexToBytes())
        t.close()
        assertEquals(listOf("aabbccdd", "ee"), t.incoming.toList().map { it.toHex() })
    }

    @Test
    fun `a dropped link ends incoming with an error and refuses writes`() = runBlocking {
        var closedByUs = false
        val t = LeTransport(maxWrite = { 20 }, writePiece = {}, onClose = { closedByUs = true })
        t.dropped("The headphones disconnected")
        assertFailsWith<IOException> { t.incoming.toList() }
        assertFailsWith<IOException> { t.write("00010100".hexToBytes()) }
        t.close()
        assertEquals(false, closedByUs) // already down: nothing left to close
    }
}
