package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.VendorRegistry
import io.github.langkurst.ruru.vendors.sony.SonyVendor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WinRfcommServiceTest {
    private val sony = SonyVendor.SERVICE_V2
    private val spp = "00001101-0000-1000-8000-00805f9b34fb"

    @Test
    fun guidIsWindowsMixedEndian() {
        val b = WinGuid.toBytes(spp)
        assertContentEquals(
            byteArrayOf(0x01, 0x11, 0, 0, 0, 0, 0, 0x10, 0x80.toByte(), 0, 0, 0x80.toByte(), 0x5F, 0x9B.toByte(), 0x34, 0xFB.toByte()),
            b,
        )
        assertEquals(spp, WinGuid.fromBytes(b))
        assertEquals(sony, WinGuid.fromBytes(WinGuid.toBytes(sony)))
        assertFailsWith<IllegalArgumentException> { WinGuid.toBytes(spp.uppercase()) }
        assertFailsWith<IllegalArgumentException> { WinGuid.toBytes("1101") }
    }

    @Test
    fun sockaddrHasServiceIdAndPortZero() {
        val sa = WinSockaddr.build(0x0102030405L, sony)
        assertEquals(30, sa.size)
        val buf = ByteBuffer.wrap(sa).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32, buf.getShort(0).toInt())
        assertEquals(0x0102030405L, buf.getLong(2))
        assertEquals(sony, WinSockaddr.serviceOf(sa))
        assertEquals(0, WinSockaddr.portOf(sa))
    }

    @Test
    fun serviceListParsingDropsNilAndRepeats() {
        val a2dp = "0000110b-0000-1000-8000-00805f9b34fb"
        val mem = WinGuid.toBytes(spp) + WinGuid.toBytes(sony) + WinGuid.toBytes(spp) + ByteArray(16) + WinGuid.toBytes(a2dp)
        assertEquals(listOf(spp, sony), WinGuid.parseList(mem, 3))
        assertEquals(listOf(spp, sony, a2dp), WinGuid.parseList(mem, 5))
        assertEquals(emptyList(), WinGuid.parseList(mem, 0))
        assertEquals(listOf(spp), WinGuid.parseList(mem, 1)) // never reads past the count
        assertEquals(listOf(spp, sony, a2dp), WinGuid.parseList(mem, 99)) // a count past the buffer is clamped
    }

    @Test
    fun missingServiceIsOneAttemptOnPortZeroAndThrowsBeforeAnyByte() {
        val attempts = ArrayList<ByteArray>()
        val e = assertFailsWith<ServiceNotFoundException> {
            WinRfcommConnect.connect(7L, sony, classifyMissing = true, describe = { "x$it" }) { sa -> attempts += sa; 10108 }
        }
        assertEquals(10108, e.code)
        // Mutation check: a fallback to a channel number would make a second attempt or a non-zero port.
        assertEquals(1, attempts.size)
        assertEquals(0, WinSockaddr.portOf(attempts.single()))
        assertEquals(sony, WinSockaddr.serviceOf(attempts.single()))
        for (code in listOf(10051, 10049)) {
            assertFailsWith<ServiceNotFoundException> { WinRfcommConnect.connect(7L, sony, true, { "x" }) { code } }
        }
    }

    @Test
    fun otherFailuresAreNotServiceNotFound() {
        for (code in listOf(10060, 10061, 10064, 10065)) {
            val e = assertFailsWith<IOException> { WinRfcommConnect.connect(7L, sony, true, { "x$it" }) { code } }
            assertFalse(e is ServiceNotFoundException, "$code")
            assertTrue(e.message!!.endsWith("x$code"))
        }
        // Bose's Serial Port keeps its old messages: no classification there.
        val bose = assertFailsWith<IOException> { WinRfcommConnect.connect(7L, WinBluetooth.SPP_UUID, false, { "x$it" }) { 10051 } }
        assertFalse(bose is ServiceNotFoundException)
        WinRfcommConnect.connect(7L, sony, true, { "x" }) { 0 } // success returns quietly
    }

    @Test
    fun routesAlwaysHaveRfcommServiceAndGattOnlyWithTheHelper() {
        val without = WindowsHeadsetPlatform(ble = null).routes
        assertTrue(RouteKind.RFCOMM_SERVICE in without && RouteKind.BOSE_RFCOMM in without)
        assertFalse(RouteKind.GATT in without || RouteKind.BOSE_LE in without)
        val with = WindowsHeadsetPlatform(ble = WinBleHelper(File("ruru-ble-win.exe"))).routes
        assertEquals(setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.BOSE_LE, RouteKind.GATT), with)
    }

    private fun routeFor(name: String, uuids: List<String>, routes: Set<RouteKind>): LinkRoute? {
        val family = DeviceFamily.resolve(null, null, name, uuids)
        return VendorRegistry.forFamily(family)?.route(HeadsetCandidate("k", name, true, uuids), routes)
    }

    @Test
    fun routeSelectionPerFamilyOnWindows() {
        val routes = WindowsHeadsetPlatform(ble = WinBleHelper(File("x.exe"))).routes
        assertTrue(routeFor("Bose QC Ultra Headphones", emptyList(), routes) is LinkRoute.BoseRfcomm)
        assertEquals(LinkRoute.RfcommService(sony), routeFor("Sony WH-1000XM5", emptyList(), routes))
        assertEquals(LinkRoute.RfcommService(sony), routeFor("Sony WH-1000XM5", listOf(sony), routes))
        // MDR v1 only: refused before any socket.
        assertNull(routeFor("Sony WH-1000XM5", listOf(SonyVendor.SERVICE_V1), routes))
        assertTrue(routeFor("JBL Wave Buds 2", emptyList(), routes) is LinkRoute.Gatt)
        assertTrue(routeFor("Sennheiser Momentum 4", emptyList(), routes) is LinkRoute.RfcommService)
        // Without the helper JBL has no route at all.
        assertNull(routeFor("JBL Wave Buds 2", emptyList(), WindowsHeadsetPlatform(ble = null).routes))
        // An unknown device has no vendor route: never sent a byte.
        assertNull(routeFor("Some Speaker", emptyList(), routes))
    }

    @Test
    fun listsAudioDevicesAndBoseButNotTwinsOrOtherClasses() {
        val audio = 0x240404 // major class 4
        val peripheral = 0x002580 // major class 5 (mouse)
        assertTrue(WindowsHeadsetPlatform.offers("Sony WH-1000XM5", audio))
        assertTrue(WindowsHeadsetPlatform.offers("Bose QC45", 0)) // Ruru 0.2's rule: any Bose name
        assertFalse(WindowsHeadsetPlatform.offers("Logitech MX Master", peripheral))
        assertFalse(WindowsHeadsetPlatform.offers("LE-Bose QC Ultra", audio))
        assertFalse(WindowsHeadsetPlatform.offers("LE_WH-1000XM5", audio))
    }

    @Test
    fun gattKeyComesFromTheLeEntryOfTheSameName() {
        val k1 = "a".repeat(16)
        val devices = listOf(
            WinBleProtocol.Device(k1, false, "LE-JBL Tour One M2"),
            WinBleProtocol.Device("b".repeat(16), true, "Other"),
        )
        assertEquals(k1, WindowsHeadsetPlatform.matchLe(devices, "JBL Tour One M2")?.key)
        assertNull(WindowsHeadsetPlatform.matchLe(devices, "JBL Live 660"))
    }
}
