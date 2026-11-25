package io.github.langkurst.ruru.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.Serializable

/** Which editor the Pro EQ page shows. Both end up as the same list of parametric bands. */
@Serializable
enum class ProEqMode { GRAPHIC10, PARAMETRIC }

/** Limits, the graphic frequencies, and the clamping rules every Pro EQ value goes through. */
object ProEq {
    /** Centres of the 10 graphic sliders. */
    val GRAPHIC_FREQUENCIES_HZ: List<Double> =
        listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    const val GRAPHIC_BAND_COUNT = 10

    /** Octave-spaced graphic bands overlap evenly at this Q. */
    const val GRAPHIC_Q = 1.41

    const val MIN_GAIN_DB = -12.0
    const val MAX_GAIN_DB = 12.0
    const val MIN_Q = 0.1
    const val MAX_Q = 10.0
    const val MIN_FREQUENCY_HZ = 20.0
    const val MAX_FREQUENCY_HZ = 20000.0
    const val MIN_PREAMP_DB = -30.0
    const val MAX_PREAMP_DB = 0.0
    const val MAX_BANDS = 20

    /** Graphic gains as parametric peaking bands (what every backend actually runs). */
    fun bandsFromGraphic(gainsDb: List<Double>): List<ParametricBand> {
        val gains = fitGains(gainsDb)
        return GRAPHIC_FREQUENCIES_HZ.mapIndexed { i, f ->
            ParametricBand(frequencyHz = f, gainDb = gains[i], q = GRAPHIC_Q, type = FilterType.PEAK)
        }
    }

    /** Exactly [GRAPHIC_BAND_COUNT] gains, clamped; missing ones are 0 dB, extra ones dropped. */
    fun fitGains(gainsDb: List<Double>): List<Double> =
        List(GRAPHIC_BAND_COUNT) { i -> clampGain(gainsDb.getOrElse(i) { 0.0 }) }

    fun clampGain(v: Double): Double = finite(v, 0.0).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    fun clampBand(b: ParametricBand): ParametricBand = b.copy(
        frequencyHz = finite(b.frequencyHz, 1000.0).coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
        gainDb = clampGain(b.gainDb),
        q = finite(b.q, GRAPHIC_Q).coerceIn(MIN_Q, MAX_Q),
    )

    fun clampPreamp(v: Double): Double = finite(v, 0.0).coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)

    /** The values the writers print: frequency to 0.1 Hz, gain to 0.1 dB, Q to 0.01. */
    fun quantise(b: ParametricBand): ParametricBand {
        val c = clampBand(b)
        return c.copy(
            frequencyHz = round(c.frequencyHz, 10.0).coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
            gainDb = round(c.gainDb, 10.0),
            q = round(c.q, 100.0).coerceIn(MIN_Q, MAX_Q),
        )
    }

    /**
     * Preamp (never positive) that keeps the summed response of [bands] at or below 0 dB:
     * minus the highest point of the sum, sampled on a fine log grid plus every band's own
     * centre, rounded up to 0.1 dB with a further 0.01 dB of margin so a peak between two
     * samples cannot poke above 0.
     */
    fun autoPreampDb(bands: List<ParametricBand>, sampleRate: Double = Biquad.DEFAULT_SAMPLE_RATE): Double {
        val active = bands.filter { it.enabled }
        if (active.isEmpty()) return 0.0
        val response = EqResponse(active, preampDb = 0.0, sampleRate = sampleRate)
        var peak = Double.NEGATIVE_INFINITY
        for (f in EqResponse.logSpaced(GRID_POINTS, MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)) {
            val g = response.bandsOnlyDb(f)
            if (g > peak) peak = g
        }
        for (b in active) {
            val g = response.bandsOnlyDb(b.frequencyHz)
            if (g > peak) peak = g
        }
        if (peak <= 0.0) return 0.0
        return -(ceil((peak + 0.01) * 10.0) / 10.0)
    }

    private const val GRID_POINTS = 4096

    internal fun round(v: Double, factor: Double): Double = floor(v * factor + 0.5) / factor

    private fun finite(v: Double, fallback: Double) = if (v.isFinite()) v else fallback
}

/**
 * Everything the Pro EQ page edits and the backends run. Plain data; the maths is below.
 *
 * In [ProEqMode.GRAPHIC10] the ten [graphicGainsDb] drive the sound; in [ProEqMode.PARAMETRIC]
 * the [bands] list does. [autoPreamp] makes the preamp follow the summed response (headroom);
 * with it off [preampDb] is used, but a backend that has no limiter (Equalizer APO, PipeWire)
 * never runs a preamp above what headroom needs ([desktopPreampDb]).
 */
@Serializable
data class ProEqState(
    val enabled: Boolean = false,
    val mode: ProEqMode = ProEqMode.GRAPHIC10,
    val graphicGainsDb: List<Double> = List(ProEq.GRAPHIC_BAND_COUNT) { 0.0 },
    val bands: List<ParametricBand> = emptyList(),
    val preampDb: Double = 0.0,
    val autoPreamp: Boolean = true,
    val limiterEnabled: Boolean = true,
    val activePresetId: String? = null,
) {
    /** Same state with every value inside its limits and the lists the right length. */
    fun sanitized(): ProEqState = copy(
        graphicGainsDb = ProEq.fitGains(graphicGainsDb),
        bands = bands.take(ProEq.MAX_BANDS).map { ProEq.clampBand(it) },
        preampDb = ProEq.clampPreamp(preampDb),
    )

    /** The bands for the current mode, clamped, disabled ones included (the UI lists them). */
    fun activeBands(): List<ParametricBand> = when (mode) {
        ProEqMode.GRAPHIC10 -> ProEq.bandsFromGraphic(graphicGainsDb)
        ProEqMode.PARAMETRIC -> bands.take(ProEq.MAX_BANDS).map { ProEq.clampBand(it) }
    }

    /** The bands that actually run, rounded to what the config files print. */
    fun outputBands(): List<ParametricBand> =
        activeBands().filter { it.enabled }.map { ProEq.quantise(it) }

    /** Preamp for the curve and for an engine with a limiter (Android). */
    fun effectivePreampDb(): Double {
        val auto = ProEq.autoPreampDb(outputBands())
        val manual = ProEq.clampPreamp(preampDb)
        return if (autoPreamp) auto else if (limiterEnabled) manual else minOf(manual, auto)
    }

    /** Preamp for Equalizer APO and PipeWire: no limiter there, so never above headroom. */
    fun desktopPreampDb(): Double {
        val b = outputBands()
        val auto = ProEq.autoPreampDb(b)
        val wanted = if (autoPreamp) auto else ProEq.clampPreamp(preampDb)
        return ProEq.round(minOf(wanted, auto), 10.0) + 0.0
    }

    /** Summed response in dB at [freqHz], effective preamp included. Builds the filters each call; use [responseFn] in a loop. */
    fun response(freqHz: Double): Double = responseFn()(freqHz)

    /** Response function built once, for drawing the curve. */
    fun responseFn(): (Double) -> Double {
        val r = EqResponse(outputBands(), preampDb = effectivePreampDb())
        return { f -> r.magnitudeDb(f) }
    }

    /** [nPoints] log-spaced points of the response over 20 Hz to 20 kHz. */
    fun curve(nPoints: Int = 256): List<ResponsePoint> =
        EqResponse(outputBands(), preampDb = effectivePreampDb()).sample(nPoints)

    /** Switch to the parametric editor, keeping the sound: the bands become the ten graphic bands. */
    fun toParametric(): ProEqState =
        if (mode == ProEqMode.PARAMETRIC) this
        else copy(mode = ProEqMode.PARAMETRIC, bands = ProEq.bandsFromGraphic(graphicGainsDb))

    /** Move one graphic slider. The preset no longer matches, so [activePresetId] is cleared. */
    fun withGraphicGain(index: Int, gainDb: Double): ProEqState {
        require(index in 0 until ProEq.GRAPHIC_BAND_COUNT) { "slider $index out of range" }
        val gains = ProEq.fitGains(graphicGainsDb).toMutableList()
        gains[index] = ProEq.clampGain(gainDb)
        return copy(graphicGainsDb = gains, activePresetId = null)
    }

    fun encode(): String = PresetCodec.json.encodeToString(serializer(), sanitized())

    companion object {
        /** Never throws: unreadable text gives the default (off, flat) state. */
        fun decode(text: String?): ProEqState =
            text?.let { runCatching { PresetCodec.json.decodeFromString(serializer(), it).sanitized() }.getOrNull() }
                ?: ProEqState()
    }
}
