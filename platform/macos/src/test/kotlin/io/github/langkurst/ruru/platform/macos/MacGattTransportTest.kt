package io.github.langkurst.ruru.platform.macos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class MacGattTransportTest {
    private class FakeHelper(maxPdu: Int = 23) {
        private val toRuru = PipedOutputStream()
        private val fromRuru = PipedInputStream(64 * 1024)
        private val ruruIn = PipedInputStream(toRuru, 64 * 1024)
        private val ruruOut = PipedOutputStream(fromRuru)
        var stopped = 0
        val transport = MacGattTransport(ruruIn, ruruOut, MacBtProtocol.Opened(maxPdu, true), "test", stopProcess = { stopped++ })

        fun send(type: Char, payload: ByteArray = ByteArray(0)) {
            toRuru.write(MacBtProtocol.encodeFrame(type, payload))
            toRuru.flush()
        }

        fun nextFromRuru(): MacBtProtocol.Frame = MacBtProtocol.readFrame(fromRuru)!!

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
        assertContentEquals(bytes.copyOfRange(20, 30), h.nextFromRuru().payload)
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
        h.send('N', byteArrayOf(2, 3))
        h.send('D', "the headphones disconnected".toByteArray())
        val got = ArrayList<ByteArray>()
        val e = runCatching { withTimeout(2_000) { h.transport.incoming.collect { got += it } } }.exceptionOrNull()
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2, 3)), got.map { it.toList() })
        assertTrue(e is IOException && e.message == "the headphones disconnected", "got $e")
        assertFalse(h.transport.isConnected)
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
        val le = MacHeadsetPlatform.boseLeOver(h.transport)
        // [0.1] BMAP version, a GET: one piece, header 0x00.
        val writing = async(Dispatchers.IO) { le.write(byteArrayOf(0x00, 0x01, 0x01, 0x00)) }
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x01, 0x01, 0x00), h.nextFromRuru().payload)
        h.send('K', byteArrayOf(0))
        withTimeout(2_000) { writing.await() }
        // A reply in two pieces (header 0x10, then 0x11) comes out as one packet.
        h.send('N', byteArrayOf(0x10, 0x00, 0x01, 0x03))
        h.send('N', byteArrayOf(0x11, 0x03, 0x31, 0x2E, 0x30))
        assertContentEquals(byteArrayOf(0x00, 0x01, 0x03, 0x03, 0x31, 0x2E, 0x30), withTimeout(2_000) { le.incoming.first() })
        le.close()
        assertEquals('Q', h.nextFromRuru().type)
    }
}
