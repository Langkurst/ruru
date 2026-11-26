package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxHeadsetPlatformTest {
    private val candidate = HeadsetCandidate(HEADSET, "Headphones", true)

    private class Rig {
        val bluez = FakeBluez()
        val channelOpens = mutableListOf<LinkRoute.BoseRfcomm>()
        val platform = LinuxHeadsetPlatform(
            { bluez }, true,
            boseChannels = { _, _, route -> channelOpens += route; throw AssertionError("not reached on a real socket") },
        )
    }

    @Test
    fun offersEveryRoute() {
        assertEquals(
            setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.GATT, RouteKind.BOSE_LE),
            LinuxHeadsetPlatform({ FakeBluez() }, true).routes,
        )
    }

    @Test
    fun anRfcommServiceNeverFallsBackToAChannelNumber() = runBlocking {
        val rig = Rig()
        rig.bluez.connectProfileError = BluezException("org.bluez.Error.NotAvailable", "no such service")
        assertFailsWith<RouteNotOfferedException> { rig.platform.open(candidate, LinkRoute.RfcommService(SVC_UUID)) }
        assertTrue(rig.channelOpens.isEmpty(), "a channel number was tried after the service was missing")
        assertTrue(rig.bluez.writes.isEmpty())
    }

    @Test
    fun aCachedListThatLacksTheServiceNeverReachesTheSocketEither() = runBlocking {
        val rig = Rig()
        rig.bluez.cached = listOf("0000110b-0000-1000-8000-00805f9b34fb")
        assertFailsWith<RouteNotOfferedException> { rig.platform.open(candidate, LinkRoute.RfcommService(SVC_UUID)) }
        assertTrue(rig.channelOpens.isEmpty())
        assertTrue(rig.bluez.calls.none { it.startsWith("registerProfile") || it.startsWith("connectProfile") })
    }

    @Test
    fun onlyBoseRfcommUsesChannelNumbers() = runBlocking {
        val rig = Rig()
        assertFailsWith<AssertionError> { rig.platform.open(candidate, LinkRoute.BoseRfcomm(listOf(2, 8))) }
        assertEquals(listOf(listOf(2, 8)), rig.channelOpens.map { it.channels })
    }

    @Test
    fun theBoseChannelAddressComesOutOfTheDevicePath() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluezPaths.addressOf(HEADSET))
        assertNull(BluezPaths.addressOf("/org/bluez/hci0"))
        assertNull(BluezPaths.addressOf("$HEADSET/service0001"))
        assertEquals(HEADSET, BluezPaths.deviceOf("$HEADSET/service0001/char0002"))
        assertTrue(BluezPaths.isDevicePath(HEADSET))
    }

    @Test
    fun aGattRouteOnAHeadsetWithoutTheServiceIsRefusedNotOpened() = runBlocking {
        val rig = Rig()
        val spec = GattLinkSpec("T", "0000aaaa-0000-1000-8000-00805f9b34fb", "0000aaa1-0000-1000-8000-00805f9b34fb", listOf("0000aaa2-0000-1000-8000-00805f9b34fb"))
        assertFailsWith<RouteNotOfferedException> { rig.platform.open(candidate, LinkRoute.Gatt(spec)) }
        Unit
    }

    @Test
    fun headsetsListPairedAudioDevicesConnectedFirstWithTheirCachedUuids() = runBlocking {
        val rig = Rig()
        val mouse = device("Mouse", path = "/org/bluez/hci0/dev_01_02_03_04_05_06", classOfDevice = 0x2580)
        val notPaired = device("Speaker", path = "/org/bluez/hci0/dev_01_02_03_04_05_07", paired = false)
        val boseOff = device("Bose QC Ultra", path = "/org/bluez/hci0/dev_01_02_03_04_05_08", connected = false, uuids = listOf(SVC_UUID))
        val sonyOn = device("Sony WH", connected = true)
        rig.bluez.deviceList = listOf(mouse, notPaired, boseOff, sonyOn)
        val list = rig.platform.headsets()
        assertEquals(listOf("Sony WH", "Bose QC Ultra"), list.map { it.name })
        assertEquals(listOf(true, false), list.map { it.connected })
        assertEquals(listOf(SVC_UUID), list.last().uuids)
        assertEquals(HeadsetLink.CLASSIC, list.first().link)
        assertTrue(list.none { it.toString().contains("AA:BB") || it.toString().contains("dev_") })
    }

    @Test
    fun anLeOnlyTwinOfAPairedClassicDeviceIsSkipped() {
        val classic = device("Buds", connected = true)
        val twin = device("Buds", path = "/org/bluez/hci0/dev_11_22_33_44_55_66", classOfDevice = null, icon = "audio-headphones")
        val lone = device("LE only", path = "/org/bluez/hci0/dev_11_22_33_44_55_67", classOfDevice = null, icon = "audio-headset")
        val out = BluezListing.candidates(listOf(twin, classic, lone))
        assertEquals(listOf("Buds", "LE only"), out.map { it.name })
        assertEquals(HeadsetLink.CLASSIC, BluezListing.link(out[0]))
        assertEquals(HeadsetLink.LE, BluezListing.link(out[1]))
    }

    @Test
    fun theListingKeyIsTheDevicePathNotAnAddress() = runBlocking {
        val rig = Rig()
        rig.bluez.deviceList = listOf(device("Sony WH"))
        val key = rig.platform.headsets().single().key
        assertEquals(HEADSET, key)
        assertNotEquals("AA:BB:CC:DD:EE:FF", key)
    }

    @Test
    fun advertsComeFromOneShortWindowAndHideAddresses() = runBlocking {
        val rig = Rig()
        rig.bluez.scanResult = listOf(
            device("AA-BB-CC-DD-EE-FF", manufacturerData = mapOf(0x004C to byteArrayOf(0x07, 0x19)), rssi = -48, classOfDevice = null),
            device("Beats Studio", path = "/org/bluez/hci0/dev_11_22_33_44_55_66", manufacturerData = mapOf(0x004C to byteArrayOf(0x07)), classOfDevice = null),
        )
        val adverts = rig.platform.adverts()
        assertEquals(listOf("scan ${BluezAdverts.WINDOW_MS}"), rig.bluez.calls)
        assertEquals(listOf(0x004C, 0x004C), adverts.map { it.manufacturerId })
        assertEquals(-48, adverts[0].rssi)
        assertNull(adverts[0].name, "a name that is only the address is not a name")
        assertEquals("Beats Studio", adverts[1].name)
        assertTrue(adverts.all { it.key.matches(Regex("[0-9a-f]{16}")) })
        assertEquals(BluezAdverts.key(HEADSET), adverts[0].key)
        assertNotEquals(adverts[0].key, adverts[1].key)
        assertTrue(rig.platform.listensForAdverts)
    }

    @Test
    fun anUnsupportedSystemListensForNothing() = runBlocking {
        val bluez = FakeBluez()
        val platform = LinuxHeadsetPlatform({ bluez }, false)
        assertFalse(platform.listensForAdverts)
        assertTrue(platform.adverts().isEmpty())
        assertTrue(bluez.calls.isEmpty())
    }

    @Test
    fun blueZErrorNamesAreFoundWhereverDbusJavaPutsThem() {
        assertEquals("org.bluez.Error.NotAvailable", BluezException.nameFrom("org.bluez.Error.NotAvailable", null))
        assertEquals(
            "org.bluez.Error.DoesNotExist",
            BluezException.nameFrom("org.freedesktop.dbus.exceptions.DBusExecutionException", "org.bluez.Error.DoesNotExist: Profile unavailable"),
        )
        assertEquals("org.bluez.Error.NotAvailable", BluezException.nameFrom("x", "NotAvailable"))
        assertEquals("org.freedesktop.DBus.Error.Failed", BluezException.nameFrom("x", "boom"))
        assertTrue(BluezException("org.bluez.Error.NotAvailable", "m").serviceMissing)
        assertFalse(BluezException("org.bluez.Error.Failed", "m").serviceMissing)
    }
}
