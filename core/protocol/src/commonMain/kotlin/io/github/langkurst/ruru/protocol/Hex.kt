package io.github.langkurst.ruru.protocol

private const val HEX_LOWER = "0123456789abcdef"
private const val HEX_UPPER = "0123456789ABCDEF"

/** Two lower-case hex digits for one byte (`0a`). Common replacement for `"%02x".format(b)`. */
fun Byte.hex2(): String {
    val v = toInt() and 0xFF
    return "${HEX_LOWER[v shr 4]}${HEX_LOWER[v and 0x0F]}"
}

/** Two upper-case hex digits for one byte (`0A`). */
fun Byte.hex2Upper(): String {
    val v = toInt() and 0xFF
    return "${HEX_UPPER[v shr 4]}${HEX_UPPER[v and 0x0F]}"
}
