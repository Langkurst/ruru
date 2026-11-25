package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Windows: Bose headsets from the paired-device list, opened on their Serial Port channel. Only
 * devices named like a Bose product are offered, so Ruru never writes to another maker's headset.
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

    /** Bose over the Serial Port service (Windows finds its channel by SDP); GATT and Bose LE through the helper. */
    override val routes: Set<RouteKind> =
        if (ble != null) setOf(RouteKind.BOSE_RFCOMM, RouteKind.BOSE_LE, RouteKind.GATT) else setOf(RouteKind.BOSE_RFCOMM)

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        if (boseOverLe) return@withContext leHeadsets()
        WinBluetooth.pairedDevices()
            .filter { looksLikeBose(it.name) }
            .sortedByDescending { it.connected }
            .map { HeadsetCandidate(key = it.address.toString(16), name = it.name, connected = it.connected) }
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
            is LinkRoute.BoseLe -> openBoseLe(helperFor(route), leKey(candidate))
            is LinkRoute.Gatt -> helperFor(route).openGatt(leKey(candidate), route.spec).also { it.start() }
            else -> throw RouteNotOfferedException(name, route)
        }
    }

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

    companion object {
        const val ADVERT_SECONDS = 4

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
