package io.github.langkurst.ruru.platform.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.protocol.bose.BoseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Linux (BlueZ): paired Bose headsets from `bluetoothctl`, the control channel through a plain
 * RFCOMM socket (libc via JNA). Bose puts BMAP on channel 2 on most models and 8 or 9 on a few,
 * as bosectl and Earshot found; each channel is checked with a harmless read before it is used.
 * Not yet tested on real hardware.
 */
class LinuxHeadsetPlatform : HeadsetPlatform {
    override val name: String = "Linux"
    override val supported: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Linux") && run("bluetoothctl", "--version") != null

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        val paired = run("bluetoothctl", "devices", "Paired") ?: run("bluetoothctl", "paired-devices") ?: ""
        paired.lines().mapNotNull { line ->
            val m = DEVICE_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val (address, name) = m.destructured
            if (!name.contains("Bose", ignoreCase = true)) return@mapNotNull null
            val connected = run("bluetoothctl", "info", address)?.lines()?.any { it.trim() == "Connected: yes" } == true
            HeadsetCandidate(key = address, name = name, connected = connected)
        }.sortedByDescending { it.connected }
    }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport = withContext(Dispatchers.IO) {
        val first = BoseModel.fromName(candidate.name)?.rfcommChannel ?: 2
        var last: Exception? = null
        for (channel in listOf(first, 2, 8, 9).distinct()) {
            try {
                val socket = RfcommSocket.connect(candidate.key, channel)
                if (socket.answersBmap()) return@withContext LinuxRfcommTransport(socket)
                socket.close()
            } catch (e: IOException) {
                last = e
            }
        }
        throw IOException("No control channel answered on ${candidate.name}", last)
    }

    private fun run(vararg command: String): String? = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!p.waitFor(4, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        p.inputStream.bufferedReader().readText().takeIf { p.exitValue() == 0 }
    }.getOrNull()

    private companion object {
        val DEVICE_LINE = Regex("^Device ([0-9A-Fa-f:]{17}) (.+)$")
    }
}

/** An RFCOMM socket from libc. Reads time out every [READ_POLL_MS] so a close is noticed. */
internal class RfcommSocket private constructor(private val fd: Int) : AutoCloseable {
    @Volatile private var open = true

    fun write(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val chunk = if (off == 0) bytes else bytes.copyOfRange(off, bytes.size)
            val n = LibC.INSTANCE.write(fd, chunk, chunk.size.toLong())
            if (n <= 0) throw IOException("write failed (errno ${Native.getLastError()})")
            off += n.toInt()
        }
    }

    /** Bytes read, an empty array on timeout, null at end of stream. */
    fun read(): ByteArray? {
        val buf = ByteArray(1024)
        val n = LibC.INSTANCE.read(fd, buf, buf.size.toLong())
        if (n > 0) return buf.copyOf(n.toInt())
        if (n == 0L) return null
        val errno = Native.getLastError()
        if (errno == EAGAIN || errno == EINTR) return ByteArray(0)
        throw IOException("read failed (errno $errno)")
    }

    /** Sends `[0.1]` GET (BMAP version) and waits up to 1.5 s for any BMAP frame of block 0. */
    fun answersBmap(): Boolean {
        write(byteArrayOf(0x00, 0x01, 0x01, 0x00))
        val until = System.currentTimeMillis() + 1_500
        val got = java.io.ByteArrayOutputStream()
        while (System.currentTimeMillis() < until) {
            val chunk = read() ?: return false
            got.write(chunk)
            val b = got.toByteArray()
            if (b.size >= 4 && b[0].toInt() == 0x00 && (b[2].toInt() and 0x0F) in 3..4) return true
        }
        return false
    }

    override fun close() {
        if (!open) return
        open = false
        LibC.INSTANCE.close(fd)
    }

    companion object {
        private const val AF_BLUETOOTH = 31
        private const val SOCK_STREAM = 1
        private const val BTPROTO_RFCOMM = 3
        private const val SOL_SOCKET = 1
        private const val SO_RCVTIMEO = 20
        private const val EAGAIN = 11
        private const val EINTR = 4
        const val READ_POLL_MS = 250

        fun connect(address: String, channel: Int): RfcommSocket {
            val fd = LibC.INSTANCE.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
            if (fd < 0) throw IOException("socket failed (errno ${Native.getLastError()}); is Bluetooth on?")
            // struct sockaddr_rc { sa_family_t family; bdaddr_t bdaddr (reversed); uint8_t channel; }
            val addr = Memory(10).apply {
                clear()
                setShort(0, AF_BLUETOOTH.toShort())
                val parts = address.split(":").map { it.toInt(16).toByte() }.reversed()
                for (i in 0 until 6) setByte(2L + i, parts[i])
                setByte(8, channel.toByte())
            }
            if (LibC.INSTANCE.connect(fd, addr, 10) != 0) {
                val errno = Native.getLastError()
                LibC.INSTANCE.close(fd)
                throw IOException("channel $channel refused (errno $errno)")
            }
            val timeout = Memory(16).apply { setLong(0, 0); setLong(8, READ_POLL_MS * 1000L) }
            LibC.INSTANCE.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout, 16)
            return RfcommSocket(fd)
        }
    }

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun socket(domain: Int, type: Int, protocol: Int): Int
        fun connect(fd: Int, addr: Pointer, len: Int): Int
        fun read(fd: Int, buf: ByteArray, count: Long): Long
        fun write(fd: Int, buf: ByteArray, count: Long): Long
        fun setsockopt(fd: Int, level: Int, name: Int, value: Pointer, len: Int): Int
        fun close(fd: Int): Int

        companion object {
            val INSTANCE: LibC = Native.load("c", LibC::class.java)
        }
    }
}

internal class LinuxRfcommTransport(private val socket: RfcommSocket) : ByteTransport {
    @Volatile private var open = true
    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = flow {
        while (open) {
            val chunk = try {
                socket.read()
            } catch (e: IOException) {
                if (!open) break
                open = false
                throw e
            }
            when {
                chunk == null -> { open = false; throw IOException("the headset closed the control channel") }
                chunk.isNotEmpty() -> emit(chunk)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!open) throw IOException("control channel closed")
        socket.write(bytes)
    }

    override fun close() {
        open = false
        socket.close()
    }
}
