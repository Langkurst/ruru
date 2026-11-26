package io.github.langkurst.ruru.app.tools

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The "find my headphones" sound: a two-note chirp that rises (2.9 kHz, then 3.7 kHz, 200 ms each),
 * repeated. High notes carry through a sofa cushion and stand out from room noise; two rising notes
 * read as an alert rather than music. Stereo 16-bit PCM, the same in both ears.
 * Ported from Earshot (MIT, see CREDITS.md) without its one-sided mode.
 *
 * The volume starts low and rises over [RAMP_MS], because someone may still be wearing the headphones.
 * Pure, so the shape is unit-tested.
 */
object FindTone {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    private const val NOTE_A_HZ = 2_900.0
    private const val NOTE_B_HZ = 3_700.0
    private const val NOTE_MS = 200
    private const val GAP_MS = 60
    private const val REST_MS = 400

    /** One period: note, gap, note, rest. */
    const val PERIOD_MS = NOTE_MS + GAP_MS + NOTE_MS + REST_MS

    /** The volume goes from [START_GAIN] to full over this long. */
    const val RAMP_MS = 6_000L
    const val START_GAIN = 0.2

    /** The tone stops by itself after this long: long enough to walk to the sofa, short enough not to drain a lost pair. */
    const val MAX_RING_MS = 30_000L

    /** A 5 ms fade at each note edge, so the notes don't click. */
    private const val FADE_MS = 5

    /** Headroom below full scale: loud enough, never clipping after resampling. */
    private const val PEAK = 0.85

    fun gainAt(elapsedMs: Long): Double =
        START_GAIN + (1.0 - START_GAIN) * min(1.0, elapsedMs.coerceAtLeast(0).toDouble() / RAMP_MS)

    /** Frames (one sample per channel) in [ms] at [SAMPLE_RATE]. */
    private fun frames(ms: Int): Int = SAMPLE_RATE * ms / 1000

    /** One period of interleaved stereo PCM (left, right, left, ...) at [gain] (0..1). */
    fun period(gain: Double): ShortArray {
        val g = gain.coerceIn(0.0, 1.0) * PEAK
        val out = ShortArray(frames(PERIOD_MS) * CHANNELS)
        writeNote(out, startFrame = 0, hz = NOTE_A_HZ, gain = g)
        writeNote(out, startFrame = frames(NOTE_MS + GAP_MS), hz = NOTE_B_HZ, gain = g)
        return out
    }

    private fun writeNote(out: ShortArray, startFrame: Int, hz: Double, gain: Double) {
        val length = frames(NOTE_MS)
        val fade = frames(FADE_MS)
        for (i in 0 until length) {
            val edge = min(1.0, min(i, length - 1 - i).toDouble() / fade)
            val sample = (sin(2 * PI * hz * i / SAMPLE_RATE) * gain * edge * Short.MAX_VALUE).roundToInt().toShort()
            val frame = (startFrame + i) * CHANNELS
            out[frame] = sample
            out[frame + 1] = sample
        }
    }
}
