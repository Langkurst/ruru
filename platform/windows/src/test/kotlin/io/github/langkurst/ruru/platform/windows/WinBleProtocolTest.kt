package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.platform.windows.WinBleProtocol.Answer
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WinBleProtocolTest {
    private val key = "0123456789abcdef"

    @Test
    fun framesRoundTrip() {
        val payload = byteArrayOf(0x00, 0x1F, 0x03, 0x01, 0x00)
        val bytes = WinBleProtocol.encodeFrame('W', payload) + WinBleProtocol.encodeFrame('Q')
        assertContentEquals(byteArrayOf('W'.code.toByte(), 0, 5) + payload, bytes.copyOf(8))
        val input = ByteArrayInputStream(bytes)
        val first = WinBleProtocol.readFrame(input)!!
        assertEquals('W', first.type)
        assertContentEquals(payload, first.payload)
        val second = WinBleProtocol.readFrame(input)!!
        assertEquals('Q', second.type)
        assertEquals(0, second.payload.size)
        assertNull(WinBleProtocol.readFrame(input), "a clean end of stream")
    }

    @Test
    fun longFrameUsesBothLengthBytes() {
        val payload = ByteArray(300) { it.toByte() }
        val frame = WinBleProtocol.encodeFrame('N', payload)
        assertEquals(1, frame[1].toInt())
        assertEquals(44, frame[2].toInt())
        assertContentEquals(payload, WinBleProtocol.readFrame(ByteArrayInputStream(frame))!!.payload)
        assertFailsWith<IllegalArgumentException> { WinBleProtocol.encodeFrame('W', ByteArray(70_000)) }
    }

    @Test
    fun frameCutShortThrows() {
        val frame = WinBleProtocol.encodeFrame('N', byteArrayOf(1, 2, 3, 4))
        assertFailsWith<IOException> { WinBleProtocol.readFrame(ByteArrayInputStream(frame.copyOf(5))) }
        assertFailsWith<IOException> { WinBleProtocol.readFrame(ByteArrayInputStream(frame.copyOf(2))) }
    }

    @Test
    fun writeResults() {
        assertNull(WinBleProtocol.writeFailure(byteArrayOf(0)))
        assertEquals("write refused (protocol error 3)", WinBleProtocol.writeFailure(byteArrayOf(1) + "write refused (protocol error 3)".toByteArray()))
        assertEquals("write refused", WinBleProtocol.writeFailure(byteArrayOf(1)))
        assertEquals("the helper gave no write result", WinBleProtocol.writeFailure(ByteArray(0)))
        assertEquals(247, WinBleProtocol.maxPdu(byteArrayOf(0, 247.toByte())))
        assertEquals(517, WinBleProtocol.maxPdu(byteArrayOf(2, 5)))
        assertNull(WinBleProtocol.maxPdu(byteArrayOf(1)))
    }

    @Test
    fun linesAndFramesShareOneStream() {
        // The OPEN line is followed at once by binary frames; reading the line must leave them alone.
        val frame = WinBleProtocol.encodeFrame('N', byteArrayOf(0x0A, 0x0D, 0x00))
        val input = ByteArrayInputStream("OPEN\t247\t1\r\n".toByteArray() + frame)
        assertEquals(Answer.Open(WinBleProtocol.Opened(247, true)), WinBleProtocol.parse(WinBleProtocol.readLine(input)!!))
        assertContentEquals(byteArrayOf(0x0A, 0x0D, 0x00), WinBleProtocol.readFrame(input)!!.payload)
        assertNull(WinBleProtocol.readLine(input))
    }

    @Test
    fun parsesAnswerLines() {
        assertEquals(
            Answer.Dev(WinBleProtocol.Device(key, true, "Bose QC Ultra Headphones")),
            WinBleProtocol.parse("DEV\t$key\t1\tBose QC Ultra Headphones"),
        )
        assertEquals(Answer.Dev(WinBleProtocol.Device(key, false, "")), WinBleProtocol.parse("DEV\t$key\t0\t"))
        assertEquals(Answer.End(3), WinBleProtocol.parse("END\t3"))
        assertEquals(Answer.Open(WinBleProtocol.Opened(23, false)), WinBleProtocol.parse("OPEN\t23\t0"))
        assertEquals(
            Answer.Err(WinBleProtocol.Failure("not-paired", "the headphones want a paired, encrypted link (protocol error 5)")),
            WinBleProtocol.parse("ERR\tnot-paired\tthe headphones want a paired, encrypted link (protocol error 5)"),
        )
        assertEquals(Answer.Err(WinBleProtocol.Failure("failed", "no reason given")), WinBleProtocol.parse("ERR"))

        val apple = assertIs<Answer.Adv>(WinBleProtocol.parse("ADV\t$key\t004c\t0719010e2001\tAirPods Pro")).advert
        assertEquals(0x004C, apple.companyId)
        assertContentEquals(byteArrayOf(0x07, 0x19, 0x01, 0x0E, 0x20, 0x01), apple.data)
        assertEquals("AirPods Pro", apple.name)
        val nameOnly = assertIs<Answer.Adv>(WinBleProtocol.parse("ADV\t$key\t-\t\tBose QC Ultra Headphones")).advert
        assertNull(nameOnly.companyId)
        assertEquals(0, nameOnly.data.size)
        assertNull(assertIs<Answer.Adv>(WinBleProtocol.parse("ADV\t$key\t009e\t01\t")).advert.name)
    }

    @Test
    fun malformedLinesAreNotTrusted() {
        for (line in listOf(
            "DEV\tAA:BB:CC:DD:EE:FF\t1\tBose", // an address is never a key
            "DEV\t$key\tyes\tBose",
            "ADV\t$key\t4c\t00\tx",
            "ADV\t$key\t004c\t0g\tx",
            "ADV\t$key\t004c\t123\tx",
            "OPEN\t-1\t1",
            "OPEN\t247",
            "END\tmany",
            "hello",
        )) {
            assertIs<Answer.Other>(WinBleProtocol.parse(line), line)
        }
    }

    @Test
    fun gattCommandNamesTheSpecOnly() {
        assertEquals(
            "gatt $key 0000febe-0000-1000-8000-00805f9b34fb c65b8f2f-aee2-4c89-b758-bc4892d6f2d8 c65b8f2f-aee2-4c89-b758-bc4892d6f2d8",
            WinBleProtocol.gattCommand(key, WinBleProtocol.BOSE_LE),
        )
        val jbl = GattLinkSpec("JBL", "65786365-6c70-6f69-6e74-2e636f6d0000", "65786365-6c70-6f69-6e74-2e636f6d0002", listOf("65786365-6c70-6f69-6e74-2e636f6d0001", "65786365-6c70-6f69-6e74-2e636f6d0003"), writeWithResponse = false)
        assertEquals(
            "gatt $key 65786365-6c70-6f69-6e74-2e636f6d0000 65786365-6c70-6f69-6e74-2e636f6d0002 " +
                "65786365-6c70-6f69-6e74-2e636f6d0001 65786365-6c70-6f69-6e74-2e636f6d0003 --without-response",
            WinBleProtocol.gattCommand(key, jbl),
        )
        assertFailsWith<IllegalArgumentException> { WinBleProtocol.gattCommand("aabbccddeeff", WinBleProtocol.BOSE_LE) }
        assertFailsWith<IllegalArgumentException> { WinBleProtocol.gattCommand("$key\nlist", WinBleProtocol.BOSE_LE) }
        // A firmware-update service cannot even be put into a spec, so it never reaches a command.
        assertFailsWith<IllegalArgumentException> {
            GattLinkSpec("OTA", "66666666-0000-1000-8000-00805f9b34fb", "66666666-0000-1000-8000-00805f9b34fc", listOf("66666666-0000-1000-8000-00805f9b34fd"))
        }
    }

    @Test
    fun scanCommandStaysShort() {
        assertEquals("scan 4", WinBleProtocol.scanCommand(4, active = false))
        assertEquals("scan 30 active", WinBleProtocol.scanCommand(600, active = true))
        assertEquals("scan 1", WinBleProtocol.scanCommand(0, active = false))
    }

    @Test
    fun writesAreCutToTheLink() {
        assertEquals(20, WinBleProtocol.writeRoom(23))
        assertEquals(244, WinBleProtocol.writeRoom(247))
        assertEquals(20, WinBleProtocol.writeRoom(5))
        val bytes = ByteArray(45) { it.toByte() }
        val chunks = WinBleProtocol.chunks(bytes, 20)
        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
        assertContentEquals(bytes, chunks.reduce { a, b -> a + b })
        assertTrue(WinBleProtocol.chunks(ByteArray(0), 20).isEmpty())
    }

    @Test
    fun bosesLeSpecIsTheIphoneRoute() {
        val spec = WinBleProtocol.BOSE_LE
        assertEquals("0000febe-0000-1000-8000-00805f9b34fb", spec.service)
        assertEquals(listOf(spec.write), spec.notify)
        assertTrue(spec.writeWithResponse)
    }

    @Test
    fun advertsKeepOnlyManufacturerSectionsAndPrintNoNames() {
        val adverts = listOf(
            WinBleProtocol.Advert(key, 0x004C, byteArrayOf(7, 25), "Someone's AirPods"),
            WinBleProtocol.Advert(key, null, ByteArray(0), "Someone's AirPods"),
            WinBleProtocol.Advert("fedcba9876543210", null, ByteArray(0), "Bose QC Ultra Headphones"),
        )
        val shared = WinAdverts.toHeadsetAdverts(adverts)
        assertEquals(1, shared.size)
        assertEquals(0x004C, shared[0].manufacturerId)
        assertEquals(key, shared[0].key)
        val summary = WinAdverts.summary(adverts)
        assertEquals("2 Bluetooth LE advertisers heard; one named Bose", summary)
        assertTrue("Someone" !in summary)
    }
}
