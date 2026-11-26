package io.github.langkurst.ruru.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.langkurst.ruru.android.ble.AdvertScanner
import io.github.langkurst.ruru.android.ble.BleScanAccess
import io.github.langkurst.ruru.android.ble.BondedRules
import io.github.langkurst.ruru.android.ble.GattLink
import io.github.langkurst.ruru.android.ble.GattSetupException
import io.github.langkurst.ruru.android.ble.GattTargets
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Android: the bonded audio devices, opened over the route the maker's vendor code picks.
 *  - Bose over the Serial Port service like the Bose app does, then the model's known RFCOMM
 *    channels (Earshot's findings);
 *  - another maker's RFCOMM service by its UUID only ([LinkRoute.RfcommService]): never a channel
 *    number, so a firmware-update service on another channel never sees a byte, and a UUID the
 *    system's cached service list lacks is refused before a socket is made;
 *  - Bluetooth LE GATT ([LinkRoute.Gatt], and Bose's FEBE service for [LinkRoute.BoseLe]).
 * Listening for AirPods and Beats adverts happens only while Ruru is in front ([inFront]).
 * Needs BLUETOOTH_CONNECT (Nearby devices); adverts need BLUETOOTH_SCAN, declared never for location.
 * Bluetooth addresses stay in this file's package: callers see opaque keys.
 */
@SuppressLint("MissingPermission")
class AndroidHeadsetPlatform(private val context: Context) : HeadsetPlatform {
    override val name: String = "Android"
    override val supported: Boolean = adapter() != null

    private val hasLe = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override val routes: Set<RouteKind> = buildSet {
        add(RouteKind.BOSE_RFCOMM)
        add(RouteKind.RFCOMM_SERVICE)
        if (hasLe) {
            add(RouteKind.GATT)
            add(RouteKind.BOSE_LE)
        }
    }

    /** True while an activity of Ruru is on screen; adverts are only listened to then. Set by the activity. */
    @Volatile var inFront: Boolean = false

    /**
     * Called (from any thread) when a listen was skipped because the app lacks the permission a scan
     * needs, so the activity can ask for it. [BleScanAccess.permissionToAsk] names it.
     */
    @Volatile var onScanAccessNeeded: ((permission: String) -> Unit)? = null

    private val adverts by lazy { AdvertScanner(context) }
    private val targets by lazy { GattTargets(context) }

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun allowed() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override val blocked: Words?
        get() = if (allowed()) null else Words("problem_android_permission")

    override val listensForAdverts: Boolean get() = hasLe && supported

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext emptyList()
        val audio = adapter()?.bondedDevices.orEmpty().filter { BondedRules.isAudio(it.majorClass(), it.cachedUuids()) }
        val kept = BondedRules.dropLeTwins(audio.map { it.name ?: "" }).toSet()
        audio.filter { (it.name ?: "") in kept }
            .map {
                HeadsetCandidate(
                    key = it.address,
                    name = it.name?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth headphones",
                    connected = it.isConnectedCompat(),
                    uuids = it.cachedUuids(),
                    link = if (it.type == BluetoothDevice.DEVICE_TYPE_LE) HeadsetLink.LE else HeadsetLink.CLASSIC,
                )
            }
            .sortedByDescending { it.connected }
    }

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport = withContext(Dispatchers.IO) {
        if (route.kind !in routes) throw RouteNotOfferedException(name, route)
        val device = adapter()?.getRemoteDevice(candidate.key) ?: throw IOException("Bluetooth is off")
        adapter()?.cancelDiscovery()
        when (route) {
            is LinkRoute.BoseRfcomm -> openBose(device, route)
            is LinkRoute.RfcommService -> openService(device, candidate, route)
            is LinkRoute.Gatt -> openGatt(device, candidate, route.spec)
            is LinkRoute.BoseLe -> openBoseLe(device)
        }
    }

    private fun openBose(device: BluetoothDevice, route: LinkRoute.BoseRfcomm): ByteTransport {
        val tries = buildList<() -> BluetoothSocket> {
            add { device.createRfcommSocketToServiceRecord(SPP) }
            for (ch in route.channels) add { device.createRfcommSocketByChannel(ch) }
        }
        var last: Exception? = null
        for (make in tries) {
            val socket = try { make() } catch (e: Exception) { last = e; continue }
            try {
                socket.connect()
                return AndroidRfcommTransport(socket)
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw IOException("Could not open the control channel", last)
    }

    /** One RFCOMM service found by its UUID (SDP), never by a channel number. */
    private fun openService(device: BluetoothDevice, candidate: HeadsetCandidate, route: LinkRoute.RfcommService): ByteTransport {
        if (BondedRules.serviceMissing(candidate.uuids, route.uuid)) {
            LogRing.add('I', TAG, "${candidate.name}: no ${route.uuid} in the system's service list; not opened")
            throw ExplainedConnectException(Words("problem_android_no_service"), retry = false)
        }
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(route.uuid))
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw IOException("Could not open the ${route.uuid} service", e)
        }
        return AndroidRfcommTransport(socket)
    }

    private suspend fun openGatt(device: BluetoothDevice, candidate: HeadsetCandidate, spec: GattLinkSpec): ByteTransport {
        val target = targets.find(device, candidate.name, spec) ?: throw GattSetupException("no Bluetooth LE address found for ${candidate.name}")
        return GattLink.open(context, target, spec)
    }

    /** Bose BMAP over LE: the FEBE service with Bose's one-byte piece header on every write and notification ([LeTransport]). */
    private suspend fun openBoseLe(device: BluetoothDevice): ByteTransport {
        lateinit var le: LeTransport
        GattLink.open(context, device, BOSE_LE) { gatt ->
            le = LeTransport(maxWrite = { gatt.maxWrite }, writePiece = { gatt.write(it) }, onClose = { gatt.close() })
            gatt.sink = { le.deliver(it) }
            gatt.onDrop = { le.dropped(it) }
        }
        return le
    }

    override suspend fun adverts(): List<HeadsetAdvert> {
        if (!listensForAdverts || !inFront || !allowed()) return emptyList()
        if (BleScanAccess.missing(context) != null) {
            onScanAccessNeeded?.invoke(BleScanAccess.permissionToAsk(Build.VERSION.SDK_INT))
            return emptyList()
        }
        return adverts.listen(ADVERT_MS)
    }

    private companion object {
        const val TAG = "Android"
        const val ADVERT_MS = 4_000L

        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        /** Bose's control service over LE: the encrypted characteristic for writes and notifications. */
        val BOSE_LE = GattLinkSpec(
            name = "Bose LE",
            service = "0000febe-0000-1000-8000-00805f9b34fb",
            write = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8",
            notify = listOf("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"),
            writeWithResponse = true,
        )

        /** BluetoothDevice.isConnected() is hidden but on the SDK's allowed list; assume yes if it is missing. */
        fun BluetoothDevice.isConnectedCompat(): Boolean =
            runCatching { javaClass.getMethod("isConnected").invoke(this) as Boolean }.getOrDefault(true)

        /** The service UUIDs Android cached from its last SDP lookup: no lookup is started, nothing is sent. */
        fun BluetoothDevice.cachedUuids(): List<String> =
            runCatching { uuids }.getOrNull().orEmpty().map { it.uuid.toString().lowercase() }

        fun BluetoothDevice.majorClass(): Int? = runCatching { bluetoothClass?.majorDeviceClass }.getOrNull()

        fun BluetoothDevice.createRfcommSocketByChannel(channel: Int): BluetoothSocket =
            javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(this, channel) as BluetoothSocket
    }
}

private class AndroidRfcommTransport(private val socket: BluetoothSocket) : ByteTransport {
    @Volatile private var open = true
    override val isConnected: Boolean get() = open && socket.isConnected

    override val incoming: Flow<ByteArray> = flow {
        val input = socket.inputStream
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
        if (!open) throw IOException("control channel closed")
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
    }

    override fun close() {
        open = false
        runCatching { socket.close() }
    }
}
