package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.HeadsetLink
import java.io.IOException

/**
 * What Ruru needs from BlueZ, as plain Kotlin. [DbusBluez] speaks D-Bus to the real daemon; the
 * tests use a fake, so the logic above (which route opens what, in which order, with which
 * arguments) is checked without a Bluetooth adapter. Devices are named by their D-Bus object path
 * (`/org/bluez/hci0/dev_...`); the path is the [io.github.langkurst.ruru.link.HeadsetCandidate.key]
 * and never leaves platform code.
 */
internal interface Bluez {
    /** Every device BlueZ knows, paired or not. */
    fun devices(): List<BluezDevice>

    /** The service UUIDs BlueZ has cached for [devicePath] (Device1.UUIDs, lower case), empty when none. */
    fun cachedUuids(devicePath: String): List<String>

    /**
     * Exports a Profile1 object at [profilePath] and registers it for [uuid] with
     * ProfileManager1.RegisterProfile. BlueZ calls [handler] when a connection arrives. Closing the
     * result unregisters and unexports it.
     */
    fun registerProfile(profilePath: String, uuid: String, options: Map<String, Any>, handler: ProfileHandler): AutoCloseable

    /** Device1.ConnectProfile(uuid): BlueZ looks the service up by SDP and connects to the profile registered for it. */
    fun connectProfile(devicePath: String, uuid: String)

    /** Connects the device if it isn't (Device1.Connect) and waits until its GATT services are resolved. */
    fun resolveGatt(devicePath: String, timeoutMs: Long)

    /** The GATT characteristics of [devicePath], once resolved. */
    fun characteristics(devicePath: String): List<BluezCharacteristic>

    /** GattCharacteristic1.WriteValue; [withResponse] picks `type=request` over `type=command`. */
    fun writeValue(characteristicPath: String, bytes: ByteArray, withResponse: Boolean)

    /** Listens to the Value property of [characteristicPath] and calls GattCharacteristic1.StartNotify. Close = StopNotify. */
    fun startNotify(characteristicPath: String, onValue: (ByteArray) -> Unit): AutoCloseable

    /** The ATT MTU of the link ([characteristicPath]'s MTU property), or null when this BlueZ doesn't say. */
    fun mtu(characteristicPath: String): Int?

    /** Calls [onLost] once when the device disconnects (Device1.Connected goes false). Close stops listening. */
    fun watchDisconnect(devicePath: String, onLost: () -> Unit): AutoCloseable

    /** Runs LE discovery for [windowMs], then returns what BlueZ heard. Leaves discovery as it found it. */
    fun scan(windowMs: Long): List<BluezDevice>
}

/** One BlueZ device with what Ruru reads from it. [address] is never shown or logged. */
internal class BluezDevice(
    val path: String,
    val address: String,
    val name: String,
    val paired: Boolean,
    val connected: Boolean,
    val uuids: List<String>,
    /** Device1.Class (null for an LE-only device). */
    val classOfDevice: Int?,
    /** Device1.Icon, e.g. "audio-headphones". */
    val icon: String?,
    /** Device1.ManufacturerData: company id to data. */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val rssi: Int? = null,
) {
    override fun toString(): String = "BluezDevice($name, paired=$paired, connected=$connected, ${uuids.size} uuids)"
}

internal class BluezCharacteristic(
    val path: String,
    val uuid: String,
    val serviceUuid: String,
    /** GattCharacteristic1.Flags, e.g. "write", "write-without-response", "notify", "indicate". */
    val flags: Set<String>,
)

/** What BlueZ calls on an exported Profile1 object. */
internal interface ProfileHandler {
    /** A connection to [devicePath] is open; [fd] is a connected RFCOMM socket now owned by Ruru. */
    fun newConnection(devicePath: String, fd: Int)

    /** BlueZ asks Ruru to let go of [devicePath]. */
    fun requestDisconnection(devicePath: String)
}

/** A BlueZ error by its D-Bus name, e.g. "org.bluez.Error.NotAvailable". */
internal class BluezException(val name: String, message: String, cause: Throwable? = null) : IOException("$message ($name)", cause) {
    /** The device or profile BlueZ was asked about doesn't offer the service. */
    val serviceMissing: Boolean
        get() = name in SERVICE_MISSING

    companion object {
        private val SERVICE_MISSING = setOf(
            "org.bluez.Error.NotAvailable",
            "org.bluez.Error.DoesNotExist",
            "org.bluez.Error.NotSupported",
        )
        private val ERROR_NAME = Regex("""org\.bluez\.Error\.[A-Za-z]+""")
        private val SHORT_NAME = Regex("(NotAvailable|DoesNotExist|NotSupported)")

        /**
         * The BlueZ error name inside what dbus-java throws: it puts the D-Bus name in the exception
         * class or message depending on whether it has a class for it. Falls back to a generic failure.
         */
        fun nameFrom(className: String?, message: String?): String {
            val text = "${className.orEmpty()} ${message.orEmpty()}"
            ERROR_NAME.find(text)?.let { return it.value }
            SHORT_NAME.find(text)?.let { return "org.bluez.Error.${it.value}" }
            return "org.freedesktop.DBus.Error.Failed"
        }
    }
}

internal object BluezNames {
    const val SERVICE = "org.bluez"
    const val PROFILE_MANAGER = "org.bluez.ProfileManager1"
    const val DEVICE = "org.bluez.Device1"
    const val ADAPTER = "org.bluez.Adapter1"
    const val GATT_CHARACTERISTIC = "org.bluez.GattCharacteristic1"
    const val GATT_SERVICE = "org.bluez.GattService1"
    const val PROFILE = "org.bluez.Profile1"
    const val ROOT_PATH = "/org/bluez"
    const val PROFILE_PATH_PREFIX = "/io/github/langkurst/ruru/profile"
}

/** Pure helpers for D-Bus object paths. */
internal object BluezPaths {
    private val DEVICE_PATH = Regex("""/org/bluez/hci\d+/dev_([0-9A-Fa-f]{2}(?:_[0-9A-Fa-f]{2}){5})""")
    private val BELOW_DEVICE = Regex("""(/org/bluez/hci\d+/dev_[0-9A-Fa-f]{2}(?:_[0-9A-Fa-f]{2}){5})(?:/.*)?""")

    /** The Bluetooth address inside a device path, "AA:BB:CC:DD:EE:FF", or null for any other path. */
    fun addressOf(devicePath: String): String? =
        DEVICE_PATH.matchEntire(devicePath)?.groupValues?.get(1)?.replace('_', ':')?.uppercase()

    /** True for the path of a device itself, not one of its services or characteristics. */
    fun isDevicePath(path: String): Boolean = DEVICE_PATH.matches(path)

    /** The device a service or characteristic path belongs to. */
    fun deviceOf(path: String): String? = BELOW_DEVICE.matchEntire(path)?.groupValues?.get(1)

    /** A profile object path for Ruru's [n]th registration. */
    fun profilePath(n: Int): String = "${BluezNames.PROFILE_PATH_PREFIX}/p$n"
}

/**
 * The options for ProfileManager1.RegisterProfile. There is deliberately no "Channel" and no
 * "PSM": BlueZ finds the service by the UUID in an SDP query, so a channel number can never be
 * guessed. "Role" is client: Ruru connects out, it never listens.
 */
internal object ProfileOptions {
    fun forUuid(@Suppress("UNUSED_PARAMETER") uuid: String): Map<String, Any> = mapOf(
        "Name" to "Ruru",
        "Role" to "client",
        "RequireAuthentication" to false,
        "RequireAuthorization" to false,
        "AutoConnect" to false,
    )

    /** Options that would pin a channel or hand BlueZ our own service record. */
    val FORBIDDEN_KEYS = setOf("Channel", "PSM", "ServiceRecord")
}

/** Which paired devices Ruru lists, and how they connect. */
internal object BluezListing {
    private const val MAJOR_AUDIO_VIDEO = 4
    private val AUDIO_UUIDS = setOf(
        "0000110b-0000-1000-8000-00805f9b34fb", // A2DP sink
        "00001108-0000-1000-8000-00805f9b34fb", // headset
        "0000111e-0000-1000-8000-00805f9b34fb", // hands-free
        "0000febe-0000-1000-8000-00805f9b34fb", // Bose's LE service
    )

    fun link(d: BluezDevice): HeadsetLink = if (d.classOfDevice != null) HeadsetLink.CLASSIC else HeadsetLink.LE

    /** A paired device Ruru lists: audio class or icon, an audio profile, or Bose by name. */
    fun offers(d: BluezDevice): Boolean =
        d.paired && (
            d.name.contains("Bose", ignoreCase = true) ||
                (((d.classOfDevice ?: 0) shr 8) and 0x1F) == MAJOR_AUDIO_VIDEO ||
                d.icon?.startsWith("audio-") == true ||
                d.uuids.any { it in AUDIO_UUIDS }
            )

    /**
     * The list for the screen: connected first, and an LE-only twin dropped when a classic device of
     * the same name is paired (one pair of headphones, not two).
     */
    fun candidates(devices: List<BluezDevice>): List<BluezDevice> {
        val offered = devices.filter(::offers)
        val classicNames = offered.filter { link(it) == HeadsetLink.CLASSIC }.map { it.name }.toSet()
        return offered.filterNot { link(it) == HeadsetLink.LE && it.name in classicNames }.sortedByDescending { it.connected }
    }
}
