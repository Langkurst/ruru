package io.github.langkurst.ruru.vendors.bragi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What Ruru adds to Earshot's handshake: a point from the headphones is checked against the curve before any key agreement. */
class BragiHandshakeTest {
    @Test fun `a generated point decodes, and both ends of an ECDH agree`() {
        val a = BragiHandshake.generateEphemeral()
        val b = BragiHandshake.generateEphemeral()
        assertEquals(65, a.publicPoint.size)
        BragiHandshake.decodePublicKey(a.publicPoint)
        assertContentEquals(BragiHandshake.ecdh(a.privateKey, b.publicPoint), BragiHandshake.ecdh(b.privateKey, a.publicPoint))
    }

    @Test fun `a point that is not on the curve is refused, whatever the key factory thinks`() {
        val good = BragiHandshake.generateEphemeral().publicPoint
        // The right length and prefix, the Y coordinate nudged off the curve.
        val off = good.copyOf().also { it[64] = (it[64] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { BragiHandshake.decodePublicKey(off) }
        assertFailsWith<IllegalArgumentException> { BragiHandshake.decodePublicKey(ByteArray(65) { 4 }) }
        // A coordinate at or above the field prime, a compressed point and a short one.
        assertFailsWith<IllegalArgumentException> { BragiHandshake.decodePublicKey(byteArrayOf(4) + ByteArray(64) { 0xFF.toByte() }) }
        assertFailsWith<IllegalArgumentException> { BragiHandshake.decodePublicKey(good.copyOf().also { it[0] = 2 }) }
        assertFailsWith<IllegalArgumentException> { BragiHandshake.decodePublicKey(good.copyOf(33)) }
        // And the key agreement itself never sees one.
        assertFailsWith<IllegalArgumentException> { BragiHandshake.ecdh(BragiHandshake.generateEphemeral().privateKey, off) }
        assertTrue(runCatching { BragiHandshake.decodePublicKey(good) }.isSuccess)
    }
}
