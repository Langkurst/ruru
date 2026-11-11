package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.ByteTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The shared session's byte pipe over a Windows RFCOMM socket. Reads block on a background thread
 * with a short timeout so [close] is noticed quickly; a dropped link ends [incoming] with the error.
 */
class WinRfcommTransport(private val socket: WinBluetooth.RfcommSocket) : ByteTransport {
    @Volatile private var open = true

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = flow {
        while (open) {
            val chunk = try {
                socket.read()
            } catch (e: IOException) {
                if (!open) break // closed by us: end quietly
                open = false
                throw e
            }
            when {
                chunk == null -> { open = false; throw IOException("the headset closed the control channel") }
                chunk.isEmpty() -> Unit // read timeout: loop and check [open]
                else -> emit(chunk)
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

    companion object {
        /** How long one blocking read waits before checking whether the transport was closed. */
        const val READ_POLL_MS = 250

        /** Opens the Serial Port channel of [device] and wraps it. */
        fun open(device: WinBluetooth.Device): WinRfcommTransport =
            WinRfcommTransport(WinBluetooth.openSerialPort(device, readTimeoutMs = READ_POLL_MS))
    }
}
