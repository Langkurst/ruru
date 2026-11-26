package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Linux (BlueZ), through its D-Bus API (dbus-java):
 *
 * - paired audio devices from the object manager, with the service UUIDs BlueZ cached for them;
 * - Bose over plain RFCOMM channels, as in 0.1 and 0.2 (a libc socket; Bose puts BMAP on channel 2
 *   on most models and 8 or 9 on a few, each checked with a harmless read);
 * - other makers' RFCOMM services by UUID: a client Profile1 is registered for the UUID and
 *   Device1.ConnectProfile makes BlueZ look the service up by SDP and hand over the socket. There
 *   is no channel number anywhere, and a headset without the service is refused before any byte;
 * - GATT links (JBL, Bose over LE) through GattCharacteristic1 WriteValue and StartNotify, only on
 *   the service the vendor's spec names (firmware-update services are never touched);
 * - LE adverts (AirPods and Beats battery) in short discovery windows.
 *
 * Bluetooth addresses stay in here: a candidate's key is a D-Bus object path that only this
 * module reads. Not yet tested on real hardware.
 */
class LinuxHeadsetPlatform internal constructor(
    bluezSource: () -> Bluez,
    override val supported: Boolean,
    /** Opens Bose's plain RFCOMM channels by number (Bose only; a seam so tests can see it is never used for other routes). */
    private val boseChannels: (String, HeadsetCandidate, LinkRoute.BoseRfcomm) -> ByteTransport = ::openBoseChannels,
) : HeadsetPlatform {
    constructor() : this({ DbusBluez() }, systemBusPresent())

    private val bluez: Bluez by lazy(bluezSource)
    private val profileCounter = AtomicInteger()
    private val rfcomm by lazy {
        BluezRfcommOpener(bluez, name, nextProfilePath = { BluezPaths.profilePath(profileCounter.incrementAndGet()) })
    }
    private val gatt by lazy { BluezGattOpener(bluez, name) }

    override val name: String = "Linux"

    override val routes: Set<RouteKind> =
        setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.GATT, RouteKind.BOSE_LE)

    override val listensForAdverts: Boolean get() = supported

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        BluezListing.candidates(bluez.devices()).map {
            HeadsetCandidate(key = it.path, name = it.name, connected = it.connected, uuids = it.uuids, link = BluezListing.link(it))
        }
    }

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport = withContext(Dispatchers.IO) {
        when (route) {
            is LinkRoute.BoseRfcomm -> boseChannels(name, candidate, route)
            is LinkRoute.RfcommService -> rfcomm.open(candidate.key, route)
            is LinkRoute.Gatt -> gatt.open(candidate.key, route, route.spec).also { it.start() }
            is LinkRoute.BoseLe -> gatt.boseLeOver(gatt.open(candidate.key, route, BOSE_LE_SPEC))
        }
    }

    override suspend fun adverts(): List<HeadsetAdvert> = withContext(Dispatchers.IO) {
        if (!supported) return@withContext emptyList()
        try {
            BluezAdverts.toAdverts(bluez.scan(BluezAdverts.WINDOW_MS))
        } catch (e: IOException) {
            emptyList()
        }
    }

    private companion object {
        /** Linux with a system D-Bus to talk to BlueZ on (BlueZ itself shows up as an error later if it is missing). */
        fun systemBusPresent(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Linux") &&
                (
                    System.getenv("DBUS_SYSTEM_BUS_ADDRESS") != null ||
                        File("/run/dbus/system_bus_socket").exists() ||
                        File("/var/run/dbus/system_bus_socket").exists()
                    )
    }
}

/** Bose's channels in order, each checked with the harmless `[0.1]` read; the address comes out of the device path. */
internal fun openBoseChannels(platformName: String, candidate: HeadsetCandidate, route: LinkRoute.BoseRfcomm): ByteTransport {
    val address = BluezPaths.addressOf(candidate.key) ?: throw RouteNotOfferedException(platformName, route)
    var last: Exception? = null
    for (channel in route.channels) {
        try {
            val socket = RfcommSocket.connect(address, channel)
            if (socket.answersBmap()) return LinuxRfcommTransport(socket)
            socket.close()
        } catch (e: IOException) {
            last = e
        }
    }
    throw IOException("No control channel answered on ${candidate.name}", last)
}
