package io.github.langkurst.ruru.platform.macos

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import io.github.langkurst.ruru.platform.macos.MacBtProtocol.Answer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacBtProtocolTest {
    private val key = "0123456789abcdef"
    private val sony = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    @Test
    fun keysAreSixteenHexCharactersNeverAnAddress() {
        assertTrue(MacBtProtocol.isKey(key))
        assertFalse(MacBtProtocol.isKey("aa-bb-cc-dd-ee-ff"))
        assertFalse(MacBtProtocol.isKey("AA:BB:CC:DD:EE:FF"))
        assertFalse(MacBtProtocol.isKey("0123456789ABCDEF"))
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.connectUuidArgs("aa-bb-cc-dd-ee-ff", sony) }
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.gattArgs("aa-bb-cc-dd-ee-ff", MacBtProtocol.BOSE_LE) }
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.connectArgs("aa-bb-cc-dd-ee-ff", 2) }
    }

    @Test
    fun commandLines() {
        assertEquals(listOf("list"), MacBtProtocol.listArgs())
        assertEquals(listOf("connect", key, "2"), MacBtProtocol.connectArgs(key, 2))
        assertEquals(listOf("connect-uuid", key, sony), MacBtProtocol.connectUuidArgs(key, sony))
        // A channel number is never what connect-uuid is given, and the UUID must be lower-case 128-bit.
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.connectUuidArgs(key, "1101") }
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.connectUuidArgs(key, sony.uppercase()) }
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.connectArgs(key, 0) }
        val bose = MacBtProtocol.BOSE_LE
        assertEquals(listOf("gatt", key, bose.service, bose.write, bose.notify[0]), MacBtProtocol.gattArgs(key, bose))
        val jbl = GattLinkSpec("JBL", sony, "11111111-0000-1000-8000-00805f9b34fb", listOf("22222222-0000-1000-8000-00805f9b34fb"), writeWithResponse = false)
        assertEquals("--without-response", MacBtProtocol.gattArgs(key, jbl).last())
    }

    @Test
    fun deviceLines() {
        val line = "DEV\t$key\t1\tclassic\t240404\t0000110b-0000-1000-8000-00805f9b34fb,$sony\tBose QC Ultra Headphones"
        val d = (MacBtProtocol.parse(line) as Answer.Dev).device
        assertEquals(key, d.key)
        assertTrue(d.connected)
        assertEquals(HeadsetLink.CLASSIC, d.link)
        assertEquals(0x240404, d.classOfDevice)
        assertEquals(listOf("0000110b-0000-1000-8000-00805f9b34fb", sony), d.uuids)
        assertEquals("Bose QC Ultra Headphones", d.name)

        val le = (MacBtProtocol.parse("DEV\t$key\t0\tle\t0\t\tBose LE") as Answer.Dev).device
        assertEquals(HeadsetLink.LE, le.link)
        assertEquals(emptyList(), le.uuids)
        assertFalse(le.connected)

        assertEquals(Answer.End(2), MacBtProtocol.parse("END\t2"))
        // An address in the key position, a bad link word or a short line is not a device.
        assertTrue(MacBtProtocol.parse("DEV\taa-bb-cc-dd-ee-ff\t1\tclassic\t0\t\tX") is Answer.Other)
        assertTrue(MacBtProtocol.parse("DEV\t$key\t1\tusb\t0\t\tX") is Answer.Other)
        assertTrue(MacBtProtocol.parse("DEV\t$key\t1\tclassic") is Answer.Other)
        // Junk in the UUID column is dropped, not trusted.
        val junk = (MacBtProtocol.parse("DEV\t$key\t1\tclassic\t0\tnot-a-uuid,$sony\tX") as Answer.Dev).device
        assertEquals(listOf(sony), junk.uuids)
    }

    @Test
    fun openAndErrorLines() {
        assertEquals(Answer.Open(MacBtProtocol.Opened(247, true)), MacBtProtocol.parse("OPEN\t247\t1"))
        assertTrue(MacBtProtocol.parse("OPEN\t0\t1") is Answer.Other)
        assertTrue(MacBtProtocol.parse("OPEN\t23\tmaybe") is Answer.Other)
        assertEquals(
            Answer.Err(MacBtProtocol.Failure("service-not-found", "the headphones do not offer that service")),
            MacBtProtocol.parse("ERR\tservice-not-found\tthe headphones do not offer that service"),
        )
        assertEquals(Answer.Err(MacBtProtocol.Failure("failed", "no reason given")), MacBtProtocol.parse("ERR"))
    }

    @Test
    fun rfcommStatusLines() {
        assertEquals(2, ((MacBtProtocol.parseRfcommStatus("OPEN 2")) as Answer.Open).opened.maxPdu)
        val err = MacBtProtocol.parseRfcommStatus("ERR\tservice-not-found\tnope") as Answer.Err
        assertEquals(MacBtProtocol.SERVICE_NOT_FOUND, err.failure.code)
        assertTrue(MacBtProtocol.parseRfcommStatus("CLOSED") is Answer.Other)
        assertEquals("nope (service-not-found)", MacBtException(err.failure).message)
    }

    @Test
    fun framesRoundTripAndShortOnesThrow() {
        val bytes = MacBtProtocol.encodeFrame('W', byteArrayOf(1, 2, 3)) + MacBtProtocol.encodeFrame('Q')
        val input = ByteArrayInputStream(bytes)
        val first = MacBtProtocol.readFrame(input)!!
        assertEquals('W', first.type)
        assertContentEquals(byteArrayOf(1, 2, 3), first.payload)
        assertEquals('Q', MacBtProtocol.readFrame(input)!!.type)
        assertNull(MacBtProtocol.readFrame(input))
        assertFailsWith<IOException> { MacBtProtocol.readFrame(ByteArrayInputStream(byteArrayOf('N'.code.toByte(), 0, 5, 1))) }
        assertContentEquals(byteArrayOf(0x4B, 0, 1, 0), MacBtProtocol.encodeFrame('K', byteArrayOf(0)))
        assertFailsWith<IllegalArgumentException> { MacBtProtocol.encodeFrame('W', ByteArray(MacBtProtocol.MAX_PAYLOAD + 1)) }
    }

    @Test
    fun writeResultsAndChunking() {
        assertNull(MacBtProtocol.writeFailure(byteArrayOf(0)))
        assertEquals("busy", MacBtProtocol.writeFailure(byteArrayOf(1) + "busy".toByteArray()))
        assertEquals("write refused", MacBtProtocol.writeFailure(byteArrayOf(1)))
        assertEquals(20, MacBtProtocol.writeRoom(23))
        assertEquals(20, MacBtProtocol.writeRoom(5))
        assertEquals(244, MacBtProtocol.writeRoom(247))
        val pieces = MacBtProtocol.chunks(ByteArray(45) { it.toByte() }, 20)
        assertEquals(listOf(20, 20, 5), pieces.map { it.size })
        assertEquals(emptyList(), MacBtProtocol.chunks(ByteArray(0), 20))
    }

    @Test
    fun readLineKeepsFramesInTheStream() {
        val input = ByteArrayInputStream("OPEN\t23\t1\r\n".toByteArray() + MacBtProtocol.encodeFrame('N', byteArrayOf(9)))
        assertEquals("OPEN\t23\t1", MacBtProtocol.readLine(input))
        assertEquals('N', MacBtProtocol.readFrame(input)!!.type)
    }

    @Test
    fun whichPairedDevicesAreOffered() {
        val headphones = 0x240404 // audio/video major class
        val phone = 0x5A020C
        assertTrue(MacHeadsetPlatform.offers("Sony WH-1000XM5", headphones))
        assertTrue(MacHeadsetPlatform.offers("Bose QC Ultra Headphones", 0)) // LE-only: no class, the name decides
        assertFalse(MacHeadsetPlatform.offers("Someone's iPhone", phone))
        assertFalse(MacHeadsetPlatform.offers("Magic Mouse", 0x002580))
    }

    @Test
    fun routesAndRefusalsBeforeAnyHelperStarts(): Unit = runBlocking {
        val platform = MacHeadsetPlatform(helper = null)
        assertEquals(setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.GATT, RouteKind.BOSE_LE), platform.routes)
        assertFalse(platform.supported)
        assertFalse(platform.listensForAdverts, "macOS hides Apple's adverts")
        assertEquals(emptyList(), platform.adverts())
        assertEquals(emptyList(), platform.headsets())
        val c = HeadsetCandidate(key = key, name = "Sony WH-1000XM5", connected = true)
        assertFailsWith<RouteNotOfferedException> { platform.open(c, LinkRoute.RfcommService(sony)) }
        assertFailsWith<RouteNotOfferedException> { platform.open(c, LinkRoute.BoseLe) }
        assertFailsWith<RouteNotOfferedException> { platform.open(c, LinkRoute.Gatt(MacBtProtocol.BOSE_LE)) }
        assertFailsWith<IOException> { platform.open(c, LinkRoute.BoseRfcomm(listOf(2))) }
    }
}
