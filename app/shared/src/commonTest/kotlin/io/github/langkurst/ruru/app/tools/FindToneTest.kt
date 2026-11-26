package io.github.langkurst.ruru.app.tools

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindToneTest {
    private fun peak(pcm: ShortArray, fromFrame: Int, toFrame: Int): Int =
        (fromFrame until toFrame).maxOf { abs(pcm[it * FindTone.CHANNELS].toInt()) }

    @Test
    fun `a period is stereo, the same in both ears, and as long as it says`() {
        val pcm = FindTone.period(1.0)
        assertEquals(FindTone.SAMPLE_RATE * FindTone.PERIOD_MS / 1000 * 2, pcm.size)
        for (i in pcm.indices step 2) assertEquals(pcm[i], pcm[i + 1], "frame ${i / 2}")
    }

    @Test
    fun `the tone has two notes and a rest, and never reaches full scale`() {
        val pcm = FindTone.period(1.0)
        val perMs = FindTone.SAMPLE_RATE / 1000
        assertTrue(peak(pcm, 0, 200 * perMs) > 20_000, "first note is audible")
        assertEquals(0, peak(pcm, 205 * perMs, 255 * perMs), "gap between the notes is silent")
        assertTrue(peak(pcm, 260 * perMs, 460 * perMs) > 20_000, "second note is audible")
        assertEquals(0, peak(pcm, 465 * perMs, 860 * perMs), "rest at the end is silent")
        assertTrue(pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE * 0.9, "headroom below full scale")
    }

    @Test
    fun `the second note is higher than the first`() {
        val pcm = FindTone.period(1.0)
        fun crossings(fromMs: Int, toMs: Int): Int {
            val perMs = FindTone.SAMPLE_RATE / 1000
            var n = 0
            for (f in fromMs * perMs + 1 until toMs * perMs) {
                if ((pcm[(f - 1) * 2] < 0) != (pcm[f * 2] < 0)) n++
            }
            return n
        }
        assertTrue(crossings(260, 460) > crossings(0, 200), "rises")
    }

    @Test
    fun `the volume starts low and reaches full over the ramp`() {
        assertEquals(FindTone.START_GAIN, FindTone.gainAt(0), 1e-9)
        assertEquals(1.0, FindTone.gainAt(FindTone.RAMP_MS), 1e-9)
        assertEquals(1.0, FindTone.gainAt(FindTone.RAMP_MS * 10), 1e-9)
        assertTrue(FindTone.gainAt(FindTone.RAMP_MS / 2) in 0.5..0.7)
        // and the first period is audibly quieter than a full one
        assertTrue(FindTone.period(FindTone.gainAt(0)).maxOf { abs(it.toInt()) } * 3 < FindTone.period(1.0).maxOf { abs(it.toInt()) } * 2 )
    }

    @Test
    fun `it rings for thirty seconds at most`() {
        assertEquals(30_000L, FindTone.MAX_RING_MS)
    }
}
