package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.link.WriteGuard
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeReadOnlyTest {
    private val probeDir = File("src/main/kotlin/io/github/langkurst/ruru/probe")

    @Test
    fun everyNewReadIsAGetAndPassesWriteGuard() {
        val packets = PlaybackReads.all.map { ProbeSafety.get(it.address, it.payload) } +
            PlaybackReads.deviceInfoRequest("aa:bb:cc:00:00:01") +
            ProbeSafety.get(QcUltra2.PAIRED_DEVICES)
        assertEquals(8, packets.size)
        for (p in packets) {
            assertEquals(BmapOperator.GET, p.operator, "${p.address}")
            assertNull(WriteGuard.refusalReason(p), "${p.address}")
        }
        val addresses = PlaybackReads.all.map { it.address }
        for (a in listOf(QcUltra2.AUDIO_SOURCE, QcUltra2.AUDIO_CONTROL, QcUltra2.AUDIO_STATUS, QcUltra2.VOLUME, QcUltra2.MIC_MUTE)) {
            assertTrue(a in addresses, "missing $a")
        }
    }

    @Test
    fun noReadTouchesTheRefusedBlocksOrFunctions() {
        val refusedBlocks = setOf(3, 18)
        val refusedFunctions = setOf(7 to 5, 7 to 6, 7 to 11, 4 to 7)
        for (r in PlaybackReads.all) {
            val a = r.address
            assertTrue(a.block !in refusedBlocks && (a.block to a.function) !in refusedFunctions, "$a")
        }
    }

    @Test
    fun writeGuardStillRefusesWhatTheProbeMustNeverSend() {
        assertFailsWith<Exception> { WriteGuard.check(BmapPacket.start(QcUltra2.DM_CLEAR_DEVICE_LIST, byteArrayOf())) }
    }

    @Test
    fun theDefaultRunSendsNoSetOrStart() {
        // The default run lives in Main.kt and Reads.kt; only WriteTest.kt and ProbeClient.kt may write.
        for (name in listOf("Main.kt", "Reads.kt")) {
            val text = File(probeDir, name).readText()
            for (bad in listOf("BmapOperator.SET", "BmapOperator.START", ".setGet(", "BmapPacket.start(")) {
                assertTrue(bad !in text, "$name contains $bad")
            }
        }
    }

    @Test
    fun addressesAreMasked() {
        val raw = byteArrayOf(0x00, 0x0f, 0x01, 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(), 0, 0, 3)
        assertEquals("000f01xxxxxxxxxxxx", ProbeSafety.maskAddress(raw, 3))
        val shown = PlaybackReads.all.first { it.address == QcUltra2.AUDIO_SOURCE }.raw(raw)
        assertTrue("ddeeff" !in shown && "xx" in shown)
    }
}
