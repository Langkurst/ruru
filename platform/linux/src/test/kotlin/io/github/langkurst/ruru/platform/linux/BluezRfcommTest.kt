package io.github.langkurst.ruru.platform.linux

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

/** RFCOMM by UUID through BlueZ's Profile API, against a pretend BlueZ. */
class BluezRfcommTest {
    private val route = LinkRoute.RfcommService(SVC_UUID)

    private class Rig(val bluez: FakeBluez = FakeBluez(), val port: FakePort = FakePort(), connectTimeoutMs: Long = 2_000) {
        val adopted = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val opener = BluezRfcommOpener(
            bluez, "Linux",
            adopt = { fd -> adopted += fd; port },
            discard = { discarded += it },
            nextProfilePath = { BluezPaths.profilePath(7) },
            connectTimeoutMs = connectTimeoutMs,
        )
    }

    @Test
    fun registersAProfileForTheUuidThenConnectsAndWrapsTheSocket() = runBlocking {
        val rig = Rig()
        val transport = rig.opener.open(HEADSET, route)
        assertEquals(listOf("cachedUuids", "registerProfile $SVC_UUID", "connectProfile $SVC_UUID"), rig.bluez.calls)
        assertEquals("/io/github/langkurst/ruru/profile/p7", rig.bluez.registeredPath)
        assertEquals(listOf(42), rig.adopted)
        assertTrue(transport.isConnected)
        assertTrue(rig.port.written.isEmpty(), "opening sends no byte")

        // What the headset sends comes out of the transport; what Ruru writes goes to the socket.
        rig.port.toRead.add(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(2_000) { transport.incoming.first() })
        transport.write(byteArrayOf(9, 9))
        assertContentEquals(byteArrayOf(9, 9), rig.port.written.single())
    }

    @Test
    fun theProfileHasNoChannelAndIsAClient() {
        val options = ProfileOptions.forUuid(SVC_UUID)
        assertEquals("client", options["Role"])
        assertTrue(options.keys.none { it in ProfileOptions.FORBIDDEN_KEYS }, "no Channel, PSM or service record: BlueZ finds the service by SDP")
        assertEquals(false, options["AutoConnect"])
    }

    @Test
    fun theRegisteredOptionsReachBlueZUnchanged() = runBlocking {
        val rig = Rig()
        rig.opener.open(HEADSET, route)
        assertEquals(ProfileOptions.forUuid(SVC_UUID), rig.bluez.registeredOptions)
    }

    @Test
    fun aCachedServiceListWithoutTheUuidRefusesBeforeAnythingIsRegistered() = runBlocking {
        val rig = Rig()
        rig.bluez.cached = listOf("0000110b-0000-1000-8000-00805f9b34fb")
        assertFailsWith<RouteNotOfferedException> { rig.opener.open(HEADSET, route) }
        assertEquals(listOf("cachedUuids"), rig.bluez.calls)
    }

    @Test
    fun aCachedListWithTheUuidGoesAhead() = runBlocking {
        val rig = Rig()
        rig.bluez.cached = listOf(SVC_UUID)
        rig.opener.open(HEADSET, route)
        assertTrue("connectProfile $SVC_UUID" in rig.bluez.calls)
    }

    @Test
    fun aServiceBlueZCannotFindIsRouteNotOfferedAndTheProfileIsUnregistered() = runBlocking {
        val rig = Rig()
        rig.bluez.connectProfileError = BluezException("org.bluez.Error.NotAvailable", "Operation currently not available")
        assertFailsWith<RouteNotOfferedException> { rig.opener.open(HEADSET, route) }
        assertEquals(1, rig.bluez.unregistered)
        assertTrue(rig.adopted.isEmpty())
        assertTrue(rig.port.written.isEmpty())
    }

    @Test
    fun otherBlueZErrorsStayErrors() = runBlocking {
        val rig = Rig()
        rig.bluez.connectProfileError = BluezException("org.bluez.Error.Failed", "Host is down")
        assertFailsWith<BluezException> { rig.opener.open(HEADSET, route) }
        assertEquals(1, rig.bluez.unregistered)
    }

    @Test
    fun aConnectionFromAnotherDeviceIsClosedNotUsed() = runBlocking {
        val rig = Rig(connectTimeoutMs = 100)
        rig.bluez.fdFromDevice = "/org/bluez/hci0/dev_11_22_33_44_55_66"
        assertFailsWith<IOException> { rig.opener.open(HEADSET, route) }
        assertEquals(listOf(42), rig.discarded)
        assertTrue(rig.adopted.isEmpty())
        assertEquals(1, rig.bluez.unregistered)
    }

    @Test
    fun noSocketInTimeIsAnErrorAndTheProfileGoes() = runBlocking {
        val rig = Rig(connectTimeoutMs = 100)
        rig.bluez.fdOnConnect = null
        assertFailsWith<IOException> { rig.opener.open(HEADSET, route) }
        assertEquals(1, rig.bluez.unregistered)
    }

    @Test
    fun closingTheTransportClosesTheSocketAndUnregistersTheProfileOnce() = runBlocking {
        val rig = Rig()
        val transport = rig.opener.open(HEADSET, route)
        assertEquals(0, rig.bluez.unregistered, "BlueZ drops the link when its profile goes, so it stays while connected")
        transport.close()
        transport.close()
        assertFalse(transport.isConnected)
        assertEquals(1, rig.port.closed)
        assertEquals(1, rig.bluez.unregistered)
        assertFailsWith<IOException> { transport.write(byteArrayOf(1)) }
        Unit
    }

    @Test
    fun blueZAskingToDisconnectClosesTheTransport() = runBlocking {
        val rig = Rig()
        val transport = rig.opener.open(HEADSET, route)
        rig.bluez.bluezDisconnects("/org/bluez/hci0/dev_11_22_33_44_55_66")
        assertTrue(transport.isConnected, "another device's request is not ours")
        rig.bluez.bluezDisconnects()
        assertFalse(transport.isConnected)
        assertEquals(1, rig.port.closed)
    }

    @Test
    fun endOfStreamEndsIncomingWithAnError() = runBlocking {
        val rig = Rig()
        val transport = rig.opener.open(HEADSET, route)
        rig.port.endOfStream = true
        assertFailsWith<IOException> { withTimeout(2_000) { transport.incoming.first() } }
        assertFalse(transport.isConnected)
        assertEquals(1, rig.bluez.unregistered)
    }
}
