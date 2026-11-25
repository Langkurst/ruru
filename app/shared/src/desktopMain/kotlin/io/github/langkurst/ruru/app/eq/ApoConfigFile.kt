package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.dsp.ApoConfigWriter

/**
 * The one edit Ruru makes to Equalizer APO's own `config.txt`: a single `Include: ruru.txt` line, put
 * first so it applies to every device APO is set up for. Everything else in the file is carried over
 * byte for byte (the bytes are read as Latin-1, which maps every byte to one character, so any encoding
 * that keeps line breaks as plain bytes survives unchanged; UTF-16 is refused, see [isUtf16]). Taking
 * the line out again gives back the original bytes.
 */
object ApoConfigFile {
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** UTF-16 files store a zero byte between letters, so lines cannot be found byte by byte. */
    fun isUtf16(bytes: ByteArray): Boolean =
        bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))

    fun hasInclude(bytes: ByteArray): Boolean = lines(body(bytes)).any(::isIncludeLine)

    /** [bytes] with the include line first (after a UTF-8 byte order mark if there is one). Unchanged when it is there already. */
    fun withInclude(bytes: ByteArray): ByteArray {
        if (hasInclude(bytes)) return bytes
        val head = if (hasBom(bytes)) bom else ByteArray(0)
        val rest = body(bytes)
        val eol = firstLineBreak(rest) ?: "\r\n"
        return head + (ApoConfigWriter.INCLUDE_LINE + eol).toByteArray(Charsets.ISO_8859_1) + rest.toByteArray(Charsets.ISO_8859_1)
    }

    /** [bytes] without any include line of Ruru's; every other byte stays. */
    fun withoutInclude(bytes: ByteArray): ByteArray {
        if (!hasInclude(bytes)) return bytes
        val head = if (hasBom(bytes)) bom else ByteArray(0)
        val kept = lines(body(bytes)).filterNot(::isIncludeLine).joinToString("")
        return head + kept.toByteArray(Charsets.ISO_8859_1)
    }

    private fun hasBom(bytes: ByteArray) = bytes.size >= 3 && bytes[0] == bom[0] && bytes[1] == bom[1] && bytes[2] == bom[2]

    private fun body(bytes: ByteArray): String =
        String(bytes, if (hasBom(bytes)) 3 else 0, bytes.size - if (hasBom(bytes)) 3 else 0, Charsets.ISO_8859_1)

    /** Lines with their own line break kept, so joining them gives the text back. */
    private fun lines(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                out += text.substring(start, i + 1)
                start = i + 1
            }
            i++
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    private fun isIncludeLine(line: String) = line.trim().equals(ApoConfigWriter.INCLUDE_LINE, ignoreCase = true)

    private fun firstLineBreak(text: String): String? {
        val at = text.indexOfFirst { it == '\n' || it == '\r' }
        if (at < 0) return null
        return if (text[at] == '\r' && at + 1 < text.length && text[at + 1] == '\n') "\r\n" else text[at].toString()
    }
}
