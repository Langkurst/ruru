package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Windows: the paired audio devices, each with the service UUIDs Windows has cached for it (so the
 * maker's rules can tell a Sony v1 or a Soundcore model apart). Bose opens on its Serial Port
 * channel; Sony, Sennheiser, Soundcore and Skullcandy open an RFCOMM service by its UUID (port 0,
 * Windows does the SDP lookup, never a channel number; a service the headset lacks is refused
 * before any byte). Whether a device is driven at all is decided above this, by its maker's rules:
 * an unknown device is never sent a byte.
 *
 * With the Bluetooth LE helper (`ruru-ble-win.exe`, bundled with the app) it can also open GATT
 * links ([LinkRoute.Gatt]) and Bose over LE ([LinkRoute.BoseLe]). RFCOMM stays the default: Bose
 * goes over LE only when [boseOverLe] is set (the probe's and hardware check's `--le`, or the
 * internal setting `windows.boseOverLe`, off by default); the headsets are then the Bose devices
 * paired with Windows over LE.
 */
class WindowsHeadsetPlatform(
    private val boseOverLe: Boolean = false,
    /** Null when the helper is missing from this build: then only RFCOMM. */
    val ble: WinBleHelper? = WinBleHelper.find(),
) : HeadsetPlatform {
    override val name: String = "Windows"

    override val supported: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Bose over the Serial Port service and the other makers' services by UUID; GATT and Bose LE through the helper. */
    override val routes: Set<RouteKind> =
        if (ble != null) setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.BOSE_LE, RouteKind.GATT)
        else setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE)

    private val leRetry = LeOpenRetry(log = { LogRing.add('I', "Win-LE", it) })

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        if (boseOverLe) return@withContext leHeadsets()
        WinBluetooth.pairedDevices()
            .filter { offers(it.name, it.classOfDevice) }
            .sortedByDescending { it.connected }
            .map { HeadsetCandidate(key = it.address.toString(16), name = it.name, connected = it.connected, uuids = WinBluetooth.cachedServices(it)) }
    }

    /** Bose devices paired with Windows over Bluetooth LE, connected first, the plain name before its "LE-" twin. */
    private fun leHeadsets(): List<HeadsetCandidate> {
        val helper = ble ?: return emptyList()
        return orderLe(helper.list().filter { it.name.contains("Bose", ignoreCase = true) })
            .map { HeadsetCandidate(key = it.key, name = it.name.removePrefix("LE-"), connected = it.connected, link = HeadsetLink.LE) }
    }

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport = withContext(Dispatchers.IO) {
        when (route) {
            is LinkRoute.BoseRfcomm -> {
                val device = WinBluetooth.pairedDevices().firstOrNull { it.address.toString(16) == candidate.key }
                    ?: throw IOException("${candidate.name} is no longer paired with this computer")
                WinRfcommTransport.open(device)
            }
            is LinkRoute.RfcommService -> {
                val device = WinBluetooth.pairedDevices().firstOrNull { it.address.toString(16) == candidate.key }
                    ?: throw IOException("${candidate.name} is no longer paired with this computer")
                try {
                    WinRfcommTransport(WinBluetooth.openService(device, route.uuid, readTimeoutMs = WinRfcommTransport.READ_POLL_MS))
                } catch (e: ServiceNotFoundException) {
                    // Nothing was sent: the connect never completed.
                    throw RouteNotOfferedException(name, route).apply { initCause(e) }
                }
            }
            is LinkRoute.BoseLe -> {
                val helper = helperFor(route)
                val key = leKey(candidate)
                leRetry.run { openBoseLe(helper, key) }
            }
            is LinkRoute.Gatt -> {
                val helper = helperFor(route)
                helper.openGatt(gattKey(helper, candidate), route.spec).also { it.start() }
            }
            else -> throw RouteNotOfferedException(name, route)
        }
    }

    override val listensForAdverts: Boolean get() = ble != null

    override suspend fun adverts(): List<HeadsetAdvert> = withContext(Dispatchers.IO) {
        val helper = ble ?: return@withContext emptyList()
        runCatching { WinAdverts.toHeadsetAdverts(helper.scan(ADVERT_SECONDS)) }.getOrElse { emptyList() }
    }

    private fun helperFor(route: LinkRoute): WinBleHelper = ble ?: throw RouteNotOfferedException(name, route)

    /** GATT links name a device by the helper's key; a classic candidate's key is not one, so nothing is opened. */
    private fun leKey(candidate: HeadsetCandidate): String {
        if (!WinBleProtocol.isKey(candidate.key)) throw IOException("${candidate.name} is not in Windows' Bluetooth LE list")
        return candidate.key
    }

    /**
     * The helper's key for [candidate]: its own when it came from the LE list, otherwise the LE
     * entry with the same name (a JBL paired as a classic device is listed over LE under "LE-" and
     * its name). Names are compared here and go no further.
     */
    private fun gattKey(helper: WinBleHelper, candidate: HeadsetCandidate): String {
        if (WinBleProtocol.isKey(candidate.key)) return candidate.key
        return matchLe(helper.list(), candidate.name)?.key
            ?: throw IOException("${candidate.name} is not in Windows' Bluetooth LE list")
    }

    companion object {
        const val ADVERT_SECONDS = 4

        /** Bluetooth major device class for audio/video: headsets, headphones, earbuds. */
        private const val MAJOR_AUDIO_VIDEO = 4

        /**
         * A paired device Ruru lists: audio/video class (headphones, earbuds), or any name with
         * "Bose" in it (so nothing Ruru 0.2 drove is dropped). Not the "LE-" or "LE_" twin Windows
         * lists for LE Audio, and never a mouse or a phone.
         */
        fun offers(name: String, classOfDevice: Int): Boolean =
            !isLeTwin(name) && (looksLikeBose(name) || ((classOfDevice shr 8) and 0x1F) == MAJOR_AUDIO_VIDEO)

        private fun isLeTwin(name: String) = name.startsWith("LE-", ignoreCase = true) || name.startsWith("LE_", ignoreCase = true)

        private fun bare(name: String) = name.trim().removePrefix("LE-").removePrefix("LE_").trim().lowercase()

        /** The LE-list entry for the same headset as [name]: connected first, the exact name before its twin. */
        internal fun matchLe(devices: List<WinBleProtocol.Device>, name: String): WinBleProtocol.Device? =
            devices.filter { bare(it.name) == bare(name) }
                .sortedWith(compareByDescending<WinBleProtocol.Device> { it.connected }.thenBy { isLeTwin(it.name) })
                .firstOrNull()

        /** "Bose QC Ultra Headphones", "Bose QC45"; not the "LE-" twin Windows lists for LE Audio. */
        fun looksLikeBose(name: String): Boolean =
            name.contains("Bose", ignoreCase = true) && !name.startsWith("LE-", ignoreCase = true)

        internal fun orderLe(devices: List<WinBleProtocol.Device>): List<WinBleProtocol.Device> =
            devices.sortedWith(compareByDescending<WinBleProtocol.Device> { it.connected }.thenBy { it.name.startsWith("LE-", ignoreCase = true) })

        /**
         * Bose BMAP over LE: the GATT pipe on FEBE's encrypted characteristic with Bose's one-byte
         * piece header on every write and notification ([LeTransport]), as the iPhone does it.
         */
        internal fun openBoseLe(helper: WinBleHelper, key: String): ByteTransport =
            boseLeOver(helper.openGatt(key, WinBleProtocol.BOSE_LE))

        /** [LeTransport] in front of an opened, not yet started [gatt]. */
        internal fun boseLeOver(gatt: WinGattTransport): ByteTransport {
            lateinit var le: LeTransport
            le = LeTransport(maxWrite = { gatt.maxWrite }, writePiece = { gatt.write(it) }, onClose = { gatt.close() })
            gatt.sink = { le.deliver(it) }
            gatt.onDrop = { le.dropped(it) }
            gatt.start()
            return le
        }
    }
}
