package io.github.langkurst.ruru.link

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriteGuardTest {
    private val writes = listOf(BmapOperator.SET, BmapOperator.SET_GET, BmapOperator.START)

    private fun packet(block: Int, function: Int, op: BmapOperator) = BmapPacket(block, function, op, byteArrayOf(1, 2, 3, 4, 5, 6))

    @Test
    fun `4_3 RemoveDevice is refused for every write operator`() {
        for (op in writes) {
            val why = assertNotNull(WriteGuard.refusalReason(packet(4, 3, op)), op.name)
            assertTrue("unpair" in why, why)
            assertFailsWith<HeadphoneCommandException> { WriteGuard.check(packet(4, 3, op)) }
        }
        assertNull(WriteGuard.refusalReason(BmapPacket.get(QcUltra2.DM_REMOVE_DEVICE)))
    }

    @Test
    fun `everything refused before is still refused`() {
        val refused = listOf(3 to 1, 3 to 2, 3 to 7, 18 to 1, 18 to 4, 7 to 5, 7 to 6, 7 to 11, 4 to 7)
        for ((block, function) in refused) for (op in writes) {
            assertNotNull(WriteGuard.refusalReason(packet(block, function, op)), "[$block.$function] ${op.name}")
        }
    }

    @Test
    fun `reads and ordinary settings pass`() {
        for ((block, function) in listOf(3 to 1, 18 to 1, 4 to 3, 4 to 7, 7 to 5)) {
            assertNull(WriteGuard.refusalReason(packet(block, function, BmapOperator.GET)))
        }
        // Switching devices, power off, a mode and the EQ stay allowed.
        for ((block, function) in listOf(4 to 1, 7 to 4, 31 to 3, 1 to 7)) {
            assertNull(WriteGuard.refusalReason(packet(block, function, BmapOperator.SET_GET)), "[$block.$function]")
        }
    }
}
