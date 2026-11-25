package io.github.langkurst.ruru.platform.windows

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A GUID as Windows keeps it in memory: the first three fields little-endian, the last eight bytes as written. */
internal object WinGuid {
    const val SIZE = 16
    private val UUID_TEXT = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    /** [uuid] (lower-case text, 8-4-4-4-12) as the 16 bytes of a Windows GUID. */
    fun toBytes(uuid: String): ByteArray {
        require(UUID_TEXT.matches(uuid)) { "not a lower-case UUID: $uuid" }
        val b = uuid.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)
    }

    /** The lower-case text of the GUID at [offset] in [mem]. */
    fun fromBytes(mem: ByteArray, offset: Int = 0): String {
        require(offset >= 0 && offset + SIZE <= mem.size) { "a GUID needs $SIZE bytes" }
        val m = mem.copyOfRange(offset, offset + SIZE)
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        val hex = order.joinToString("") { "%02x".format(m[it].toInt() and 0xFF) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** The GUIDs in the first [count] slots of [mem] (what BluetoothEnumerateInstalledServices fills), without repeats. */
    fun parseList(mem: ByteArray, count: Int): List<String> {
        val n = count.coerceIn(0, mem.size / SIZE)
        return (0 until n).map { fromBytes(mem, it * SIZE) }.filter { it != NIL }.distinct()
    }

    private const val NIL = "00000000-0000-0000-0000-000000000000"
}

/**
 * The SOCKADDR_BTH Winsock connects an RFCOMM socket with. Service links always use port 0 and the
 * service's class id, so Windows looks the channel up by SDP; there is deliberately no way to put a
 * channel number in here (a headset's other channels carry firmware-update and accessory services).
 */
internal object WinSockaddr {
    /** ws2bth.h packs it to one byte: family u16, address u64, service class GUID, port u32. */
    const val SIZE = 30
    const val AF_BTH = 32
    private const val ADDRESS_AT = 2
    private const val GUID_AT = 10
    private const val PORT_AT = 26

    fun build(address: Long, serviceUuid: String): ByteArray {
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, AF_BTH.toShort())
        b.putLong(ADDRESS_AT, address)
        b.position(GUID_AT)
        b.put(WinGuid.toBytes(serviceUuid))
        b.putInt(PORT_AT, 0)
        return b.array()
    }

    fun portOf(sockaddr: ByteArray): Int = ByteBuffer.wrap(sockaddr).order(ByteOrder.LITTLE_ENDIAN).getInt(PORT_AT)
    fun serviceOf(sockaddr: ByteArray): String = WinGuid.fromBytes(sockaddr, GUID_AT)
}

/** The headset does not offer the RFCOMM service asked for. Nothing was sent: the connect never completed. */
class ServiceNotFoundException(val code: Int) : IOException("service not found on the headset ($code)")

/** Opening an RFCOMM service by its UUID, with the one attempt and its error classification kept apart from Winsock so they can be tested. */
internal object WinRfcommConnect {
    /** WSASERVICE_NOT_FOUND, WSAENETUNREACH and WSAEADDRNOTAVAIL: what Windows answers when its SDP lookup finds no such service. */
    private val SERVICE_MISSING = setOf(10108, 10051, 10049)

    fun isServiceMissing(winsockError: Int): Boolean = winsockError in SERVICE_MISSING

    /**
     * One connect to [serviceUuid] on [address] through [attempt] (which gets the sockaddr and
     * returns 0 or the Winsock error). There is no second try on another channel. A missing service
     * throws [ServiceNotFoundException] when [classifyMissing]; any other failure [describe]s itself.
     */
    fun connect(address: Long, serviceUuid: String, classifyMissing: Boolean, describe: (Int) -> String, attempt: (ByteArray) -> Int) {
        val err = attempt(WinSockaddr.build(address, serviceUuid))
        if (err == 0) return
        if (classifyMissing && isServiceMissing(err)) throw ServiceNotFoundException(err)
        throw IOException("could not open the control channel: ${describe(err)}")
    }
}
