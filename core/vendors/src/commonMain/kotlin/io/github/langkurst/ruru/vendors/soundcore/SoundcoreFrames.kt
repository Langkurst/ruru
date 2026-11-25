package io.github.langkurst.ruru.vendors.soundcore

/**
 * One Soundcore frame (Anker's earbud protocol over RFCOMM). Layout, from the OpenSCQ30 and
 * Gadgetbridge sources (facts only; both are GPL/AGPL, nothing copied) and two captures of the
 * official app on a Sleep A30:
 * ```
 *  phone → buds:  08 EE 00 00 00 | cat code | len u16 LE | body | sum
 *  buds → phone:  09 FF 00 00 01 | cat code | len u16 LE | body | sum
 * ```
 * `len` is the whole frame (body + 10, checksum included); `sum` is every preceding byte added
 * up, mod 256. The command travels as `[category, code]`; codes 0x00..0x7F read, 0x80..0xFF write.
 * Example (request state): `08 EE 00 00 00 01 01 0A 00 02`.
 */
class SoundcorePacket(
    /** `(category shl 8) or code`, e.g. 0x0101 = request state, 0x0687 = Sleep A30 ANC on/off. */
    val command: Int,
    val body: ByteArray = ByteArray(0),
    /** True for a frame the headset sent (`09 FF …`). */
    val inbound: Boolean = false,
) {
    val category: Int get() = command shr 8
    val code: Int get() = command and 0xFF
    val isWrite: Boolean get() = code >= 0x80

    fun encode(): ByteArray {
        val total = body.size + OVERHEAD
        val out = ByteArray(total)
        (if (inbound) IN_PREFIX else OUT_PREFIX).copyInto(out, 0)
        out[5] = category.toByte()
        out[6] = code.toByte()
        out[7] = (total and 0xFF).toByte()
        out[8] = (total shr 8).toByte()
        body.copyInto(out, 9)
        out[total - 1] = checksum(out, total - 1).toByte()
        return out
    }

    override fun toString(): String = "${commandName(command)}${if (inbound) " ←" else " →"} ${body.joinToString("") { hex2(it.toInt()) }}"

    companion object {
        val OUT_PREFIX = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)
        val IN_PREFIX = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

        /** Prefix (5) + command (2) + length (2) + checksum (1). */
        const val OVERHEAD = 10

        fun checksum(bytes: ByteArray, count: Int): Int {
            var sum = 0
            for (i in 0 until count) sum += bytes[i].toInt() and 0xFF
            return sum and 0xFF
        }

        fun commandName(command: Int): String = "${hex2(command shr 8)} ${hex2(command)}"
    }
}

/**
 * Turns the RFCOMM byte stream back into frames. Chunks arrive split and merged arbitrarily; a
 * frame whose checksum fails, or bytes that don't start a frame, are skipped up to the next
 * prefix, so one corrupt read never wedges the link.
 */
class SoundcoreFrameBuffer(private val onJunk: (Int) -> Unit = {}) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<SoundcorePacket> {
        buf += chunk
        val out = ArrayList<SoundcorePacket>()
        while (true) {
            val start = findPrefix()
            if (start < 0) {
                // Keep a possible partial prefix at the tail, drop the rest.
                val keep = minOf(buf.size, SoundcorePacket.IN_PREFIX.size - 1)
                if (buf.size > keep) onJunk(buf.size - keep)
                buf = buf.copyOfRange(buf.size - keep, buf.size)
                return out
            }
            if (start > 0) {
                onJunk(start)
                buf = buf.copyOfRange(start, buf.size)
            }
            if (buf.size < 9) return out
            val total = (buf[7].toInt() and 0xFF) or ((buf[8].toInt() and 0xFF) shl 8)
            if (total < SoundcorePacket.OVERHEAD || total > MAX_FRAME) {
                onJunk(1)
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            if (buf.size < total) return out
            val frame = buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
            if (SoundcorePacket.checksum(frame, total - 1) != (frame[total - 1].toInt() and 0xFF)) {
                onJunk(total)
                continue
            }
            val inbound = frame.copyOfRange(0, 5).contentEquals(SoundcorePacket.IN_PREFIX)
            val command = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
            out += SoundcorePacket(command, frame.copyOfRange(9, total - 1), inbound)
        }
    }

    /** First index where either prefix starts (the virtual device and tests feed outbound frames too). */
    private fun findPrefix(): Int {
        for (i in 0..buf.size - 5) {
            if (matches(i, SoundcorePacket.IN_PREFIX) || matches(i, SoundcorePacket.OUT_PREFIX)) return i
        }
        return -1
    }

    private fun matches(at: Int, prefix: ByteArray): Boolean = prefix.indices.all { buf[at + it] == prefix[it] }

    private companion object {
        /** The Sleep A30 state is 160 bytes; nothing seen is near this. */
        const val MAX_FRAME = 2048
    }
}

