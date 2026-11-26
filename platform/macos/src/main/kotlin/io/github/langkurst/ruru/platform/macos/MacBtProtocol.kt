package io.github.langkurst.ruru.platform.macos

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetLink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * How Ruru talks to `ruru-bt-mac` (platform/macos/helper): a command line per job, tab-separated
 * answer lines, then, once a GATT link is open, length-prefixed frames both ways (the same frames as
 * the Windows helper). Pure, so the tests cover every line and frame without Bluetooth. Devices are
 * named by the helper's opaque key (a salted hash of the address); no address comes back over the pipe.
 */
object MacBtProtocol {
    /** Ruru to helper: bytes for the write characteristic. */
    const val WRITE: Char = 'W'

    /** Ruru to helper: close the link. */
    const val CLOSE: Char = 'Q'

    /** Helper to Ruru: one notification. */
    const val NOTIFY: Char = 'N'

    /** Helper to Ruru: the result of one write (0x00, or 0x01 and the reason). */
    const val WRITTEN: Char = 'K'

    /** Helper to Ruru: the link dropped (the reason); the helper exits after it. */
    const val DROPPED: Char = 'D'

    const val MAX_PAYLOAD = 0xFFFF

    /** ATT's opcode and handle take three bytes of every write. */
    const val ATT_HEADER = 3

    /** The helper's code for "the headphones do not offer that RFCOMM service" (nothing was opened). */
    const val SERVICE_NOT_FOUND = "service-not-found"

    /** Bose BMAP over LE: service FEBE, the encrypted characteristic for writes and notifications. */
    val BOSE_LE: GattLinkSpec = GattLinkSpec(
        name = "Bose LE",
        service = "0000febe-0000-1000-8000-00805f9b34fb",
        write = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8",
        notify = listOf("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"),
        writeWithResponse = true,
    )

    private val KEY = Regex("[0-9a-f]{16}")
    private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    fun isKey(text: String): Boolean = KEY.matches(text)

    // ---- Command lines (the arguments after the helper's path) ----------------------------------

    fun listArgs(): List<String> = listOf("list")

    /** Bose on the model's own channel (used when the device has no Serial Port record). */
    fun connectArgs(key: String, channel: Int): List<String> {
        require(isKey(key)) { "not a helper device key" }
        require(channel in 1..30) { "not an RFCOMM channel" }
        return listOf("connect", key, channel.toString())
    }

    /** One RFCOMM service by its UUID; the helper finds the channel itself and never guesses one. */
    fun connectUuidArgs(key: String, uuid: String): List<String> {
        require(isKey(key)) { "not a helper device key" }
        require(UUID.matches(uuid)) { "not a lower-case UUID" }
        return listOf("connect-uuid", key, uuid)
    }

    /** The `gatt` command for [spec] on the device with [key]. The spec already refused firmware services. */
    fun gattArgs(key: String, spec: GattLinkSpec): List<String> {
        require(isKey(key)) { "not a helper device key" }
        return listOf("gatt", key, spec.service, spec.write) + spec.notify +
            (if (spec.writeWithResponse) emptyList() else listOf("--without-response"))
    }

    // ---- Frames ---------------------------------------------------------------------------------

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
        if (hi < 0 || lo < 0) throw IOException("the Bluetooth helper stopped mid-frame")
        val size = (hi shl 8) or lo
        val payload = input.readNBytes(size)
        if (payload.size != size) throw IOException("the Bluetooth helper stopped mid-frame")
        return Frame(type.toChar(), payload)
    }

    /** A 'K' frame: null when the write went through, else the reason. */
    fun writeFailure(payload: ByteArray): String? = when {
        payload.isEmpty() -> "the helper gave no write result"
        payload[0].toInt() == 0 -> null
        else -> payload.copyOfRange(1, payload.size).decodeToString().ifBlank { "write refused" }
    }

    /** Bytes one write may carry for a link whose maximum PDU is [maxPdu] (at least the 20 of the smallest link). */
    fun writeRoom(maxPdu: Int): Int = (maxPdu - ATT_HEADER).coerceAtLeast(GattLinkSpec.MIN_MTU - ATT_HEADER)

    /** [bytes] cut into writes of at most [room] bytes, in order. */
    fun chunks(bytes: ByteArray, room: Int): List<ByteArray> {
        require(room > 0)
        if (bytes.isEmpty()) return emptyList()
        return (bytes.indices step room).map { bytes.copyOfRange(it, minOf(it + room, bytes.size)) }
    }

    // ---- Lines ----------------------------------------------------------------------------------

    /** One answer line, read a byte at a time so the frames after an OPEN line stay in the stream. Null at the end. */
    fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 64 * 1024) throw IOException("the Bluetooth helper sent an over-long line")
        }
        return out.toString(Charsets.UTF_8)
    }

    /**
     * A paired device from `list`: [classOfDevice] is Bluetooth's class word (0 for an LE-only device),
     * [uuids] the service UUIDs macOS has cached for it.
     */
    class Device(
        val key: String,
        val connected: Boolean,
        val link: HeadsetLink,
        val classOfDevice: Int,
        val uuids: List<String>,
        val name: String,
    ) {
        override fun toString(): String = "Device($name, connected=$connected, $link)"
    }

    /** The link is open: [maxPdu] as the helper reports it, and whether writes wait for a response. */
    data class Opened(val maxPdu: Int, val withResponse: Boolean)

    /** The helper refused or failed: a short [code] (`not-paired`, `service-not-found`, ...) and its words. */
    data class Failure(val code: String, val words: String)

    sealed class Answer {
        class Dev(val device: Device) : Answer()
        data class End(val count: Int) : Answer()
        data class Open(val opened: Opened) : Answer()
        data class Err(val failure: Failure) : Answer()
        data class Other(val line: String) : Answer()
    }

    /** One line of `list` or `gatt` on stdout, or an `ERR` line from any command. */
    fun parse(line: String): Answer {
        val f = line.split('\t')
        return when (f[0]) {
            "DEV" -> parseDevice(f) ?: Answer.Other(line)
            "END" -> f.getOrNull(1)?.toIntOrNull()?.let { Answer.End(it) } ?: Answer.Other(line)
            "OPEN" -> {
                val pdu = f.getOrNull(1)?.toIntOrNull()
                val response = f.getOrNull(2)
                if (pdu != null && pdu > 0 && (response == "0" || response == "1")) Answer.Open(Opened(pdu, response == "1"))
                else Answer.Other(line)
            }
            "ERR" -> Answer.Err(
                Failure(f.getOrNull(1).orEmpty().ifBlank { "failed" }, f.drop(2).joinToString("\t").ifBlank { "no reason given" }),
            )
            else -> Answer.Other(line)
        }
    }

    private fun parseDevice(f: List<String>): Answer.Dev? {
        if (f.size < 7 || !isKey(f[1]) || (f[2] != "0" && f[2] != "1")) return null
        val link = when (f[3]) {
            "classic" -> HeadsetLink.CLASSIC
            "le" -> HeadsetLink.LE
            else -> return null
        }
        val cod = f[4].toLongOrNull(16)?.toInt() ?: return null
        val uuids = f[5].split(',').map { it.trim() }.filter { UUID.matches(it) }
        return Answer.Dev(Device(f[1], f[2] == "1", link, cod, uuids, f.drop(6).joinToString("\t").trim()))
    }

    /**
     * The first line a `connect` or `connect-uuid` helper writes on stderr (stdout carries the
     * headset's bytes): `OPEN <channel>` or `ERR<TAB>code<TAB>words`. [Answer.Open] carries the
     * channel number in `maxPdu` (RFCOMM has no PDU; the field is unused otherwise).
     */
    fun parseRfcommStatus(line: String): Answer = when {
        line.startsWith("OPEN") -> Answer.Open(Opened(maxPdu = line.removePrefix("OPEN").trim().toIntOrNull() ?: 0, withResponse = false))
        line.startsWith("ERR") -> parse(line)
        else -> Answer.Other(line)
    }
}

/** The helper refused or could not open the link; [failure] has its code and words (English, for logs and the probe). */
class MacBtException(val failure: MacBtProtocol.Failure) : IOException("${failure.words} (${failure.code})")
