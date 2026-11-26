package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.LogRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A vendor's GATT byte pipe through `ruru-ble-win.exe`, as a [ByteTransport]: the helper holds the
 * Bluetooth LE link, Ruru sends it 'W' frames and gets one 'N' frame per notification. Each write
 * is cut to what one ATT write carries and waits for the helper's result before the next, so a long
 * frame arrives whole and in order. Notifications come out in arrival order, one per element;
 * joining them into frames is the vendor codec's job (or [io.github.langkurst.ruru.link.LeTransport]'s
 * for Bose), exactly as with a socket.
 *
 * By default notifications go to [incoming]; a [sink] takes them instead (Bose LE puts a
 * [io.github.langkurst.ruru.link.LeTransport] in front). Call [start] once the sink is in place.
 */
class WinGattTransport internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    opened: WinBleProtocol.Opened,
    private val name: String,
    private val stopProcess: () -> Unit = {},
    private val writeTimeoutMs: Long = 10_000,
) : ByteTransport {
    internal constructor(process: Process, opened: WinBleProtocol.Opened, name: String) :
        this(process.inputStream, process.outputStream, opened, name, stopProcess = {
            // The helper switches notifications off and exits by itself; it is stopped if it hasn't soon.
            Thread({ if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroy() }, "ruru-ble-stop")
                .apply { isDaemon = true }.start()
        })

    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val results = Channel<String?>(Channel.UNLIMITED)
    private val writing = Mutex()
    @Volatile private var open = true
    @Volatile private var started = false
    @Volatile private var pdu = opened.maxPdu

    /** Where notifications go instead of [incoming]; set before [start]. */
    @Volatile var sink: ((ByteArray) -> Unit)? = null

    /** Called once when the link drops underneath (not on [close]). */
    @Volatile var onDrop: ((String) -> Unit)? = null

    /** The bytes one ATT write carries on this link now. */
    val maxWrite: Int get() = WinBleProtocol.writeRoom(pdu)

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** Starts reading the helper's frames. */
    fun start() {
        check(!started) { "already started" }
        started = true
        Thread(::readLoop, "ruru-ble-gatt").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        val reason = try {
            while (true) {
                val frame = WinBleProtocol.readFrame(input) ?: break
                when (frame.type) {
                    WinBleProtocol.NOTIFY -> sink?.invoke(frame.payload) ?: inbox.trySend(frame.payload)
                    WinBleProtocol.WRITTEN -> results.trySend(WinBleProtocol.writeFailure(frame.payload))
                    WinBleProtocol.MAX_PDU -> WinBleProtocol.maxPdu(frame.payload)?.let {
                        pdu = it
                        LogRing.add('I', WinBleHelper.TAG, "$name: max PDU now $it")
                    }
                    WinBleProtocol.DROPPED -> {
                        dropped(frame.payload.decodeToString().ifBlank { "the Bluetooth LE link dropped" })
                        return
                    }
                    else -> LogRing.add('W', WinBleHelper.TAG, "$name: unknown frame ${frame.type}")
                }
            }
            "the Bluetooth LE helper stopped"
        } catch (e: IOException) {
            e.message ?: "the Bluetooth LE helper stopped"
        }
        dropped(reason)
    }

    private fun dropped(reason: String) {
        if (!open) return
        open = false
        LogRing.add('I', WinBleHelper.TAG, "$name: $reason")
        // Tear the link down first, then tell readers: whoever sees the stream end finds the helper already stopped.
        runCatching { output.close() }
        stopProcess()
        results.close(IOException(reason))
        inbox.close(IOException(reason))
        onDrop?.invoke(reason)
    }

    override suspend fun write(bytes: ByteArray) = writing.withLock {
        for (chunk in WinBleProtocol.chunks(bytes, maxWrite)) {
            if (!open) throw IOException("The Bluetooth LE link is closed")
            withContext(Dispatchers.IO) {
                output.write(WinBleProtocol.encodeFrame(WinBleProtocol.WRITE, chunk))
                output.flush()
            }
            val failure = withTimeoutOrNull(writeTimeoutMs) {
                try {
                    results.receive() ?: OK
                } catch (e: Exception) {
                    throw IOException(e.message ?: "The Bluetooth LE link is closed", e)
                }
            } ?: throw IOException("The headphones didn't accept a write over Bluetooth LE in time")
            if (failure != OK) throw IOException("The headphones refused a write over Bluetooth LE: $failure")
        }
    }

    override fun close() {
        if (!open) return
        open = false
        inbox.close()
        results.close()
        runCatching {
            output.write(WinBleProtocol.encodeFrame(WinBleProtocol.CLOSE))
            output.flush()
            output.close()
        }
        stopProcess()
    }

    private companion object {
        const val OK = ""
    }
}
