package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.GattLinkSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * How Ruru talks to `ruru-ble-win.exe` (platform/windows/helper): command lines in, tab-separated
 * answer lines out, then, once a GATT link is open, length-prefixed frames both ways. Pure, so the
 * tests cover every line and frame without Bluetooth. Devices are named by the helper's opaque key
 * (a salted hash of the address); no address ever comes back over this pipe.
 */
object WinBleProtocol {
    /** Ruru to helper: bytes for the write characteristic. */
    const val WRITE: Char = 'W'

    /** Ruru to helper: close the link. */
    const val CLOSE: Char = 'Q'

    /** Helper to Ruru: one notification. */
    const val NOTIFY: Char = 'N'

    /** Helper to Ruru: the result of one write (0x00, or 0x01 and the reason). */
    const val WRITTEN: Char = 'K'

    /** Helper to Ruru: the link's new maximum PDU (two bytes, big-endian). */
    const val MAX_PDU: Char = 'M'

    /** Helper to Ruru: the link dropped (the reason); the helper exits after it. */
    const val DROPPED: Char = 'D'

    const val MAX_PAYLOAD = 0xFFFF

    /** ATT's opcode and handle take three bytes of every write. */
    const val ATT_HEADER = 3

    /** Bose BMAP over LE: service FEBE, the encrypted characteristic for writes and notifications (iPhone route). */
    val BOSE_LE: GattLinkSpec = GattLinkSpec(
        name = "Bose LE",
        service = "0000febe-0000-1000-8000-00805f9b34fb",
        write = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8",
        notify = listOf("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"),
        writeWithResponse = true,
    )

    private val KEY = Regex("[0-9a-f]{16}")

    fun isKey(text: String): Boolean = KEY.matches(text)

    /** The `gatt` command for [spec] on the device with [key]. The spec already refused firmware services. */
    fun gattCommand(key: String, spec: GattLinkSpec): String {
        require(isKey(key)) { "not a helper device key" }
        val parts = listOf("gatt", key, spec.service, spec.write) + spec.notify +
            (if (spec.writeWithResponse) emptyList() else listOf("--without-response"))
        return parts.joinToString(" ")
    }

    fun scanCommand(seconds: Int, active: Boolean): String =
        "scan ${seconds.coerceIn(1, 30)}" + if (active) " active" else ""

    // ---- Frames -------------------------------------------------------------------------------

    class Frame(val type: Char, val payload: ByteArray) {
        override fun toString(): String = "Frame($type, ${payload.size} bytes)"
    }

    fun encodeFrame(type: Char, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "a frame holds at most $MAX_PAYLOAD bytes, not ${payload.size}" }
        val out = ByteArray(3 + payload.size)
        out[0] = type.code.toByte()
        out[1] = (payload.size shr 8).toByte()
        out[2] = payload.size.toByte()
        payload.copyInto(out, 3)
        return out
    }

    /** The next frame from [input], or null at a clean end of stream. A frame cut short throws. */
    fun readFrame(input: InputStream): Frame? {
        val type = input.read()
        if (type < 0) return null
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) throw IOException("the Bluetooth LE helper stopped mid-frame")
        val size = (hi shl 8) or lo
        val payload = input.readNBytes(size)
        if (payload.size != size) throw IOException("the Bluetooth LE helper stopped mid-frame")
        return Frame(type.toChar(), payload)
    }

    /** A 'K' frame: null when the write went through, else the reason. */
    fun writeFailure(payload: ByteArray): String? = when {
        payload.isEmpty() -> "the helper gave no write result"
        payload[0].toInt() == 0 -> null
        else -> payload.copyOfRange(1, payload.size).decodeToString().ifBlank { "write refused" }
    }

    fun maxPdu(payload: ByteArray): Int? =
        if (payload.size == 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else null

    /** Bytes one write may carry for a link whose maximum PDU is [maxPdu] (at least the 20 of the smallest link). */
    fun writeRoom(maxPdu: Int): Int = (maxPdu - ATT_HEADER).coerceAtLeast(GattLinkSpec.MIN_MTU - ATT_HEADER)

    /** [bytes] cut into writes of at most [room] bytes, in order. */
    fun chunks(bytes: ByteArray, room: Int): List<ByteArray> {
        require(room > 0)
        if (bytes.isEmpty()) return emptyList()
        return (bytes.indices step room).map { bytes.copyOfRange(it, minOf(it + room, bytes.size)) }
    }

    // ---- Lines --------------------------------------------------------------------------------

    /**
     * One answer line, read a byte at a time so the frames after an OPEN line stay in the stream.
     * Null at the end of the stream.
     */
    fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 64 * 1024) throw IOException("the Bluetooth LE helper sent an over-long line")
        }
        return out.toString(Charsets.UTF_8)
    }

    /** A paired Bluetooth LE device from `list`. */
    data class Device(val key: String, val connected: Boolean, val name: String)

    /** One advertiser's manufacturer section (or none, [companyId] null) from `scan`. */
    class Advert(val key: String, val companyId: Int?, val data: ByteArray, val name: String?)

    /** The link is open: [maxPdu] as Windows reports it, and whether writes wait for a response. */
    data class Opened(val maxPdu: Int, val withResponse: Boolean)

    /** The helper refused or failed: a short [code] (`no-device`, `not-paired`, ...) and its words. */
    data class Failure(val code: String, val words: String)

    sealed class Answer {
        data class Dev(val device: Device) : Answer()
        class Adv(val advert: Advert) : Answer()
        data class End(val count: Int) : Answer()
        data class Open(val opened: Opened) : Answer()
        data class Err(val failure: Failure) : Answer()
        data class Other(val line: String) : Answer()
    }

    fun parse(line: String): Answer {
        val f = line.split('\t')
        return when (f[0]) {
            "DEV" -> if (f.size >= 4 && isKey(f[1]) && (f[2] == "0" || f[2] == "1")) {
                Answer.Dev(Device(f[1], f[2] == "1", f.subList(3, f.size).joinToString("\t").trim()))
            } else Answer.Other(line)
            "ADV" -> parseAdvert(f) ?: Answer.Other(line)
            "END" -> f.getOrNull(1)?.toIntOrNull()?.let { Answer.End(it) } ?: Answer.Other(line)
            "OPEN" -> {
                val pdu = f.getOrNull(1)?.toIntOrNull()
                val response = f.getOrNull(2)
                if (pdu != null && pdu > 0 && (response == "0" || response == "1")) Answer.Open(Opened(pdu, response == "1"))
                else Answer.Other(line)
            }
            "ERR" -> Answer.Err(Failure(f.getOrNull(1).orEmpty().ifBlank { "failed" }, f.drop(2).joinToString("\t").ifBlank { "no reason given" }))
            else -> Answer.Other(line)
        }
    }

    private fun parseAdvert(f: List<String>): Answer.Adv? {
        if (f.size < 5 || !isKey(f[1])) return null
        val company = when {
            f[2] == "-" -> null
            f[2].length == 4 -> f[2].toIntOrNull(16) ?: return null
            else -> return null
        }
        val data = unhex(f[3]) ?: return null
        val name = f.subList(4, f.size).joinToString("\t").trim().ifEmpty { null }
        return Answer.Adv(Advert(f[1], company, data, name))
    }

    private fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return ByteArray(text.length / 2) { i ->
            val hi = Character.digit(text[2 * i], 16)
            val lo = Character.digit(text[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) or lo).toByte()
        }
    }
}
