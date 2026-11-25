package io.github.langkurst.ruru.dsp

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProEqTest {
    private val flat = ProEqState(enabled = true)

    private val tuned: ProEqState =
        ProEqPreset.builtIn.first { it.id == BuiltInPresets.QC_ULTRA_ID }.applyTo(ProEqState(enabled = true))

    private val custom = ProEqState(
        enabled = true,
        mode = ProEqMode.PARAMETRIC,
        bands = listOf(
            ParametricBand(80.0, 4.5, 0.71, FilterType.LOW_SHELF),
            ParametricBand(250.0, -2.0, 1.0),
            ParametricBand(3000.0, 3.0, 2.5),
            ParametricBand(9000.0, 2.0, 0.71, FilterType.HIGH_SHELF),
        ),
    )

    private val target = "bluez_output.XX.1"

    // ---- golden files -------------------------------------------------------------------

    @Test
    fun `apo golden output`() {
        assertEquals(EqGolden.apoFlat, ApoConfigWriter.write(flat))
        assertEquals(EqGolden.apoTuned, ApoConfigWriter.write(tuned))
        assertEquals(EqGolden.apoCustom, ApoConfigWriter.write(custom))
    }

    @Test
    fun `pipewire golden output`() {
        assertEquals(EqGolden.pwFlat, FilterChainWriter.write(flat, target))
        assertEquals(EqGolden.pwTuned, FilterChainWriter.write(tuned, target))
        assertEquals(EqGolden.pwCustom, FilterChainWriter.write(custom, target))
    }

    @Test
    fun `apo device line comes before the preamp and a line break cannot add commands`() {
        val text = ApoConfigWriter.write(custom, "Headphones (WH-1000XM5)\nInclude: evil.txt")
        val lines = text.lines()
        assertEquals("Device: Headphones (WH-1000XM5) Include: evil.txt", lines[1])
        assertTrue(lines[2].startsWith("Preamp:"))
        assertFalse(lines.any { it.startsWith("Include:") })
        assertFalse(ApoConfigWriter.write(custom, "  \n ").contains("Device:"))
    }

    @Test
    fun `an off state writes a flat file and disabled or zero bands are left out`() {
        assertEquals(EqGolden.apoFlat, ApoConfigWriter.write(custom.copy(enabled = false)))
        val s = custom.copy(
            bands = custom.bands.mapIndexed { i, b -> if (i == 1) b.copy(enabled = false) else if (i == 2) b.copy(gainDb = 0.02) else b },
        )
        val text = ApoConfigWriter.write(s)
        assertEquals(2, text.lines().count { it.startsWith("Filter ") })
        assertTrue(text.contains("Filter 1: ON LSC Fc 80 Hz"))
        assertTrue(text.contains("Filter 2: ON HSC Fc 9000 Hz"))
    }

    @Test
    fun `pipewire target is optional and escaped`() {
        assertFalse(FilterChainWriter.write(custom).contains("target.object"))
        assertFalse(FilterChainWriter.write(custom, "  ").contains("target.object"))
        val text = FilterChainWriter.write(custom, "a\"b\\c\nd")
        assertTrue(text.contains("target.object = \"a\\\"b\\\\cd\""), text)
        assertTrue(text.contains("node.name = \"ruru_eq\""))
        assertTrue(text.contains("media.class = Audio/Sink"))
        assertTrue(text.contains("node.description = \"Ruru EQ\""))
    }

    // ---- headroom -----------------------------------------------------------------------

    private fun peakDb(bands: List<ParametricBand>, preampDb: Double): Double =
        EqResponse(bands, preampDb).sample(16384).maxOf { it.gainDb }

    private fun preampOf(apo: String): Double =
        apo.lines().first { it.startsWith("Preamp:") }.removePrefix("Preamp:").removeSuffix("dB").trim().toDouble()

    private fun lcg(seed: Long): () -> Double {
        var s = seed
        return {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            ((s ushr 11) % 1_000_000L).toDouble() / 1_000_000.0
        }
    }

    @Test
    fun `headroom keeps the summed peak at or below 0 dB`() {
        val states = ArrayList<ProEqState>()
        states += tuned
        states += custom
        states += flat.copy(graphicGainsDb = List(10) { 12.0 })
        states += flat.copy(graphicGainsDb = List(10) { if (it % 2 == 0) 12.0 else -12.0 })
        states += flat.copy(graphicGainsDb = listOf(12.0, 12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 12.0, 12.0))
        ProEqPreset.builtIn.forEach { states += it.applyTo(flat).copy(autoPreamp = true) }
        val rnd = lcg(42)
        repeat(60) {
            val n = 1 + (rnd() * 12).toInt()
            val bands = List(n) {
                ParametricBand(
                    frequencyHz = 20.0 * 1000.0.pow(rnd()),
                    gainDb = -12.0 + 24.0 * rnd(),
                    q = 0.1 + 9.9 * rnd(),
                    type = FilterType.entries[(rnd() * 3).toInt().coerceAtMost(2)],
                )
            }
            states += custom.copy(bands = bands)
        }
        for (s in states) {
            val auto = s.copy(autoPreamp = true)
            assertTrue(peakDb(auto.outputBands(), auto.effectivePreampDb()) <= 1e-9, "auto $auto")
            assertTrue(peakDb(auto.outputBands(), auto.desktopPreampDb()) <= 1e-9, "desktop $auto")
            // A manual preamp of 0 dB must still be pulled down on the desktop (no limiter there).
            val manual = s.copy(autoPreamp = false, preampDb = 0.0)
            assertTrue(peakDb(manual.outputBands(), manual.desktopPreampDb()) <= 1e-9, "manual $manual")
            // The written files carry the same preamp the check used.
            val written = preampOf(ApoConfigWriter.write(manual))
            assertTrue(peakDb(manual.outputBands(), written) <= 1e-9, "apo preamp $manual")
        }
    }

    @Test
    fun `flat and cut-only states need no preamp, a boost needs about its own size`() {
        assertEquals(0.0, flat.effectivePreampDb(), 0.0)
        assertEquals(0.0, flat.desktopPreampDb(), 0.0)
        assertEquals(0.0, flat.copy(graphicGainsDb = List(10) { -6.0 }).desktopPreampDb(), 0.0)
        val one = flat.withGraphicGain(5, 6.0).desktopPreampDb()
        assertTrue(one <= -6.0 && one > -7.0, "got $one")
    }

    @Test
    fun `a manual preamp lower than headroom is kept and a higher one is cut on the desktop`() {
        val s = flat.withGraphicGain(5, 6.0)
        val need = s.desktopPreampDb()
        assertEquals(-12.0, s.copy(autoPreamp = false, preampDb = -12.0).desktopPreampDb(), 0.0)
        assertEquals(need, s.copy(autoPreamp = false, preampDb = -1.0).desktopPreampDb(), 0.0)
        // With a limiter (Android) the manual value stands.
        assertEquals(-1.0, s.copy(autoPreamp = false, preampDb = -1.0, limiterEnabled = true).effectivePreampDb(), 0.0)
        assertEquals(need, s.copy(autoPreamp = false, preampDb = -1.0, limiterEnabled = false).effectivePreampDb(), 0.0)
    }

    // ---- conversion and the response curve ----------------------------------------------

    @Test
    fun `graphic gains become ten peaking bands at the graphic frequencies`() {
        val gains = listOf(1.0, 2.0, 3.0, 4.0, 5.0, -1.0, -2.0, -3.0, -4.0, -5.0)
        val bands = ProEq.bandsFromGraphic(gains)
        assertEquals(listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), bands.map { it.frequencyHz })
        assertEquals(gains, bands.map { it.gainDb })
        assertTrue(bands.all { it.type == FilterType.PEAK && it.q == 1.41 && it.enabled })
    }

    @Test
    fun `switching to parametric keeps the sound`() {
        val g = flat.copy(graphicGainsDb = listOf(3.0, 2.0, 1.0, 0.0, -1.0, -2.0, 1.5, 3.0, 4.0, 2.0), activePresetId = "x")
        val p = g.toParametric()
        assertEquals(ProEqMode.PARAMETRIC, p.mode)
        assertEquals(10, p.bands.size)
        val f = g.responseFn()
        val f2 = p.responseFn()
        for (hz in listOf(20.0, 45.0, 100.0, 777.0, 3000.0, 12000.0, 20000.0)) assertEquals(f(hz), f2(hz), 1e-9)
        assertEquals(p, p.toParametric())
    }

    @Test
    fun `response includes the preamp and matches the curve`() {
        val s = custom
        val fn = s.responseFn()
        for (hz in listOf(25.0, 80.0, 250.0, 1000.0, 3000.0, 9000.0, 18000.0)) {
            assertEquals(s.response(hz), fn(hz), 1e-12)
        }
        val bandsOnly = EqResponse(s.outputBands(), 0.0).magnitudeDb(3000.0)
        assertEquals(bandsOnly + s.effectivePreampDb(), s.response(3000.0), 1e-9)
        val curve = s.curve(64)
        assertEquals(64, curve.size)
        assertEquals(20.0, curve.first().frequencyHz, 1e-9)
        assertEquals(20000.0, curve.last().frequencyHz, 1e-6)
        assertEquals(fn(curve[10].frequencyHz), curve[10].gainDb, 1e-9)
        assertEquals(0.0, flat.response(1000.0), 1e-9)
    }

    @Test
    fun `moving a slider clears the preset id`() {
        val s = flat.copy(activePresetId = "flat").withGraphicGain(2, 4.0)
        assertNull(s.activePresetId)
        assertEquals(4.0, s.graphicGainsDb[2], 0.0)
        assertEquals(12.0, s.withGraphicGain(2, 40.0).graphicGainsDb[2], 0.0)
    }

    // ---- clamping -----------------------------------------------------------------------

    @Test
    fun `values are clamped to the Pro EQ limits`() {
        val wild = ProEqState(
            enabled = true,
            mode = ProEqMode.PARAMETRIC,
            graphicGainsDb = listOf(99.0, -99.0, Double.NaN),
            bands = listOf(
                ParametricBand(5.0, 40.0, 0.0),
                ParametricBand(90000.0, -40.0, 500.0, FilterType.HIGH_SHELF),
                ParametricBand(Double.NaN, Double.NaN, Double.NaN),
            ),
            preampDb = 6.0,
        ).sanitized()
        assertEquals(10, wild.graphicGainsDb.size)
        assertEquals(12.0, wild.graphicGainsDb[0], 0.0)
        assertEquals(-12.0, wild.graphicGainsDb[1], 0.0)
        assertEquals(0.0, wild.graphicGainsDb[2], 0.0)
        assertEquals(ParametricBand(20.0, 12.0, 0.1), wild.bands[0])
        assertEquals(ParametricBand(20000.0, -12.0, 10.0, FilterType.HIGH_SHELF), wild.bands[1])
        assertEquals(ParametricBand(1000.0, 0.0, 1.41), wild.bands[2])
        assertEquals(0.0, wild.preampDb, 0.0)
        assertEquals(-30.0, ProEq.clampPreamp(-100.0), 0.0)
        assertEquals(ProEq.MAX_BANDS, ProEqState(mode = ProEqMode.PARAMETRIC, bands = List(40) { ParametricBand(100.0 + it, 1.0) }).sanitized().bands.size)
    }

    @Test
    fun `writers print only clamped values`() {
        val wild = custom.copy(bands = listOf(ParametricBand(5.0, 40.0, 0.0), ParametricBand(90000.0, -40.0, 500.0, FilterType.HIGH_SHELF)))
        val apo = ApoConfigWriter.write(wild)
        assertTrue(apo.contains("Filter 1: ON PK Fc 20 Hz Gain 12.0 dB Q 0.10"), apo)
        assertTrue(apo.contains("Filter 2: ON HSC Fc 20000 Hz Gain -12.0 dB Q 10.00"), apo)
        val pw = FilterChainWriter.write(wild)
        assertTrue(pw.contains("\"Freq\" = 20.0 \"Q\" = 0.10 \"Gain\" = 12.0"), pw)
        assertTrue(pw.contains("\"Freq\" = 20000.0 \"Q\" = 10.00 \"Gain\" = -12.0"), pw)
    }

    // ---- AutoEq round trip --------------------------------------------------------------

    @Test
    fun `AutoEq import to parametric to APO and back gives the same bands`() {
        val autoEq = """
            Preamp: -6.0 dB
            Filter 1: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
            Filter 2: ON PK Fc 5032 Hz Gain 2.8 dB Q 3.69
            Filter 3: ON PK Fc 156 Hz Gain 1.5 dB Q 1.61
            Filter 4: ON HSC Fc 10000 Hz Gain 2.7 dB Q 0.70
            Filter 5: ON PK Fc 1633.5 Hz Gain 2.6 dB Q 3.51
        """.trimIndent()
        val (store, preset) = PresetStore().importAutoEq(autoEq, "My AutoEq")
        assertEquals("My AutoEq", preset.name)
        assertFalse(preset.isBuiltIn)
        assertNotNull(store.byId(preset.id))
        val state = preset.applyTo(ProEqState(enabled = true))
        assertEquals(ProEqMode.PARAMETRIC, state.mode)
        assertEquals(preset.id, state.activePresetId)

        val apo = ApoConfigWriter.write(state)
        val back = AutoEqParser.parse(apo, "back", "back")
        assertEquals(state.outputBands(), back.bands)
        assertEquals(state.desktopPreampDb(), back.preampDb, 0.0)
        assertTrue(apo.contains("Fc 1633.5 Hz"))
    }

    @Test
    fun `the built-in QC Ultra preset survives the APO round trip`() {
        val original = BuiltInPresets.tunedForQcUltra
        val back = AutoEqParser.parse(ApoConfigWriter.write(tuned), "b", "b")
        assertEquals(original.bands, back.bands)
        // Never louder than AutoEq's own preamp, so never clips.
        assertTrue(back.preampDb <= original.preampDb + 1e-9, "${back.preampDb} vs ${original.preampDb}")
    }

    @Test
    fun `an import with a wild gain is clamped to the Pro EQ limit`() {
        val (_, preset) = PresetStore().importAutoEq("Filter 1: ON PK Fc 1000 Hz Gain -16.0 dB Q 20.0", "Wild")
        assertEquals(-12.0, preset.bands.single().gainDb, 0.0)
        assertEquals(10.0, preset.bands.single().q, 0.0)
    }

    @Test
    fun `an import that is not AutoEq text is refused`() {
        var refused = false
        try {
            PresetStore().importAutoEq("hello", "x")
        } catch (e: AutoEqParser.ParseException) {
            refused = true
        }
        assertTrue(refused)
    }

    // ---- state and preset store serialisation -------------------------------------------

    @Test
    fun `state serialises and deserialises`() {
        val s = custom.copy(preampDb = -3.5, autoPreamp = false, limiterEnabled = false, activePresetId = "user-1")
        assertEquals(s, ProEqState.decode(s.encode()))
        assertEquals(tuned, ProEqState.decode(tuned.encode()))
        assertEquals(ProEqState(), ProEqState.decode("not json"))
        assertEquals(ProEqState(), ProEqState.decode(null))
        assertEquals(ProEqState(), ProEqState.decode("{}"))
        val back = ProEqState.decode("""{"graphicGainsDb":[50.0],"bands":[{"frequencyHz":1.0,"gainDb":30.0}],"future":1}""")
        assertEquals(10, back.graphicGainsDb.size)
        assertEquals(12.0, back.graphicGainsDb[0], 0.0)
        assertEquals(ParametricBand(20.0, 12.0, 1.41), back.bands.single())
    }

    @Test
    fun `preset store round trips through one string`() {
        var store = PresetStore()
        val (s1, a) = store.saveNew("  Late   night  ", custom)
        val (s2, b) = s1.saveNew("Gym", flat.withGraphicGain(0, 6.0))
        store = s2.withPresetFor("Bose QC Ultra Headphones", a.id).withPresetFor("WH-1000XM5", b.id)
        assertEquals("Late night", a.name)
        assertEquals("user-1", a.id)
        assertEquals("user-2", b.id)
        assertEquals(ProEqMode.GRAPHIC10, b.mode)
        assertEquals(6.0, b.graphicGainsDb[0], 0.0)

        val text = store.encode()
        val back = PresetStore.decode(text)
        assertEquals(store, back)
        assertEquals(a.id, back.presetIdFor("  bose  qc ULTRA headphones "))
        assertEquals(b.id, back.presetIdFor("WH-1000XM5"))
        assertNull(back.presetIdFor("Unknown"))
        // Applying a saved preset gives back the saved sound.
        assertEquals(custom.bands, back.byId(a.id)!!.applyTo(flat).bands)
        assertEquals(a.id, back.byId(a.id)!!.applyTo(flat).activePresetId)
    }

    @Test
    fun `built-ins are listed first and cannot be renamed or deleted`() {
        val (store, saved) = PresetStore().saveNew("Mine", custom)
        val all = store.all()
        assertEquals(BuiltInPresets.all.map { it.id } + saved.id, all.map { it.id })
        assertTrue(all.take(BuiltInPresets.all.size).all { it.isBuiltIn })
        assertEquals(store, store.rename(BuiltInPresets.FLAT_ID, "Nope"))
        assertEquals(store, store.delete(BuiltInPresets.FLAT_ID))
        assertEquals(store, store.update(BuiltInPresets.FLAT_ID, custom))
        assertEquals(store, store.rename(saved.id, "   "))
        assertEquals("Renamed", store.rename(saved.id, "Renamed").byId(saved.id)!!.name)
        assertEquals(PresetStore.MAX_NAME_LENGTH, store.rename(saved.id, "x".repeat(100)).byId(saved.id)!!.name.length)
    }

    @Test
    fun `deleting a preset forgets the headsets that used it and ids are not reused while in use`() {
        val (s1, a) = PresetStore().saveNew("A", custom)
        val (s2, b) = s1.saveNew("B", flat)
        val withHeads = s2.withPresetFor("Headset 1", a.id).withPresetFor("Headset 2", b.id)
        val after = withHeads.delete(a.id)
        assertNull(after.byId(a.id))
        assertNull(after.presetIdFor("Headset 1"))
        assertEquals(b.id, after.presetIdFor("Headset 2"))
        val (_, c) = after.saveNew("C", flat)
        assertEquals("user-1", c.id)
        // Built-in ids work for a headset; unknown ids and blank names are ignored.
        assertEquals(BuiltInPresets.QC_ULTRA_ID, after.withPresetFor("Headset 3", BuiltInPresets.QC_ULTRA_ID).presetIdFor("Headset 3"))
        assertEquals(after, after.withPresetFor("Headset 3", "missing"))
        assertEquals(after, after.withPresetFor("   ", BuiltInPresets.FLAT_ID))
        assertNull(after.withPresetFor("Headset 2", null).presetIdFor("Headset 2"))
    }

    @Test
    fun `saving over a saved preset keeps its id and name`() {
        val (store, a) = PresetStore().saveNew("A", custom)
        val updated = store.update(a.id, flat.withGraphicGain(3, 5.0))
        val p = updated.byId(a.id)!!
        assertEquals("A", p.name)
        assertEquals(ProEqMode.GRAPHIC10, p.mode)
        assertEquals(5.0, p.graphicGainsDb[3], 0.0)
    }

    @Test
    fun `a damaged store text gives an empty store and bad entries are repaired`() {
        assertEquals(PresetStore(), PresetStore.decode("garbage"))
        assertEquals(PresetStore(), PresetStore.decode(null))
        val text = """{"saved":[
            {"id":"flat","name":"Impostor"},
            {"id":"user-1","name":"  ","bands":[{"frequencyHz":100.0,"gainDb":50.0,"q":0.0}],"isBuiltIn":true},
            {"id":"user-1","name":"Duplicate"}],
            "perHeadset":{"a":"user-1","b":"gone","c":"flat"}}"""
        val store = PresetStore.decode(text)
        assertEquals(1, store.saved.size)
        val p = store.saved.single()
        assertEquals(PresetStore.DEFAULT_NAME, p.name)
        assertFalse(p.isBuiltIn)
        assertEquals(ParametricBand(100.0, 12.0, 0.1), p.bands.single())
        assertEquals(mapOf("a" to "user-1", "c" to "flat"), store.perHeadset)
    }

    @Test
    fun `built-in Flat applies as a flat sound`() {
        val s = ProEqPreset.builtIn.first { it.id == BuiltInPresets.FLAT_ID }.applyTo(ProEqState(enabled = true))
        assertTrue(abs(s.response(1000.0)) < 1e-9)
        assertEquals(EqGolden.apoFlat, ApoConfigWriter.write(s))
    }
}
