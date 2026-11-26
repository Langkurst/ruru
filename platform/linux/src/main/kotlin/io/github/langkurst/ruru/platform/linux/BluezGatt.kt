package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.link.RouteNotOfferedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Bose BMAP over LE: service FEBE, the encrypted characteristic for writes and notifications
 * (the same spec the Mac and iPhone use).
 */
internal val BOSE_LE_SPEC: GattLinkSpec = GattLinkSpec(
    name = "Bose LE",
    service = "0000febe-0000-1000-8000-00805f9b34fb",
    write = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8",
    notify = listOf("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"),
    writeWithResponse = true,
)

/** Pure sizing and mode rules for BlueZ GATT writes. */
internal object BluezGattRules {
    /** Opcode and handle take 3 bytes of every ATT write. */
    const val ATT_HEADER = 3
    const val DEFAULT_MTU = 23

    /** BlueZ and the ATT protocol cap one attribute value at 512 bytes. */
    const val MAX_VALUE = 512

    /** The bytes one write carries: what the link's MTU grants, never more than [spec] asked for. */
    fun maxWrite(grantedMtu: Int?, spec: GattLinkSpec): Int {
        val mtu = minOf(grantedMtu ?: DEFAULT_MTU, spec.mtu)
        return (mtu - ATT_HEADER).coerceIn(DEFAULT_MTU - ATT_HEADER, MAX_VALUE)
    }

    /** Cuts [bytes] into writes of at most [maxWrite]; an empty array is one empty write. */
    fun chunks(bytes: ByteArray, maxWrite: Int): List<ByteArray> {
        require(maxWrite > 0) { "a write must carry at least one byte" }
        if (bytes.size <= maxWrite) return listOf(bytes)
        return (bytes.indices step maxWrite).map { bytes.copyOfRange(it, minOf(bytes.size, it + maxWrite)) }
    }

    /**
     * With or without a response: what [spec] asks for when the characteristic allows it, otherwise
     * the mode the characteristic does allow.
     */
    fun withResponse(asked: Boolean, flags: Set<String>): Boolean {
        val canRequest = "write" in flags
        val canCommand = "write-without-response" in flags
        return when {
            asked && canRequest -> true
            !asked && canCommand -> false
            canRequest -> true
            canCommand -> false
            else -> asked
        }
    }

    /** True when [flags] let the characteristic push values to Ruru. */
    fun notifies(flags: Set<String>): Boolean = "notify" in flags || "indicate" in flags
}

/**
 * A vendor's GATT byte pipe through BlueZ, as a [ByteTransport]. Writes are cut to what one ATT
 * write carries and go one after the other (each waits for BlueZ's reply); notifications go to
 * [incoming], or to a [sink] when a framing layer (Bose LE's [LeTransport]) sits in front. Joining
 * notifications into frames is the vendor codec's job, as on every other platform. Call [start]
 * once the sink is in place: notifications are switched on there, in the spec's order.
 */
internal class BluezGattTransport(
    private val bluez: Bluez,
    private val devicePath: String,
    private val write: BluezCharacteristic,
    private val notifyOn: List<BluezCharacteristic>,
    private val withResponse: Boolean,
    val maxWrite: Int,
    private val name: String,
) : ByteTransport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val writing = Mutex()
    private val subscriptions = ArrayList<AutoCloseable>()
    private var watch: AutoCloseable? = null
    @Volatile private var open = true
    @Volatile private var started = false

    /** Where notifications go instead of [incoming]; set before [start]. */
    @Volatile var sink: ((ByteArray) -> Unit)? = null

    /** Called once when the link drops underneath (not on [close]). */
    @Volatile var onDrop: ((String) -> Unit)? = null

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** Switches notifications on and starts watching for the link to drop. */
    fun start() {
        check(!started) { "already started" }
        started = true
        watch = bluez.watchDisconnect(devicePath) { dropped("the Bluetooth LE link dropped") }
        for (c in notifyOn) {
            try {
                synchronized(subscriptions) { subscriptions += bluez.startNotify(c.path, ::deliver) }
            } catch (e: IOException) {
                close()
                throw e
            }
        }
    }

    internal fun deliver(bytes: ByteArray) {
        if (!open) return
        sink?.invoke(bytes) ?: inbox.trySend(bytes)
    }

    private fun dropped(reason: String) {
        if (!open) return
        open = false
        LogRing.add('I', "Linux", "$name: $reason")
        inbox.close(IOException(reason))
        onDrop?.invoke(reason)
        release()
    }

    override suspend fun write(bytes: ByteArray) = writing.withLock {
        for (chunk in BluezGattRules.chunks(bytes, maxWrite)) {
            if (!open) throw IOException("The Bluetooth LE link is closed")
            try {
                withContext(Dispatchers.IO) { bluez.writeValue(write.path, chunk, withResponse) }
            } catch (e: IOException) {
                throw IOException("The headphones refused a write over Bluetooth LE: ${e.message}", e)
            }
        }
    }

    override fun close() {
        if (!open) return
        open = false
        inbox.close()
        release()
    }

    /** Stops notifications and the watch. The device stays connected: it may be playing music. */
    private fun release() {
        val subs = synchronized(subscriptions) { subscriptions.toList().also { subscriptions.clear() } }
        for (s in subs) runCatching { s.close() }
        runCatching { watch?.close() }
    }
}

/** Finds the characteristics of a [GattLinkSpec] on a device and builds the transport, touching nothing else. */
internal class BluezGattOpener(
    private val bluez: Bluez,
    private val platformName: String,
    private val resolveTimeoutMs: Long = 20_000,
) {
    /**
     * Connects (if needed) and reads the device's GATT tree, then picks the spec's characteristics.
     * A device without the service, the write characteristic or any notifying characteristic is
     * [RouteNotOfferedException], nothing written. Characteristics of other services (firmware
     * updates above all) are never opened, written or subscribed to.
     */
    fun open(devicePath: String, route: LinkRoute, spec: GattLinkSpec): BluezGattTransport {
        require(!GattLinkSpec.isFirmwareService(spec.service)) { "${spec.service} is a firmware-update service" }
        bluez.resolveGatt(devicePath, resolveTimeoutMs)
        val own = bluez.characteristics(devicePath).filter { it.serviceUuid == spec.service }
        val write = own.firstOrNull { it.uuid == spec.write } ?: throw RouteNotOfferedException(platformName, route)
        val notify = spec.notify.distinct().mapNotNull { id -> own.firstOrNull { it.uuid == id && BluezGattRules.notifies(it.flags) } }
        if (notify.isEmpty()) throw RouteNotOfferedException(platformName, route)
        val mtu = runCatching { bluez.mtu(write.path) }.getOrNull()
        return BluezGattTransport(
            bluez = bluez,
            devicePath = devicePath,
            write = write,
            notifyOn = notify,
            withResponse = BluezGattRules.withResponse(spec.writeWithResponse, write.flags),
            maxWrite = BluezGattRules.maxWrite(mtu, spec),
            name = spec.name,
        )
    }

    /** Bose BMAP over LE: the GATT pipe with Bose's one-byte piece header on every write and notification ([LeTransport]). */
    fun boseLeOver(gatt: BluezGattTransport): ByteTransport {
        lateinit var le: LeTransport
        le = LeTransport(maxWrite = { gatt.maxWrite }, writePiece = { gatt.write(it) }, onClose = { gatt.close() })
        gatt.sink = { le.deliver(it) }
        gatt.onDrop = { le.dropped(it) }
        gatt.start()
        return le
    }
}
