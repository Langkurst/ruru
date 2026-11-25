package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The frame reader and the transmit wrapper (Earshot's BragiLinkTest, MIT). */
class BragiLinkTest {

    private fun frame(tag: Int) = PppFraming.frame(BosRpcPacket.request(BragiServices.DEV_INFO, tag).encode())

    @Test fun `reader returns frames split across chunks`() {
        val f = frame(1)
        val r = BragiFrameReader()
        assertTrue(r.feed(f.copyOfRange(0, 5)).isEmpty())
        assertTrue(r.feed(f.copyOfRange(5, 9)).isEmpty())
        val out = r.feed(f.copyOfRange(9, f.size))
        assertEquals(1, out.size)
        assertEquals(PppFraming.PROTOCOL_CLEARTEXT, out.single().protocolId)
        assertEquals(0, r.pendingSize)
    }

    @Test fun `reader returns two frames glued together`() {
        val out = BragiFrameReader().feed(frame(1) + frame(2))
        assertEquals(2, out.size)
    }

    @Test fun `reader skips a vendor header and noise in front of and between frames`() {
        val vendor = byteArrayOf(0x05, 0x5D, 0x0D, 0x00, 0x01, 0x0D)
        val skipped = ArrayList<Int>()
        val out = BragiFrameReader(onSkipped = { skipped += it }).feed(vendor + frame(1) + byteArrayOf(0x7E, 0x00, 0x01) + vendor + frame(2))
        assertEquals(2, out.size)
        assertTrue(skipped.sum() > 0)
    }

    @Test fun `reader resyncs after a corrupt frame`() {
        val bad = frame(1).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val out = BragiFrameReader().feed(bad + frame(2))
        assertEquals(1, out.size)
        assertEquals(0x10, BosRpcPacket.decode(out.single().body).flags)
        assertEquals(2, BosRpcPacket.decode(out.single().body).tag)
    }

    @Test fun `reader never keeps more than its buffer limit`() {
        val r = BragiFrameReader(maxBuffer = 64)
        // Garbage that is not a frame is skipped, so the buffer cannot grow without bound.
        val hdr = byteArrayOf(0x7E, 0xFF.toByte(), 0x80.toByte(), 0x94.toByte(), 0xEA.toByte(), 0x60)
        r.feed(hdr)
        r.feed(ByteArray(200))
        assertTrue(r.pendingSize <= 64)
    }

    @Test fun `the raw wrapper changes nothing, and BOS-RPC is never RACE-wrapped`() {
        val f = frame(0)
        assertEquals(f.toHex(), RawPppWrapper.wrap(f).toHex())
        // The dev_info call, as the maker's app frames it.
        assertEquals("7eff80940004ce10020100ab00", f.toHex())
    }
}
