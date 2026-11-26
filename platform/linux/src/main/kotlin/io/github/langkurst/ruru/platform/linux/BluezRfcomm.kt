package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Opens one RFCOMM service by its UUID through BlueZ's Profile API, never by a channel number:
 *
 * 1. If BlueZ has cached the headset's service list and the UUID isn't in it, the route is refused
 *    before anything is registered or connected.
 * 2. Ruru registers a client Profile1 for the UUID ([ProfileOptions]: no channel).
 * 3. Device1.ConnectProfile(uuid) makes BlueZ query SDP and connect. A device without the service
 *    answers NotAvailable (or similar): that is [RouteNotOfferedException], nothing was written.
 * 4. BlueZ calls Profile1.NewConnection with the connected socket; the socket becomes the
 *    transport. The profile stays registered until the transport closes, because BlueZ drops the
 *    connection when its profile goes.
 *
 * No byte is written here: the vendor's own code sends the first frame.
 */
internal class BluezRfcommOpener(
    private val bluez: Bluez,
    private val platformName: String,
    private val adopt: (Int) -> RfcommPort = RfcommSocket::adopt,
    private val discard: (Int) -> Unit = RfcommSocket::closeFd,
    private val nextProfilePath: () -> String,
    private val connectTimeoutMs: Long = 15_000,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun open(devicePath: String, route: LinkRoute.RfcommService): ByteTransport {
        val uuid = route.uuid
        val cached = bluez.cachedUuids(devicePath)
        if (cached.isNotEmpty() && uuid !in cached) throw RouteNotOfferedException(platformName, route)

        val arrived = CompletableDeferred<Int>()
        var transport: LinuxRfcommTransport? = null
        val wanted = devicePath
        val registration = bluez.registerProfile(nextProfilePath(), uuid, ProfileOptions.forUuid(uuid), object : ProfileHandler {
            override fun newConnection(devicePath: String, fd: Int) {
                if (devicePath != wanted || !arrived.complete(fd)) discard(fd)
            }

            override fun requestDisconnection(devicePath: String) {
                if (devicePath == wanted) transport?.close()
            }
        })
        try {
            try {
                bluez.connectProfile(devicePath, uuid)
            } catch (e: BluezException) {
                if (e.serviceMissing) throw RouteNotOfferedException(platformName, route).apply { initCause(e) }
                throw e
            }
            val fd = withTimeoutOrNull(connectTimeoutMs) { arrived.await() }
                ?: throw IOException("The headphones did not hand over the connection in time")
            return LinuxRfcommTransport(adopt(fd), onClose = { registration.close() }).also { transport = it }
        } catch (e: Throwable) {
            runCatching { registration.close() }
            // A socket that arrived just as we gave up is closed, not leaked.
            if (arrived.isCompleted) runCatching { discard(arrived.getCompleted()) }
            throw e
        }
    }
}
