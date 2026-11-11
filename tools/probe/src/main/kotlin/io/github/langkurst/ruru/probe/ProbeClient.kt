package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.platform.windows.WinBluetooth
import io.github.langkurst.ruru.protocol.BmapFrameBuffer
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.FunctionAddress

/** One request at a time over an open control channel, with every frame the headset sends logged. */
class ProbeClient(private val socket: WinBluetooth.RfcommSocket, private val verbose: Boolean = true) {
    private val frames = BmapFrameBuffer()
    private val queue = ArrayDeque<BmapPacket>()
    private val started = System.nanoTime()

    fun ms(): Long = (System.nanoTime() - started) / 1_000_000
    val framingErrors: Int get() = frames.desyncCount

    private fun next(untilMs: Long): BmapPacket? {
        while (true) {
            queue.removeFirstOrNull()?.let { return it }
            if (ms() >= untilMs) return null
            val chunk = socket.read() ?: return null
            if (chunk.isNotEmpty()) queue.addAll(frames.feed(chunk))
        }
    }

    /** Everything the headset sends for [forMs], e.g. the pushes after a change. */
    fun drain(forMs: Long): List<BmapPacket> {
        val out = ArrayList<BmapPacket>()
        val until = ms() + forMs
        while (true) out.add(next(until) ?: return out)
    }

    /**
     * Send [packet] and return the first frame on the same address that ends the exchange:
     * STATUS or ERROR for GET / SET_GET; RESULT, STATUS or ERROR for START (PROCESSING is logged
     * and waited past). Other frames are logged as pushes.
     */
    fun request(packet: BmapPacket, timeoutMs: Long = 2_500): BmapPacket? {
        socket.write(packet.encode())
        if (verbose) println("      -> $packet")
        val until = ms() + timeoutMs
        while (true) {
            val f = next(until) ?: return null.also { if (verbose) println("      <- (no reply in $timeoutMs ms)") }
            val ours = f.matches(packet.address)
            if (verbose) println("      <- $f${if (ours) "" else "   (push)"}")
            if (!ours) continue
            val done = when (packet.operator) {
                BmapOperator.START -> f.operator == BmapOperator.RESULT || f.operator == BmapOperator.STATUS || f.operator == BmapOperator.ERROR
                else -> f.operator == BmapOperator.STATUS || f.operator == BmapOperator.ERROR
            }
            if (done) return f
        }
    }

    fun get(address: FunctionAddress, payload: ByteArray = BmapPacket.EMPTY) =
        request(BmapPacket(address.block, address.function, BmapOperator.GET, payload))

    fun setGet(address: FunctionAddress, payload: ByteArray) = request(BmapPacket.setGet(address, payload))

    fun start(address: FunctionAddress, payload: ByteArray) = request(BmapPacket.start(address, payload), timeoutMs = 4_000)
}
