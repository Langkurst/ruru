@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package io.github.langkurst.ruru.platform.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// The BlueZ D-Bus interfaces Ruru calls, written out for dbus-java. Method names are BlueZ's own.

@DBusInterfaceName("org.bluez.ProfileManager1")
interface BluezProfileManager : DBusInterface {
    fun RegisterProfile(profile: DBusPath, uuid: String, options: @JvmSuppressWildcards Map<String, Variant<*>>)
    fun UnregisterProfile(profile: DBusPath)
}

@DBusInterfaceName("org.bluez.Profile1")
interface BluezProfile : DBusInterface {
    fun Release()
    fun NewConnection(device: DBusPath, fd: FileDescriptor, fdProperties: @JvmSuppressWildcards Map<String, Variant<*>>)
    fun RequestDisconnection(device: DBusPath)
}

@DBusInterfaceName("org.bluez.Device1")
interface BluezDeviceCalls : DBusInterface {
    fun Connect()
    fun ConnectProfile(uuid: String)
}

@DBusInterfaceName("org.bluez.Adapter1")
interface BluezAdapterCalls : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
    fun SetDiscoveryFilter(properties: @JvmSuppressWildcards Map<String, Variant<*>>)
}

@DBusInterfaceName("org.bluez.GattCharacteristic1")
interface BluezCharacteristicCalls : DBusInterface {
    fun WriteValue(value: ByteArray, options: @JvmSuppressWildcards Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
}

/** The Profile1 object BlueZ calls when a connection arrives. Exported at [path] on Ruru's own bus connection. */
internal class RuruProfile(private val path: String, private val handler: ProfileHandler) : BluezProfile {
    override fun getObjectPath(): String = path
    override fun isRemote(): Boolean = false
    override fun Release() = Unit

    override fun NewConnection(device: DBusPath, fd: FileDescriptor, fdProperties: Map<String, Variant<*>>) {
        handler.newConnection(device.path, fd.intFileDescriptor)
    }

    override fun RequestDisconnection(device: DBusPath) {
        handler.requestDisconnection(device.path)
    }
}

/** [Bluez] over the system bus with dbus-java and the junixsocket transport (the one that passes file descriptors). */
internal class DbusBluez : Bluez {
    private val connection: DBusConnection by lazy {
        try {
            DBusConnectionBuilder.forSystemBus().build()
        } catch (e: DBusException) {
            throw IOException("Ruru could not reach the system D-Bus: ${e.message}", e)
        }
    }

    private fun <T : DBusInterface> remote(path: String, type: Class<T>): T = call { connection.getRemoteObject(BluezNames.SERVICE, path, type, false) }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: DBusExecutionException) {
        throw BluezException(BluezException.nameFrom(e.javaClass.name, e.message), e.message ?: "BlueZ refused the request", e)
    } catch (e: DBusException) {
        throw BluezException("org.freedesktop.DBus.Error.Failed", e.message ?: "D-Bus failed", e)
    }

    private fun managed(): Map<String, Map<String, Map<String, Variant<*>>>> = call {
        remote("/", ObjectManager::class.java).GetManagedObjects().entries.associate { (path, ifaces) -> path.path to ifaces }
    }

    private fun props(path: String, iface: String): Map<String, Variant<*>> = call { remote(path, Properties::class.java).GetAll(iface) }

    override fun devices(): List<BluezDevice> = managed().mapNotNull { (path, ifaces) -> ifaces[BluezNames.DEVICE]?.let { device(path, it) } }

    private fun device(path: String, p: Map<String, Variant<*>>): BluezDevice? {
        val address = p.str("Address") ?: return null
        return BluezDevice(
            path = path,
            address = address,
            name = p.str("Name") ?: p.str("Alias") ?: address,
            paired = p.bool("Paired"),
            connected = p.bool("Connected"),
            uuids = p.strings("UUIDs").map { it.lowercase() },
            classOfDevice = p.num("Class")?.toInt(),
            icon = p.str("Icon"),
            manufacturerData = manufacturerData(p["ManufacturerData"]),
            rssi = p.num("RSSI")?.toInt(),
        )
    }

    override fun cachedUuids(devicePath: String): List<String> = props(devicePath, BluezNames.DEVICE).strings("UUIDs").map { it.lowercase() }

    override fun registerProfile(profilePath: String, uuid: String, options: Map<String, Any>, handler: ProfileHandler): AutoCloseable {
        call { connection.exportObject(profilePath, RuruProfile(profilePath, handler)) }
        val manager = remote(BluezNames.ROOT_PATH, BluezProfileManager::class.java)
        try {
            call { manager.RegisterProfile(DBusPath(profilePath), uuid, options.mapValues { (_, v) -> Variant(v) }) }
        } catch (e: IOException) {
            connection.unExportObject(profilePath)
            throw e
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                runCatching { manager.UnregisterProfile(DBusPath(profilePath)) }
                runCatching { connection.unExportObject(profilePath) }
            }
        }
    }

    override fun connectProfile(devicePath: String, uuid: String) {
        call { remote(devicePath, BluezDeviceCalls::class.java).ConnectProfile(uuid) }
    }

    override fun resolveGatt(devicePath: String, timeoutMs: Long) {
        if (!props(devicePath, BluezNames.DEVICE).bool("Connected")) call { remote(devicePath, BluezDeviceCalls::class.java).Connect() }
        val until = System.currentTimeMillis() + timeoutMs
        while (!props(devicePath, BluezNames.DEVICE).bool("ServicesResolved")) {
            if (System.currentTimeMillis() > until) throw IOException("The headphones' Bluetooth LE services did not appear in time")
            Thread.sleep(200)
        }
    }

    override fun characteristics(devicePath: String): List<BluezCharacteristic> {
        val all = managed().filterKeys { it.startsWith("$devicePath/") }
        val services = all.mapNotNull { (path, ifaces) -> ifaces[BluezNames.GATT_SERVICE]?.str("UUID")?.let { path to it.lowercase() } }.toMap()
        return all.mapNotNull { (path, ifaces) ->
            val p = ifaces[BluezNames.GATT_CHARACTERISTIC] ?: return@mapNotNull null
            val servicePath = (p["Service"]?.value as? DBusPath)?.path ?: p.str("Service") ?: return@mapNotNull null
            BluezCharacteristic(
                path = path,
                uuid = p.str("UUID")?.lowercase() ?: return@mapNotNull null,
                serviceUuid = services[servicePath] ?: return@mapNotNull null,
                flags = p.strings("Flags").toSet(),
            )
        }
    }

    override fun writeValue(characteristicPath: String, bytes: ByteArray, withResponse: Boolean) {
        call {
            remote(characteristicPath, BluezCharacteristicCalls::class.java)
                .WriteValue(bytes, mapOf("type" to Variant(if (withResponse) "request" else "command")))
        }
    }

    override fun startNotify(characteristicPath: String, onValue: (ByteArray) -> Unit): AutoCloseable {
        val properties = remote(characteristicPath, Properties::class.java)
        val handler = call {
            connection.addSigHandler(Properties.PropertiesChanged::class.java, BluezNames.SERVICE, properties) { signal ->
                if (signal.interfaceName == BluezNames.GATT_CHARACTERISTIC) {
                    signal.propertiesChanged["Value"]?.let { v -> asBytes(v.value)?.let(onValue) }
                }
            }
        }
        val calls = remote(characteristicPath, BluezCharacteristicCalls::class.java)
        try {
            call { calls.StartNotify() }
        } catch (e: IOException) {
            runCatching { handler.close() }
            throw e
        }
        return AutoCloseable {
            runCatching { calls.StopNotify() }
            runCatching { handler.close() }
        }
    }

    override fun mtu(characteristicPath: String): Int? = props(characteristicPath, BluezNames.GATT_CHARACTERISTIC).num("MTU")?.toInt()

    override fun watchDisconnect(devicePath: String, onLost: () -> Unit): AutoCloseable {
        val fired = AtomicBoolean()
        val handler = call {
            connection.addSigHandler(Properties.PropertiesChanged::class.java, BluezNames.SERVICE, remote(devicePath, Properties::class.java)) { signal ->
                val connected = signal.propertiesChanged["Connected"]?.value as? Boolean
                if (signal.interfaceName == BluezNames.DEVICE && connected == false && fired.compareAndSet(false, true)) onLost()
            }
        }
        return AutoCloseable { runCatching { handler.close() } }
    }

    override fun scan(windowMs: Long): List<BluezDevice> {
        val adapterPath = managed().entries.firstOrNull { BluezNames.ADAPTER in it.value }?.key ?: return emptyList()
        val adapter = remote(adapterPath, BluezAdapterCalls::class.java)
        val wasScanning = props(adapterPath, BluezNames.ADAPTER).bool("Discovering")
        try {
            // LE only, and repeats wanted: an advert's signal strength and data change as the person moves.
            call { adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le"), "DuplicateData" to Variant(true))) }
            if (!wasScanning) call { adapter.StartDiscovery() }
        } catch (e: IOException) {
            return emptyList()
        }
        try {
            Thread.sleep(windowMs)
            return devices().filter { it.manufacturerData.isNotEmpty() }
        } finally {
            if (!wasScanning) runCatching { adapter.StopDiscovery() }
            runCatching { adapter.SetDiscoveryFilter(emptyMap()) }
        }
    }

    private companion object {
        fun Map<String, Variant<*>>.str(key: String): String? = this[key]?.value as? String
        fun Map<String, Variant<*>>.bool(key: String): Boolean = this[key]?.value as? Boolean ?: false
        fun Map<String, Variant<*>>.num(key: String): Number? = this[key]?.value as? Number
        fun Map<String, Variant<*>>.strings(key: String): List<String> = when (val v = this[key]?.value) {
            is Collection<*> -> v.filterIsInstance<String>()
            is Array<*> -> v.filterIsInstance<String>()
            else -> emptyList()
        }

        fun manufacturerData(v: Variant<*>?): Map<Int, ByteArray> {
            val map = v?.value as? Map<*, *> ?: return emptyMap()
            val out = LinkedHashMap<Int, ByteArray>()
            for ((id, data) in map) {
                val bytes = asBytes((data as? Variant<*>)?.value ?: data) ?: continue
                out[(id as? Number)?.toInt() ?: continue] = bytes
            }
            return out
        }

        fun asBytes(v: Any?): ByteArray? = when (v) {
            is ByteArray -> v
            is Array<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
            is Collection<*> -> ByteArray(v.size).also { out -> v.forEachIndexed { i, b -> out[i] = (b as Number).toByte() } }
            else -> null
        }
    }
}
