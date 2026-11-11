package io.github.langkurst.ruru.platform.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.Closeable
import java.io.IOException

/**
 * Bluetooth Classic serial channel (RFCOMM) on Windows, through Winsock and the Win32 Bluetooth
 * API. No WinRT and no helper program: a plain JVM can open the same channel the Bose app uses.
 *
 * 64-bit Windows only (struct offsets and SOCKET width below assume it).
 */
object WinBluetooth {

    /** A paired device. [address] is kept inside the app and never printed. */
    class Device(val name: String, internal val address: Long, val connected: Boolean)

    /** Paired devices Windows knows about (paired and remembered, with their connection state). */
    fun pairedDevices(): List<Device> {
        val params = Memory(SEARCH_PARAMS_SIZE.toLong()).apply {
            clear()
            setInt(0, SEARCH_PARAMS_SIZE)  // dwSize
            setInt(4, 1)                   // fReturnAuthenticated
            setInt(8, 1)                   // fReturnRemembered
            setInt(12, 0)                  // fReturnUnknown
            setInt(16, 1)                  // fReturnConnected
            setInt(20, 0)                  // fIssueInquiry: no radio scan, paired list only
        }
        val info = Memory(DEVICE_INFO_SIZE.toLong())
        fun resetInfo() { info.clear(); info.setInt(0, DEVICE_INFO_SIZE) }

        resetInfo()
        val find = BtApi.INSTANCE.BluetoothFindFirstDevice(params, info) ?: return emptyList()
        val out = ArrayList<Device>()
        try {
            do {
                val name = info.getWideString(64)
                out.add(Device(name, info.getLong(8), connected = info.getInt(20) != 0))
                resetInfo()
            } while (BtApi.INSTANCE.BluetoothFindNextDevice(find, info))
        } finally {
            BtApi.INSTANCE.BluetoothFindDeviceClose(find)
        }
        return out
    }

    /** Opens the device's Serial Port service. Windows looks up the channel number itself (SDP). */
    fun openSerialPort(device: Device, readTimeoutMs: Int = 2_000): RfcommSocket {
        startWinsock()
        val ws = Ws2.INSTANCE
        val s = ws.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (s == INVALID_SOCKET) throw IOException("socket() failed: ${describe(Native.getLastError())}")

        val addr = Memory(SOCKADDR_BTH_SIZE.toLong()).apply {
            clear()
            setShort(0, AF_BTH.toShort())
            setLong(2, device.address)
            write(10, SPP_GUID, 0, SPP_GUID.size)
            setInt(26, 0) // port 0 + service class id = ask SDP for the channel
        }
        if (ws.connect(s, addr, SOCKADDR_BTH_SIZE) != 0) {
            val err = Native.getLastError()
            ws.closesocket(s)
            throw IOException("could not open the control channel: ${describe(err)}")
        }
        val timeout = Memory(4).apply { setInt(0, readTimeoutMs) }
        ws.setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, timeout, 4)
        return RfcommSocket(s)
    }

    class RfcommSocket internal constructor(private val s: Long) : Closeable {
        private val buf = ByteArray(1024)
        @Volatile private var open = true

        fun write(bytes: ByteArray) {
            var sent = 0
            while (sent < bytes.size) {
                val chunk = if (sent == 0) bytes else bytes.copyOfRange(sent, bytes.size)
                val n = Ws2.INSTANCE.send(s, chunk, chunk.size, 0)
                if (n <= 0) throw IOException("send failed: ${describe(Native.getLastError())}")
                sent += n
            }
        }

        /** The next chunk of bytes, an empty array when the read timeout passes, or null at end of stream. */
        fun read(): ByteArray? {
            val n = Ws2.INSTANCE.recv(s, buf, buf.size, 0)
            if (n > 0) return buf.copyOf(n)
            if (n == 0) return null
            val err = Native.getLastError()
            if (err == WSAETIMEDOUT) return ByteArray(0)
            throw IOException("read failed: ${describe(err)}")
        }

        override fun close() {
            if (!open) return
            open = false
            Ws2.INSTANCE.closesocket(s)
        }
    }

    // ---- Win32 plumbing ----

    private const val AF_BTH = 32
    private const val SOCK_STREAM = 1
    private const val BTHPROTO_RFCOMM = 3
    private const val SOL_SOCKET = 0xFFFF
    private const val SO_RCVTIMEO = 0x1006
    private const val INVALID_SOCKET = -1L
    private const val WSAETIMEDOUT = 10060

    /** ws2bth.h packs SOCKADDR_BTH to 1 byte: family u16, address u64, service GUID, port u32. */
    private const val SOCKADDR_BTH_SIZE = 30
    /** sizeof(BLUETOOTH_DEVICE_SEARCH_PARAMS) and sizeof(BLUETOOTH_DEVICE_INFO) on x64. */
    private const val SEARCH_PARAMS_SIZE = 40
    private const val DEVICE_INFO_SIZE = 560

    /** {00001101-0000-1000-8000-00805F9B34FB} in GUID memory order (first three fields little-endian). */
    private val SPP_GUID = byteArrayOf(
        0x01, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10,
        0x80.toByte(), 0x00, 0x00, 0x80.toByte(), 0x5F, 0x9B.toByte(), 0x34, 0xFB.toByte(),
    )

    @Volatile private var winsockStarted = false

    @Synchronized
    private fun startWinsock() {
        if (winsockStarted) return
        val data = Memory(512)
        val rc = Ws2.INSTANCE.WSAStartup(0x0202.toShort(), data)
        if (rc != 0) throw IOException("WSAStartup failed: $rc")
        winsockStarted = true
    }

    private fun describe(code: Int): String = when (code) {
        10013 -> "access denied (10013)"
        10048 -> "already in use by another app (10048)"
        10049 -> "address not available (10049)"
        10050 -> "Bluetooth is off or the network is down (10050)"
        10051 -> "device unreachable (10051)"
        10060 -> "timed out: the headset did not answer (10060)"
        10061 -> "refused: the headset turned the channel down (10061)"
        10064 -> "headset is off or out of range (10064)"
        10065 -> "no route to the headset (10065)"
        10108 -> "service not found on the headset (10108)"
        else -> "Winsock error $code"
    }

    @Suppress("FunctionName")
    private interface Ws2 : Library {
        fun WSAStartup(version: Short, data: Pointer): Int
        fun socket(af: Int, type: Int, protocol: Int): Long
        fun connect(s: Long, name: Pointer, nameLen: Int): Int
        fun send(s: Long, buf: ByteArray, len: Int, flags: Int): Int
        fun recv(s: Long, buf: ByteArray, len: Int, flags: Int): Int
        fun setsockopt(s: Long, level: Int, optName: Int, optVal: Pointer, optLen: Int): Int
        fun closesocket(s: Long): Int

        companion object {
            // The error code is read with Native.getLastError(), which JNA captures straight after
            // each call (a separate WSAGetLastError() call could see a value JNA itself reset).
            val INSTANCE: Ws2 = Native.load("ws2_32", Ws2::class.java)
        }
    }

    @Suppress("FunctionName")
    private interface BtApi : Library {
        fun BluetoothFindFirstDevice(params: Pointer, info: Pointer): Pointer?
        fun BluetoothFindNextDevice(find: Pointer, info: Pointer): Boolean
        fun BluetoothFindDeviceClose(find: Pointer): Boolean

        companion object {
            val INSTANCE: BtApi = Native.load("BluetoothApis", BtApi::class.java)
        }
    }
}
