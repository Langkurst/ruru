package io.github.langkurst.ruru.link

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.io.IOException
import kotlin.concurrent.Volatile

/**
 * The pure parts of a GATT byte pipe that every platform shares: how a frame is cut into writes,
 * which write type and which notify type a characteristic's property bits allow (the bits are the
 * same on Android and CoreBluetooth). Same rules as Android's GattTransport.
 */
object GattWire {
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08
    /** The smallest write payload: the 23-byte minimum ATT MTU less its 3-byte header. */
    const val MIN_WRITE = 20
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20

    /** Cuts [bytes] into writes of at most [maxWrite] bytes, in order. Nothing for an empty frame. */
    fun split(bytes: ByteArray, maxWrite: Int): List<ByteArray> {
        require(maxWrite >= 1) { "a write must hold at least one byte, not $maxWrite" }
        if (bytes.isEmpty()) return emptyList()
        return (bytes.indices step maxWrite).map { start -> bytes.copyOfRange(start, minOf(start + maxWrite, bytes.size)) }
    }

    /** True for a write with response, false for one without, null when the characteristic takes neither. */
    fun chooseWriteType(wantWithResponse: Boolean, props: Int): Boolean? {
        val canWithResponse = props and PROPERTY_WRITE != 0
        val canWithout = props and PROPERTY_WRITE_NO_RESPONSE != 0
        return when {
            wantWithResponse && canWithResponse -> true
            !wantWithResponse && canWithout -> false
            canWithResponse -> true
            canWithout -> false
            else -> null
        }
    }

    /** False to notify, true to indicate, null when the characteristic does neither. */
    fun chooseIndicate(props: Int): Boolean? = when {
        props and PROPERTY_NOTIFY != 0 -> false
        props and PROPERTY_INDICATE != 0 -> true
        else -> null
    }
}

/**
 * A vendor's byte pipe over GATT as a [ByteTransport], for platforms whose system API delivers
 * callbacks (the iPhone). [write] cuts a frame into pieces of at most the current write size and
 * sends them one after another through [writePiece], which returns once the piece was accepted, so
 * a long frame arrives whole and in order. The platform hands every notification to [deliver], in
 * arrival order, one chunk per notification; reassembling frames is the vendor codec's job, as
 * with a socket.
 */
class GattByteTransport(
    private val maxWrite: () -> Int,
    private val writePiece: suspend (ByteArray) -> Unit,
    private val onClose: () -> Unit = {},
) : ByteTransport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var open = true

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** One notification, in the order it arrived. Empty ones carry nothing and are dropped. */
    fun deliver(notification: ByteArray) {
        if (!open || notification.isEmpty()) return
        inbox.trySend(notification.copyOf())
    }

    /** The link dropped underneath: [incoming] ends with this as its error. */
    fun dropped(reason: String) {
        if (!open) return
        open = false
        inbox.close(IOException(reason))
    }

    override suspend fun write(bytes: ByteArray) {
        if (!open) throw IOException("The Bluetooth LE link is closed")
        for (piece in GattWire.split(bytes, maxWrite())) {
            if (!open) throw IOException("The Bluetooth LE link is closed")
            writePiece(piece)
        }
    }

    override fun close() {
        if (!open) return
        open = false
        inbox.close()
        onClose()
    }
}
