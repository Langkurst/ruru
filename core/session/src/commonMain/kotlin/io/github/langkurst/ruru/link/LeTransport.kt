package io.github.langkurst.ruru.link

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.io.IOException
import kotlin.concurrent.Volatile

/**
 * Bose's framing for BMAP over Bluetooth LE (GATT service FEBE). Every write and every
 * notification starts with one header byte: the high four bits are the number of the last piece,
 * the low four bits the number of this piece, so a message that fits in one write starts with 0x00.
 *
 * Seen on QC Ultra Headphones (1st Gen), firmware 1.6.7, 11 November 2026: without the header the
 * headphones accept a write and never answer; with it, six reads answered, and a 51-byte reply came
 * back whole in one notification (header 0x00) when the link allowed long writes. The numbering for
 * longer messages follows bozo's and QuietControl's published code.
 */
object LeFraming {
    /** The header has four bits for the piece number. */
    const val MAX_PIECES = 16

    /** Splits one BMAP packet into writes of at most [maxWrite] bytes, the header byte included. */
    fun split(packet: ByteArray, maxWrite: Int): List<ByteArray> {
        require(maxWrite >= 2) { "a write must hold the header and at least one byte, not $maxWrite" }
        val room = maxWrite - 1
        val pieces = maxOf(1, (packet.size + room - 1) / room)
        require(pieces <= MAX_PIECES) { "a ${packet.size}-byte packet needs $pieces pieces of $room bytes; the header allows $MAX_PIECES" }
        val last = pieces - 1
        return List(pieces) { i ->
            val from = i * room
            val to = minOf(packet.size, from + room)
            byteArrayOf(((last shl 4) or i).toByte()) + packet.copyOfRange(from, to)
        }
    }

    /** Joins notifications back into whole BMAP packets. Feed every notification, in order. */
    class Joiner {
        private val parts = ArrayList<ByteArray>()
        private var last = -1

        /** The finished packet, or null while pieces are missing. A piece out of order drops the message. */
        fun feed(notification: ByteArray): ByteArray? {
            if (notification.isEmpty()) return null
            val header = notification[0].toInt() and 0xFF
            val pieceLast = header shr 4
            val piece = header and 0x0F
            if (piece == 0) {
                parts.clear()
                last = pieceLast
            } else if (piece != parts.size || pieceLast != last) {
                parts.clear()
                last = -1
                return null
            }
            parts += notification.copyOfRange(1, notification.size)
            if (piece < pieceLast) return null
            var whole = ByteArray(0)
            for (p in parts) whole += p
            parts.clear()
            last = -1
            return whole
        }
    }
}

/**
 * A [ByteTransport] over Bose's LE characteristic, for platforms with no serial channel (the
 * iPhone). Each BMAP packet written goes out as headed pieces through [writePiece]; the platform
 * hands every notification to [deliver], and whole packets come out of [incoming], so
 * [BmapConnection] sees an ordinary byte stream.
 */
class LeTransport(
    private val maxWrite: () -> Int,
    private val writePiece: suspend (ByteArray) -> Unit,
    private val onClose: () -> Unit = {},
) : ByteTransport {
    private val joiner = LeFraming.Joiner()
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var open = true

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** One notification from the characteristic, in the order it arrived. */
    fun deliver(notification: ByteArray) {
        joiner.feed(notification)?.let { inbox.trySend(it) }
    }

    /** The link dropped underneath: [incoming] ends with this as its error. */
    fun dropped(reason: String) {
        if (!open) return
        open = false
        inbox.close(IOException(reason))
    }

    override suspend fun write(bytes: ByteArray) {
        if (!open) throw IOException("The Bluetooth LE link is closed")
        for (piece in LeFraming.split(bytes, maxWrite())) writePiece(piece)
    }

    override fun close() {
        if (!open) return
        open = false
        inbox.close()
        onClose()
    }
}
