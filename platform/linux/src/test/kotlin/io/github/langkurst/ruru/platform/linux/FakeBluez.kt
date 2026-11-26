package io.github.langkurst.ruru.platform.linux

import java.io.IOException

internal const val HEADSET = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"
internal const val SVC_UUID = "00000000-deca-fade-deca-deafdecacafe"
internal const val BOSE_SERVICE = "0000febe-0000-1000-8000-00805f9b34fb"
internal const val BOSE_CHAR = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"
internal const val FIRMWARE_SERVICE = "66666666-0000-1000-8000-00805f9b34fb"

internal fun device(
    name: String = "Headphones",
    path: String = HEADSET,
    paired: Boolean = true,
    connected: Boolean = false,
    uuids: List<String> = emptyList(),
    classOfDevice: Int? = 0x240404,
    icon: String? = null,
    manufacturerData: Map<Int, ByteArray> = emptyMap(),
    rssi: Int? = null,
) = BluezDevice(path, BluezPaths.addressOf(path) ?: "00:00:00:00:00:00", name, paired, connected, uuids, classOfDevice, icon, manufacturerData, rssi)

/** A pretend BlueZ: records every call in [calls] and answers from its fields. */
internal class FakeBluez : Bluez {
    val calls = mutableListOf<String>()
    var deviceList: List<BluezDevice> = emptyList()
    var cached: List<String> = emptyList()
    var characteristicList: List<BluezCharacteristic> = emptyList()
    var mtu: Int? = null
    var scanResult: List<BluezDevice> = emptyList()

    /** Throw this from [connectProfile]. */
    var connectProfileError: BluezException? = null

    /** What the pretend BlueZ does inside connectProfile: hands over this fd for [HEADSET] (null = nothing arrives). */
    var fdOnConnect: Int? = 42
    var fdFromDevice: String = HEADSET

    var registeredPath: String? = null
    var registeredUuid: String? = null
    var registeredOptions: Map<String, Any>? = null
    var unregistered = 0
    private var handler: ProfileHandler? = null

    val writes = mutableListOf<Triple<String, ByteArray, Boolean>>()
    val notifyHandlers = LinkedHashMap<String, (ByteArray) -> Unit>()
    val stoppedNotify = mutableListOf<String>()
    var disconnectHandler: (() -> Unit)? = null
    var writeError: IOException? = null

    override fun devices(): List<BluezDevice> = deviceList.also { calls += "devices" }

    override fun cachedUuids(devicePath: String): List<String> = cached.also { calls += "cachedUuids" }

    override fun registerProfile(profilePath: String, uuid: String, options: Map<String, Any>, handler: ProfileHandler): AutoCloseable {
        calls += "registerProfile $uuid"
        registeredPath = profilePath
        registeredUuid = uuid
        registeredOptions = options
        this.handler = handler
        return AutoCloseable { unregistered++; calls += "unregisterProfile" }
    }

    override fun connectProfile(devicePath: String, uuid: String) {
        calls += "connectProfile $uuid"
        connectProfileError?.let { throw it }
        fdOnConnect?.let { handler!!.newConnection(fdFromDevice, it) }
    }

    fun bluezDisconnects(device: String = HEADSET) = handler!!.requestDisconnection(device)

    override fun resolveGatt(devicePath: String, timeoutMs: Long) {
        calls += "resolveGatt"
    }

    override fun characteristics(devicePath: String): List<BluezCharacteristic> = characteristicList.also { calls += "characteristics" }

    override fun writeValue(characteristicPath: String, bytes: ByteArray, withResponse: Boolean) {
        calls += "write $characteristicPath"
        writeError?.let { throw it }
        writes += Triple(characteristicPath, bytes, withResponse)
    }

    override fun startNotify(characteristicPath: String, onValue: (ByteArray) -> Unit): AutoCloseable {
        calls += "startNotify $characteristicPath"
        notifyHandlers[characteristicPath] = onValue
        return AutoCloseable { stoppedNotify += characteristicPath; calls += "stopNotify $characteristicPath" }
    }

    override fun mtu(characteristicPath: String): Int? = mtu

    override fun watchDisconnect(devicePath: String, onLost: () -> Unit): AutoCloseable {
        calls += "watchDisconnect"
        disconnectHandler = onLost
        return AutoCloseable { disconnectHandler = null }
    }

    override fun scan(windowMs: Long): List<BluezDevice> = scanResult.also { calls += "scan $windowMs" }
}

internal fun characteristic(service: String, uuid: String, vararg flags: String, path: String = "$HEADSET/service/char_${uuid.take(4)}") =
    BluezCharacteristic(path, uuid, service, flags.toSet())

/** A pretend connected socket: [toRead] chunks come out of read(), writes are kept. */
internal class FakePort : RfcommPort {
    val written = mutableListOf<ByteArray>()
    val toRead = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile var endOfStream = false
    var closed = 0

    override fun write(bytes: ByteArray) {
        written += bytes
    }

    override fun read(): ByteArray? {
        val chunk = toRead.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
        return when {
            chunk != null -> chunk
            endOfStream -> null
            else -> ByteArray(0)
        }
    }

    override fun close() {
        closed++
    }
}
