package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GATT through BlueZ, against a pretend BlueZ. */
class BluezGattTest {
    private val svc = "0000aaaa-0000-1000-8000-00805f9b34fb"
    private val writeId = "0000aaa1-0000-1000-8000-00805f9b34fb"
    private val notifyId = "0000aaa2-0000-1000-8000-00805f9b34fb"
    private val spec = GattLinkSpec("Test", svc, writeId, listOf(notifyId), writeWithResponse = true, mtu = 512)
    private val route = LinkRoute.Gatt(spec)
    private val writePath = "$HEADSET/service0001/char0001"
    private val notifyPath = "$HEADSET/service0001/char0002"
    private val firmwarePath = "$HEADSET/service0009/char0009"
    private val bosePath = "$HEADSET/service0002/char0003"

    private fun bluez(mtu: Int? = null) = FakeBluez().apply {
        this.mtu = mtu
        characteristicList = listOf(
            characteristic(svc, writeId, "write", "write-without-response", path = writePath),
            characteristic(svc, notifyId, "notify", path = notifyPath),
            characteristic(FIRMWARE_SERVICE, "66666666-0000-1000-8000-00805f9b34fc", "write", "notify", path = firmwarePath),
        )
    }

    private fun open(b: FakeBluez): BluezGattTransport = BluezGattOpener(b, "Linux").open(HEADSET, route, spec).also { it.start() }

    @Test
    fun maxWriteFollowsTheGrantedMtuAndTheSpecsCap() {
        assertEquals(20, BluezGattRules.maxWrite(null, spec))
        assertEquals(20, BluezGattRules.maxWrite(23, spec))
        assertEquals(244, BluezGattRules.maxWrite(247, spec))
        assertEquals(509, BluezGattRules.maxWrite(517, spec))
        assertEquals(100, BluezGattRules.maxWrite(517, GattLinkSpec("Small", svc, writeId, listOf(notifyId), mtu = 103)))
    }

    @Test
    fun chunksCutAtTheLimitAndKeepOrder() {
        val bytes = ByteArray(45) { it.toByte() }
        val pieces = BluezGattRules.chunks(bytes, 20)
        assertEquals(listOf(20, 20, 5), pieces.map { it.size })
        assertContentEquals(bytes, pieces.reduce { a, b -> a + b })
        assertEquals(1, BluezGattRules.chunks(ByteArray(0), 20).size)
        assertEquals(1, BluezGattRules.chunks(ByteArray(20), 20).size)
    }

    @Test
    fun writeModeFollowsTheSpecWhenTheCharacteristicAllowsIt() {
        assertTrue(BluezGattRules.withResponse(true, setOf("write", "write-without-response")))
        assertFalse(BluezGattRules.withResponse(false, setOf("write", "write-without-response")))
        assertFalse(BluezGattRules.withResponse(true, setOf("write-without-response")))
        assertTrue(BluezGattRules.withResponse(false, setOf("write")))
    }

    @Test
    fun aLongWriteGoesOutInOrderedChunksOnTheWriteCharacteristic() = runBlocking {
        val b = bluez(mtu = 23)
        val transport = open(b)
        val bytes = ByteArray(45) { it.toByte() }
        transport.write(bytes)
        assertEquals(listOf(20, 20, 5), b.writes.map { it.second.size })
        assertTrue(b.writes.all { it.first == writePath && it.third })
        assertContentEquals(bytes, b.writes.map { it.second }.reduce { a, c -> a + c })
    }

    @Test
    fun aRefusedWriteIsAnIoError() = runBlocking {
        val b = bluez()
        val transport = open(b)
        b.writeError = IOException("not permitted")
        assertFailsWith<IOException> { transport.write(byteArrayOf(1)) }
        Unit
    }

    @Test
    fun notificationsArriveInOrderOnIncoming() = runBlocking {
        val b = bluez()
        val transport = open(b)
        b.notifyHandlers.getValue(notifyPath)(byteArrayOf(1))
        b.notifyHandlers.getValue(notifyPath)(byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), withTimeout(2_000) { transport.incoming.first() })
    }

    @Test
    fun onlyTheSpecsServiceIsTouchedNeverTheFirmwareOne() = runBlocking {
        val b = bluez()
        val transport = open(b)
        transport.write(byteArrayOf(1))
        transport.close()
        assertTrue(b.calls.none { it.contains(firmwarePath) }, "firmware characteristic was touched: ${b.calls}")
        assertEquals(listOf(notifyPath), b.notifyHandlers.keys.toList())
    }

    @Test
    fun aServiceTheHeadsetLacksIsRouteNotOfferedBeforeAnyWriteOrSubscription() {
        val b = bluez()
        b.characteristicList = b.characteristicList.filter { it.serviceUuid != svc }
        assertFailsWith<RouteNotOfferedException> { BluezGattOpener(b, "Linux").open(HEADSET, route, spec) }
        assertTrue(b.calls.none { it.startsWith("write") || it.startsWith("startNotify") })
    }

    @Test
    fun noNotifyingCharacteristicIsRouteNotOffered() {
        val b = bluez()
        b.characteristicList = b.characteristicList.map { if (it.uuid == notifyId) characteristic(svc, notifyId, "read", path = notifyPath) else it }
        assertFailsWith<RouteNotOfferedException> { BluezGattOpener(b, "Linux").open(HEADSET, route, spec) }
    }

    @Test
    fun aMissingWriteCharacteristicIsRouteNotOffered() {
        val b = bluez()
        b.characteristicList = b.characteristicList.filter { it.uuid != writeId }
        assertFailsWith<RouteNotOfferedException> { BluezGattOpener(b, "Linux").open(HEADSET, route, spec) }
    }

    @Test
    fun theLinkDroppingEndsIncomingAndStopsNotifications() = runBlocking {
        val b = bluez()
        val transport = open(b)
        var told: String? = null
        transport.onDrop = { told = it }
        b.disconnectHandler!!.invoke()
        assertFalse(transport.isConnected)
        assertEquals(listOf(notifyPath), b.stoppedNotify)
        assertTrue(told != null)
        assertFailsWith<IOException> { withTimeout(2_000) { transport.incoming.first() } }
        assertFailsWith<IOException> { transport.write(byteArrayOf(1)) }
        Unit
    }

    @Test
    fun closingStopsNotificationsOnceAndNeverDisconnectsTheDevice() = runBlocking {
        val b = bluez()
        val transport = open(b)
        transport.close()
        transport.close()
        assertEquals(listOf(notifyPath), b.stoppedNotify)
        assertTrue(b.calls.none { it.contains("disconnect", ignoreCase = true) && !it.startsWith("watch") })
    }

    // Bose over LE: the same pipe with Bose's one-byte piece header.

    private fun boseBluez(mtu: Int?) = FakeBluez().apply {
        this.mtu = mtu
        characteristicList = listOf(characteristic(BOSE_SERVICE, BOSE_CHAR, "write", "notify", path = bosePath))
    }

    @Test
    fun boseLeReassemblesNotificationPiecesIntoOnePacket() = runBlocking {
        val b = boseBluez(mtu = 23)
        val gatt = BluezGattOpener(b, "Linux")
        val le = gatt.boseLeOver(gatt.open(HEADSET, LinkRoute.BoseLe, BOSE_LE_SPEC))
        val handler = b.notifyHandlers.getValue(bosePath)
        handler(byteArrayOf(0x10, 1, 2)) // piece 0 of 2
        handler(byteArrayOf(0x11, 3, 4)) // piece 1 of 2
        assertContentEquals(byteArrayOf(1, 2, 3, 4), withTimeout(2_000) { le.incoming.first() })
    }

    @Test
    fun boseLeSplitsALongPacketIntoHeadedPiecesThatFitTheLink() = runBlocking {
        val b = boseBluez(mtu = 23)
        val gatt = BluezGattOpener(b, "Linux")
        val le = gatt.boseLeOver(gatt.open(HEADSET, LinkRoute.BoseLe, BOSE_LE_SPEC))
        le.write(ByteArray(30) { it.toByte() })
        assertEquals(listOf(20, 12), b.writes.map { it.second.size })
        assertEquals(0x10, b.writes[0].second[0].toInt())
        assertEquals(0x11, b.writes[1].second[0].toInt())
        assertTrue(b.writes.all { it.third }, "Bose LE writes ask for a response")
    }

    @Test
    fun aHeadsetWithoutBosesLeServiceIsRouteNotOffered() {
        val b = bluez()
        assertFailsWith<RouteNotOfferedException> { BluezGattOpener(b, "Linux").open(HEADSET, LinkRoute.BoseLe, BOSE_LE_SPEC) }
    }
}
