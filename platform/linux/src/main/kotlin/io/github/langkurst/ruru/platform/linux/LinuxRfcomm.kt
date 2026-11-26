package io.github.langkurst.ruru.platform.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.langkurst.ruru.link.ByteTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

/** A connected RFCOMM socket as [LinuxRfcommTransport] uses it: a real one from libc, or a pretend one in tests. */
internal interface RfcommPort : AutoCloseable {
    fun write(bytes: ByteArray)

    /** Bytes read, an empty array on timeout, null at end of stream. */
    fun read(): ByteArray?
}

/** An RFCOMM socket from libc. Reads time out every [READ_POLL_MS] so a close is noticed. */
internal class RfcommSocket private constructor(private val fd: Int) : RfcommPort {
    @Volatile private var open = true

    override fun write(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val chunk = if (off == 0) bytes else bytes.copyOfRange(off, bytes.size)
            val n = LibC.INSTANCE.write(fd, chunk, chunk.size.toLong())
            if (n <= 0) throw IOException("write failed (errno ${Native.getLastError()})")
            off += n.toInt()
        }
    }

    override fun read(): ByteArray? {
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

        /**
         * Takes over a connected socket BlueZ handed us (Profile1.NewConnection) and gives it the
         * same read timeout as one Ruru opened itself. The socket is Ruru's to close from here.
         */
        fun adopt(fd: Int): RfcommSocket {
            setReadTimeout(fd)
            return RfcommSocket(fd)
        }

        /** Closes a descriptor BlueZ handed us that Ruru will not use. */
        fun closeFd(fd: Int) {
            LibC.INSTANCE.close(fd)
        }

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
            setReadTimeout(fd)
            return RfcommSocket(fd)
        }

        private fun setReadTimeout(fd: Int) {
            val timeout = Memory(16).apply { setLong(0, 0); setLong(8, READ_POLL_MS * 1000L) }
            LibC.INSTANCE.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout, 16)
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

/** An RFCOMM [RfcommPort] as a [ByteTransport]. [onClose] runs once, when the link is closed or drops. */
internal class LinuxRfcommTransport(private val socket: RfcommPort, private val onClose: () -> Unit = {}) : ByteTransport {
    @Volatile private var open = true
    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = flow {
        while (open) {
            // A collector that stops (cancel) ends the loop; reads are blocking and never notice otherwise.
            currentCoroutineContext().ensureActive()
            val chunk = try {
                socket.read()
            } catch (e: IOException) {
                if (!open) break
                shutdown()
                throw e
            }
            when {
                chunk == null -> { shutdown(); throw IOException("the headset closed the control channel") }
                chunk.isNotEmpty() -> emit(chunk)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!open) throw IOException("control channel closed")
        socket.write(bytes)
    }

    override fun close() {
        shutdown()
    }

    private fun shutdown() {
        if (!open) return
        open = false
        runCatching { socket.close() }
        runCatching { onClose() }
    }
}
