package io.github.langkurst.ruru.vendors.jbl

/** Byte helpers for the JBL code, common to every target (no `java.nio`, no `String.format`). */
internal object JblBytes {
    /** [value] as [digits] upper-case hex digits. */
    fun hex(value: Int, digits: Int = 2): String = value.toString(16).uppercase().padStart(digits, '0')

    /** The four bytes of [value], little-endian, written at [at]. */
    fun putFloat(out: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (i in 0 until 4) out[at + i] = (bits ushr (8 * i)).toByte()
    }

    /** The little-endian float at [at]. */
    fun getFloat(bytes: ByteArray, at: Int): Float {
        var bits = 0
        for (i in 0 until 4) bits = bits or ((bytes[at + i].toInt() and 0xFF) shl (8 * i))
        return Float.fromBits(bits)
    }
}
