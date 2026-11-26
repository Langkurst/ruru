package io.github.langkurst.ruru.platform.macos

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * macOS: the JVM cannot call IOBluetooth or CoreBluetooth, so a small Swift helper
 * (`helper/main.swift`, bundled as `ruru-bt-mac`) does it: it lists the paired devices (with the
 * service UUIDs macOS has cached), bridges one RFCOMM channel, and holds GATT links. Bose opens on
 * its Serial Port channel; Sony, Sennheiser, Soundcore and Skullcandy open an RFCOMM service by its
 * UUID (an SDP query, then the channel the record names, never a guessed one; a service the
 * headset lacks is refused before anything is opened); JBL and Bose over LE use GATT.
 *
 * Adverts are not possible here (macOS hides AirPods and Beats adverts from apps), so [adverts]
 * stays empty and [listensForAdverts] false. Devices are named by the helper's opaque keys; no
 * Bluetooth address ever reaches this code. Not yet tested on real hardware.
 */
class MacHeadsetPlatform(helper: File? = MacBtHelper.locate()) : HeadsetPlatform {
    private val bt: MacBtHelper? = helper?.let { MacBtHelper(it) }

    override val name: String = "macOS"
    override val supported: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac") && helper?.canExecute() == true

    override val routes: Set<RouteKind> =
        setOf(RouteKind.BOSE_RFCOMM, RouteKind.RFCOMM_SERVICE, RouteKind.GATT, RouteKind.BOSE_LE)

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        val helper = bt ?: return@withContext emptyList()
        helper.list()
            .filter { offers(it.name, it.classOfDevice) }
            .sortedByDescending { it.connected }
            .map { HeadsetCandidate(key = it.key, name = it.name.removePrefix("LE-"), connected = it.connected, uuids = it.uuids, link = it.link) }
    }

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport = withContext(Dispatchers.IO) {
        when (route) {
            is LinkRoute.BoseRfcomm -> {
                // The model's own channel, the first of the route's list; the helper prefers the Serial Port record's.
                val channel = route.channels.first()
                requireHelper().openRfcomm(MacBtProtocol.connectArgs(candidate.key, channel))
            }
            is LinkRoute.RfcommService -> {
                val helper = bt ?: throw RouteNotOfferedException(name, route)
                try {
                    helper.openRfcomm(MacBtProtocol.connectUuidArgs(candidate.key, route.uuid))
                } catch (e: MacBtException) {
                    // Nothing was opened: the SDP query showed no such service.
                    if (e.failure.code == MacBtProtocol.SERVICE_NOT_FOUND) throw RouteNotOfferedException(name, route).apply { initCause(e) }
                    throw e
                }
            }
            is LinkRoute.BoseLe -> {
                val helper = bt ?: throw RouteNotOfferedException(name, route)
                boseLeOver(helper.openGatt(candidate.key, MacBtProtocol.BOSE_LE))
            }
            is LinkRoute.Gatt -> {
                val helper = bt ?: throw RouteNotOfferedException(name, route)
                helper.openGatt(candidate.key, route.spec).also { it.start() }
            }
        }
    }

    private fun requireHelper(): MacBtHelper = bt ?: throw IOException("The Bluetooth helper is missing from this build")

    companion object {
        /** Bluetooth major device class for audio/video: headsets, headphones, earbuds. */
        private const val MAJOR_AUDIO_VIDEO = 4

        /** In the app bundle (Compose resources dir), or RURU_BT_MAC while developing. */
        fun findHelper(): File? = MacBtHelper.locate()

        /**
         * A paired device Ruru lists: audio/video class (headphones, earbuds), or any name with
         * "Bose" in it (so nothing Ruru 0.2 drove is dropped; a Bose that macOS shows only over LE
         * has no class). Not the "LE-" twin, and never a mouse or a phone.
         */
        fun offers(name: String, classOfDevice: Int): Boolean =
            looksLikeBose(name) || ((classOfDevice shr 8) and 0x1F) == MAJOR_AUDIO_VIDEO

        fun looksLikeBose(name: String): Boolean = name.contains("Bose", ignoreCase = true)

        /**
         * Bose BMAP over LE: the GATT pipe on FEBE's encrypted characteristic with Bose's one-byte
         * piece header on every write and notification ([LeTransport]), as the iPhone does it.
         */
        internal fun boseLeOver(gatt: MacGattTransport): ByteTransport {
            lateinit var le: LeTransport
            le = LeTransport(maxWrite = { gatt.maxWrite }, writePiece = { gatt.write(it) }, onClose = { gatt.close() })
            gatt.sink = { le.deliver(it) }
            gatt.onDrop = { le.dropped(it) }
            gatt.start()
            return le
        }
    }
}

/** Bytes to and from a helper process: its stdout is the headset, its stdin goes to the headset. */
internal class ProcessTransport(private val process: Process) : ByteTransport {
    @Volatile private var open = true
    override val isConnected: Boolean get() = open && process.isAlive

    override val incoming: Flow<ByteArray> = flow {
        val input = process.inputStream
        val buf = ByteArray(1024)
        while (open) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (!open) break
                open = false
                throw e
            }
            if (n < 0) {
                open = false
                throw IOException("the headset closed the control channel")
            }
            if (n > 0) emit(buf.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!isConnected) throw IOException("control channel closed")
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override fun close() {
        open = false
        runCatching { process.outputStream.close() }
        process.destroy()
    }
}
