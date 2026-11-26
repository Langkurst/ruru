package io.github.langkurst.ruru.link

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GattStreamTest {
    @Test
    fun splitKeepsOrderAndBytes() {
        val frame = ByteArray(50) { it.toByte() }
        val pieces = GattWire.split(frame, 20)
        assertEquals(listOf(20, 20, 10), pieces.map { it.size })
        assertContentEquals(frame, pieces.fold(ByteArray(0)) { a, b -> a + b })
        assertTrue(GattWire.split(ByteArray(0), 20).isEmpty())
        assertEquals(1, GattWire.split(frame, 512).size)
    }

    @Test
    fun writeTypeFollowsSpecThenWhatTheCharacteristicOffers() {
        val w = GattWire.PROPERTY_WRITE
        val n = GattWire.PROPERTY_WRITE_NO_RESPONSE
        assertEquals(true, GattWire.chooseWriteType(true, w or n))
        assertEquals(false, GattWire.chooseWriteType(false, w or n))
        assertEquals(true, GattWire.chooseWriteType(false, w))
        assertEquals(false, GattWire.chooseWriteType(true, n))
        assertNull(GattWire.chooseWriteType(true, GattWire.PROPERTY_NOTIFY))
        assertEquals(false, GattWire.chooseIndicate(GattWire.PROPERTY_NOTIFY or GattWire.PROPERTY_INDICATE))
        assertEquals(true, GattWire.chooseIndicate(GattWire.PROPERTY_INDICATE))
        assertNull(GattWire.chooseIndicate(w))
    }

    @Test
    fun writesAreSplitAtTheCurrentWriteSizeAndSentInOrder() = runBlocking {
        val sent = ArrayList<ByteArray>()
        var size = 5
        val t = GattByteTransport({ size }, { sent += it })
        t.write(ByteArray(12) { it.toByte() })
        size = 100
        t.write(ByteArray(7) { 99 })
        assertEquals(listOf(5, 5, 2, 7), sent.map { it.size })
        assertEquals(listOf<Byte>(0, 1, 2, 3, 4), sent[0].toList())
    }

    @Test
    fun notificationsComeOutInArrivalOrderAndDropEndsTheStream() = runBlocking {
        val t = GattByteTransport({ 20 }, {})
        t.deliver(byteArrayOf(1, 2))
        t.deliver(byteArrayOf())
        t.deliver(byteArrayOf(3))
        t.dropped("gone")
        assertFalse(t.isConnected)
        val got = mutableListOf<List<Byte>>()
        val e = runCatching { t.incoming.collect { got += it.toList() } }.exceptionOrNull()
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), got)
        assertTrue(e is IOException)
        assertFailsWith<IOException> { t.write(byteArrayOf(1)) }
        Unit
    }

    @Test
    fun closeRunsOnCloseOnceAndEndsIncoming() = runBlocking {
        var closed = 0
        val t = GattByteTransport({ 20 }, {}, { closed++ })
        t.close()
        t.close()
        assertEquals(1, closed)
        assertEquals(emptyList(), t.incoming.toList())
    }
}
