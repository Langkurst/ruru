package io.github.langkurst.ruru.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskScaleTest {
    @Test
    fun `nothing changes up to 1280dp`() {
        for (w in listOf(360f, 880f, 1100f, 1280f)) assertEquals(1f, deskScale(w), "width $w")
    }

    @Test
    fun `it grows gently and stops at 1_35`() {
        assertEquals(1.09f, deskScale(1600f))
        assertTrue(deskScale(1920f) in 1.17f..1.18f)
        assertEquals(1.35f, deskScale(2560f))
        assertEquals(1.35f, deskScale(5000f))
    }

    @Test
    fun `it never shrinks or jumps as the window grows`() {
        var last = 1f
        for (w in 1000..3000 step 7) {
            val k = deskScale(w.toFloat())
            assertTrue(k >= last && k - last <= 0.0101f, "width $w: $last to $k")
            last = k
        }
    }
}
