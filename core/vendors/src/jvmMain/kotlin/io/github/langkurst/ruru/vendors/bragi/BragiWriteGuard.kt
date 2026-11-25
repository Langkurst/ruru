package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.vendors.bragi.BragiServices as S

/**
 * The last check before a Skullcandy call is written, on the connection itself. Ruru sends only:
 *  - the read-only calls ([BragiServices.READS]: info, read and get methods);
 *  - four writes, each with a body that has the shape Ruru builds: `hearing.mode_configure`,
 *    `hearing.sidetone_configure`, `spatial_audio.mode_set` and `crusher.state_set`;
 *  - the `authenticated_access` handshake (hello, authenticate and the one-time pairing).
 * [BragiServices.ALLOWED] is wider (the equaliser and clock writes the protocol layer can build,
 * which Ruru never sends); everything outside this guard is refused before a byte leaves, the
 * firmware-update surface and the services Ruru doesn't know included. A call to a method number
 * nobody documented is the dangerous one: BOS-RPC numbers methods per service.
 */
object BragiWriteGuard {
    /** The writes a session may send. */
    val WRITES: Set<S.Address> = setOf(S.MODE_CONFIGURE, S.SIDETONE_CONFIGURE, S.SPATIAL_MODE_SET, S.CRUSHER_STATE_SET)

    /** The pairing and secure-session handshake: not settings, not reads. */
    val HANDSHAKE: Set<S.Address> = setOf(
        S.AUTH_HELLO, S.AUTH_AUTHENTICATE, S.AUTH_TRUST_START, S.AUTH_TRUST_COMMIT, S.AUTH_TRUST_NONCE, S.AUTH_TRUST_CONFIRM, S.AUTH_TRUST_ABORT,
    )

    /** Everything that may be sent at all, whatever its body. */
    val SENDABLE: Set<S.Address> = S.READS + WRITES + HANDSHAKE

    private const val MAX_SMALL_BODY = 16
    private const val MAX_HANDSHAKE_BODY = 512
    private const val MAX_BYTE = 255
    private const val MAX_FEEL = 15

    /** True when [address] with [body] is something Ruru sends. */
    fun isAllowed(address: S.Address, body: ByteArray): Boolean = try {
        when (address) {
            in S.READS -> body.size <= MAX_SMALL_BODY
            in HANDSHAKE -> body.size <= MAX_HANDSHAKE_BODY
            S.MODE_CONFIGURE -> modeConfigure(body)
            S.SIDETONE_CONFIGURE -> sidetoneConfigure(body)
            S.SPATIAL_MODE_SET -> spatialModeSet(body)
            S.CRUSHER_STATE_SET -> crusherStateSet(body)
            else -> false
        }
    } catch (_: IllegalArgumentException) {
        false // a body that doesn't parse as protobuf
    }

    /** `{1: mode (OFF, ANC or AT), 2: activate, 3: preset?, 4: gain?}`; OFF carries no preset or gain. */
    private fun modeConfigure(body: ByteArray): Boolean {
        val f = ProtoReader.map(body)
        if (!f.keys.all { it in 1..4 } || body.size > MAX_SMALL_BODY) return false
        val mode = f[1]?.int ?: return false
        if (mode !in HearingMode.OFF.wire..HearingMode.AT.wire) return false
        if (mode == HearingMode.OFF.wire && (f[3] != null || f[4] != null)) return false
        return (f[3]?.int ?: 0) in 0..MAX_BYTE && (f[4]?.int ?: 0) in 0..MAX_BYTE
    }

    /** `{1: enabled?, 2: volume?}`; at least one of them. */
    private fun sidetoneConfigure(body: ByteArray): Boolean {
        val f = ProtoReader.map(body)
        return body.size <= MAX_SMALL_BODY && f.isNotEmpty() && f.keys.all { it in 1..2 } && (f[2]?.int ?: 0) in 0..MAX_BYTE
    }

    /** `{1: mode (OFF, STILL or MOTION)}`. */
    private fun spatialModeSet(body: ByteArray): Boolean {
        val f = ProtoReader.map(body)
        return body.size <= MAX_SMALL_BODY && f.keys == setOf(1) && f.getValue(1).int in SpatialMode.OFF.wire..SpatialMode.MOTION.wire
    }

    /** `{1: active?, 2: intensity?, 3: feel?}`; at least one of them. */
    private fun crusherStateSet(body: ByteArray): Boolean {
        val f = ProtoReader.map(body)
        return body.size <= MAX_SMALL_BODY && f.isNotEmpty() && f.keys.all { it in 1..3 } &&
            (f[2]?.int ?: 0) in 0..MAX_BYTE && (f[3]?.int ?: 0) in 0..MAX_FEEL
    }
}
