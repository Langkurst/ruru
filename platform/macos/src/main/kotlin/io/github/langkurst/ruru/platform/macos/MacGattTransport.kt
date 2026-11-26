package io.github.langkurst.ruru.platform.macos

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
 * A vendor's GATT byte pipe through `ruru-bt-mac gatt`, as a [ByteTransport]: the helper holds the
 * CoreBluetooth link, Ruru sends it 'W' frames and gets one 'N' frame per notification. Each write
 * is cut to what one ATT write carries and waits for the helper's result before the next, so a long
 * frame arrives whole and in order. Joining notifications into frames is the vendor codec's job (or
 * [io.github.langkurst.ruru.link.LeTransport]'s for Bose), exactly as with a socket. The same
 * design as the Windows `WinGattTransport`; the two modules share no code yet because each helper's
 * module stands alone.
 *
 * By default notifications go to [incoming]; a [sink] takes them instead (Bose LE puts a
 * [io.github.langkurst.ruru.link.LeTransport] in front). Call [start] once the sink is in place.
 */
class MacGattTransport internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    opened: MacBtProtocol.Opened,
    private val name: String,
    private val stopProcess: () -> Unit = {},
    private val writeTimeoutMs: Long = 10_000,
) : ByteTransport {
    internal constructor(process: Process, opened: MacBtProtocol.Opened, name: String) :
        this(process.inputStream, process.outputStream, opened, name, stopProcess = {
            // The helper cancels the link and exits by itself; it is stopped if it hasn't soon.
            Thread({ if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroy() }, "ruru-bt-stop")
                .apply { isDaemon = true }.start()
        })

    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val results = Channel<String?>(Channel.UNLIMITED)
    private val writing = Mutex()
    @Volatile private var open = true
    @Volatile private var started = false
    private val pdu = opened.maxPdu

    /** Where notifications go instead of [incoming]; set before [start]. */
    @Volatile var sink: ((ByteArray) -> Unit)? = null

    /** Called once when the link drops underneath (not on [close]). */
    @Volatile var onDrop: ((String) -> Unit)? = null

    /** The bytes one ATT write carries on this link. */
    val maxWrite: Int get() = MacBtProtocol.writeRoom(pdu)

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** Starts reading the helper's frames. */
    fun start() {
        check(!started) { "already started" }
        started = true
        Thread(::readLoop, "ruru-bt-gatt").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        val reason = try {
            while (true) {
                val frame = MacBtProtocol.readFrame(input) ?: break
                when (frame.type) {
                    MacBtProtocol.NOTIFY -> sink?.invoke(frame.payload) ?: inbox.trySend(frame.payload)
                    MacBtProtocol.WRITTEN -> results.trySend(MacBtProtocol.writeFailure(frame.payload))
                    MacBtProtocol.DROPPED -> {
                        dropped(frame.payload.decodeToString().ifBlank { "the Bluetooth LE link dropped" })
                        return
                    }
                    else -> LogRing.add('W', MacBtHelper.TAG, "$name: unknown frame ${frame.type}")
                }
            }
            "the Bluetooth helper stopped"
        } catch (e: IOException) {
            e.message ?: "the Bluetooth helper stopped"
        }
        dropped(reason)
    }

    private fun dropped(reason: String) {
        if (!open) return
        open = false
        LogRing.add('I', MacBtHelper.TAG, "$name: $reason")
        results.close(IOException(reason))
        inbox.close(IOException(reason))
        onDrop?.invoke(reason)
        runCatching { output.close() }
        stopProcess()
    }

    override suspend fun write(bytes: ByteArray) = writing.withLock {
        for (chunk in MacBtProtocol.chunks(bytes, maxWrite)) {
            if (!open) throw IOException("The Bluetooth LE link is closed")
            withContext(Dispatchers.IO) {
                output.write(MacBtProtocol.encodeFrame(MacBtProtocol.WRITE, chunk))
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
            output.write(MacBtProtocol.encodeFrame(MacBtProtocol.CLOSE))
            output.flush()
            output.close()
        }
        stopProcess()
    }

    private companion object {
        const val OK = ""
    }
}
