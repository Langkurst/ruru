package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.dsp.AutoEqParser
import io.github.langkurst.ruru.dsp.BuiltInPresets
import io.github.langkurst.ruru.dsp.FilterType
import io.github.langkurst.ruru.dsp.ParametricBand
import io.github.langkurst.ruru.dsp.ProEq
import io.github.langkurst.ruru.dsp.ProEqMode
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Pro EQ model against a stand-in backend: what reaches the backend, what is saved, and what each headset remembers. */
class ProEqModelTest {
    private lateinit var scope: CoroutineScope
    private val settings = MemorySettingsStore()
    private val backend = FakeEqBackend()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun model(store: MemorySettingsStore = settings, be: EqBackend = backend) =
        ProEqModel(store, scope, be, demoBackend = { FakeEqBackend() }, debounceMs = 30, saveMs = 30)

    @Test
    fun `switching on goes to the backend at once and switching off undoes it`() = runBlocking {
        val m = model()
        m.setEnabled(true)
        m.settle()
        assertEquals(EqBackendStatus.On, m.ui.value.status)
        assertTrue(backend.applied.last().enabled)
        m.setEnabled(false)
        m.settle()
        assertEquals(1, backend.offCount)
        assertEquals(EqBackendStatus.Ready, m.ui.value.status)
    }

    @Test
    fun `a drag of many slider moves reaches the backend once, with the last value`() = runBlocking {
        val m = model()
        m.setEnabled(true)
        m.settle()
        val before = backend.applied.size
        for (i in 1..24) m.setGraphicGain(2, i * 0.5)
        m.settle()
        assertEquals(before + 1, backend.applied.size, "one write for the whole drag")
        assertEquals(12.0, backend.applied.last().graphicGainsDb[2], "the last value, clamped to 12 dB")
    }

    @Test
    fun `a change shows at once and is saved a moment later, then loads back`() = runBlocking {
        val m = model()
        m.setGraphicGain(0, 4.5)
        assertEquals(4.5, m.ui.value.eq.graphicGainsDb[0], "shown before it is saved")
        m.saveAs("Mine")
        m.flushSave()
        val again = model()
        assertEquals(4.5, again.ui.value.eq.graphicGainsDb[0])
        assertEquals("Mine", again.ui.value.activePreset?.name)
        assertTrue(settings.contains("eq.presets"), "the preset store sits under the key a backup carries")
    }

    @Test
    fun `an edit makes the sound Custom and Save as makes it a preset again`() {
        val m = model()
        m.applyPreset(BuiltInPresets.BASS_ID)
        assertEquals(BuiltInPresets.BASS_ID, m.ui.value.eq.activePresetId)
        m.setMode(ProEqMode.PARAMETRIC)
        assertEquals(BuiltInPresets.BASS_ID, m.ui.value.eq.activePresetId, "switching the editor keeps the sound")
        m.setBand(0, m.ui.value.eq.bands[0].copy(gainDb = 2.0))
        assertNull(m.ui.value.activePreset, "now Custom")
        m.saveAs("My bass")
        assertEquals("My bass", m.ui.value.activePreset?.name)
        assertFalse(m.ui.value.activePreset!!.isBuiltIn)
        m.rename(m.ui.value.eq.activePresetId!!, "Bass again")
        assertEquals("Bass again", m.ui.value.activePreset?.name)
        m.delete(m.ui.value.eq.activePresetId!!)
        assertNull(m.ui.value.activePreset)
        assertTrue(m.ui.value.presets.saved.isEmpty())
    }

    @Test
    fun `bands can be added up to the limit and removed`() {
        val m = model()
        m.setMode(ProEqMode.PARAMETRIC)
        val start = m.ui.value.eq.bands.size
        repeat(ProEq.MAX_BANDS + 5) { m.addBand() }
        assertEquals(ProEq.MAX_BANDS, m.ui.value.eq.bands.size)
        m.removeBand(0)
        assertEquals(ProEq.MAX_BANDS - 1, m.ui.value.eq.bands.size)
        assertTrue(start <= ProEq.MAX_BANDS)
        m.setBand(0, ParametricBand(99999.0, 40.0, 50.0, FilterType.HIGH_SHELF))
        val b = m.ui.value.eq.bands[0]
        assertEquals(ProEq.MAX_FREQUENCY_HZ, b.frequencyHz)
        assertEquals(ProEq.MAX_GAIN_DB, b.gainDb)
        assertEquals(ProEq.MAX_Q, b.q)
    }

    @Test
    fun `each pair of headphones gets its own preset back, found by name and never by address`() {
        val m = model()
        m.setHeadphone("Chris QC Ultra")
        m.applyPreset(BuiltInPresets.BASS_ID)
        m.setHeadphone("Spare Headphones")
        m.applyPreset(BuiltInPresets.VOCAL_ID)
        m.setHeadphone("  chris   qc ULTRA ")
        assertEquals(BuiltInPresets.BASS_ID, m.ui.value.eq.activePresetId)
        m.setHeadphone("Spare Headphones")
        assertEquals(BuiltInPresets.VOCAL_ID, m.ui.value.eq.activePresetId)
        val stored = m.ui.value.presets.perHeadset
        assertEquals(setOf("chris qc ultra", "spare headphones"), stored.keys)
        m.setHeadphone(null)
        assertEquals(BuiltInPresets.VOCAL_ID, m.ui.value.eq.activePresetId, "no headphones: the sound stays")
    }

    @Test
    fun `the headphones used last time keep the sound the person left, edits included`() = runBlocking {
        val first = model()
        first.setHeadphone("Chris QC Ultra")
        first.applyPreset(BuiltInPresets.BASS_ID)
        first.setMode(ProEqMode.PARAMETRIC)
        first.setBand(0, first.ui.value.eq.bands[0].copy(gainDb = -4.0))
        first.flushSave()
        val gain = first.ui.value.eq.bands[0].gainDb
        val second = model()
        second.setHeadphone("Chris QC Ultra")
        assertEquals(gain, second.ui.value.eq.bands[0].gainDb)
        assertNull(second.ui.value.activePreset)
    }

    @Test
    fun `demo mode works on its own copy and leaves the real settings and backend alone`() = runBlocking {
        val m = model()
        m.setGraphicGain(0, 3.0)
        m.flushSave()
        val realState = settings.getString(ProEqModel.KEY_STATE)
        val applied = backend.applied.size
        m.setDemo(true)
        assertEquals(0.0, m.ui.value.eq.graphicGainsDb[0], "demo starts from nothing")
        m.setEnabled(true)
        m.setGraphicGain(0, -6.0)
        m.saveAs("Demo preset")
        m.settle()
        m.flushSave()
        assertEquals(realState, settings.getString(ProEqModel.KEY_STATE), "the real settings did not change")
        assertEquals(applied, backend.applied.size, "the real backend was not asked")
        m.setDemo(false)
        assertEquals(3.0, m.ui.value.eq.graphicGainsDb[0], "back to the person's own sound")
        assertTrue(m.ui.value.presets.saved.isEmpty())
    }

    @Test
    fun `an AutoEq file becomes a preset that is played, and a bad file leaves a notice`() {
        val m = model()
        m.importAutoEq("My Cans ParametricEQ.txt", "Preamp: -3.0 dB\nFilter 1: ON PK Fc 100 Hz Gain 4.0 dB Q 1.00\nFilter 2: ON HSC Fc 8000 Hz Gain -2.0 dB Q 0.70\n")
        val ui = m.ui.value
        assertEquals("My Cans", ui.activePreset?.name)
        assertEquals(ProEqMode.PARAMETRIC, ui.eq.mode)
        assertEquals(2, ui.eq.bands.size)
        assertNull(ui.notice)
        m.importAutoEq("notes.txt", "this is not a preset")
        assertEquals(Words("eq_import_failed"), m.ui.value.notice)
        assertEquals(1, m.ui.value.presets.saved.size, "a bad file adds nothing")
        m.dismissNotice()
        assertNull(m.ui.value.notice)
    }

    @Test
    fun `export gives an AutoEq file that reads back to the same bands`() {
        val m = model()
        m.applyPreset(BuiltInPresets.QC_ULTRA_ID)
        val parsed = AutoEqParser.parse(m.exportText(), id = "x", name = "x")
        assertEquals(m.ui.value.eq.outputBands().size, parsed.bands.size)
        assertEquals(m.ui.value.eq.desktopPreampDb(), parsed.preampDb)
    }

    @Test
    fun `without a limiter the preamp shown is never above what the boosts need`() {
        val m = model()
        m.setGraphicGain(5, 12.0)
        m.setPreampDb(0.0)
        val ui = m.ui.value
        assertFalse(ui.hasLimiter)
        assertTrue(ui.shownPreampDb < -5.0, "a +12 dB boost needs headroom, got ${ui.shownPreampDb}")
        assertEquals(ui.eq.desktopPreampDb(), ui.shownPreampDb)
    }

    @Test
    fun `status and the setup note come from the backend, and its one button goes back to it`() {
        val needs = FakeEqBackend(EqStatuses.apoNotInstalled())
        val m = model(MemorySettingsStore(), needs)
        val status = m.ui.value.status as EqBackendStatus.NeedsSetup
        assertEquals(EqSetupKind.OPEN_WEBSITE, status.action.kind)
        assertEquals(EqStatuses.APO_URL, status.action.url)
        assertFalse(m.ui.value.status.switchable)
        assertNotNull(m.ui.value.setupNote)
        val allow = EqSetupAction(EqSetupKind.ALLOW, Words("eq_apo_consent", "x"), Words("eq_apo_allow"))
        m.perform(allow)
        m.refresh()
        assertEquals(listOf(allow), needs.performed)
        assertEquals(1, needs.refreshCount)
    }

    @Test
    fun `a model with no backend says so and does not fail`() {
        val m = ProEqModel(MemorySettingsStore(), scope, real = null)
        assertTrue(m.ui.value.status is EqBackendStatus.Unavailable)
        m.setEnabled(true)
        m.setGraphicGain(1, 2.0)
        assertEquals(2.0, m.ui.value.eq.graphicGainsDb[1])
    }

    @Test
    fun `what is on when Ruru starts is applied again`() = runBlocking {
        val first = model()
        first.setEnabled(true)
        first.flushSave()
        val be = FakeEqBackend()
        val second = model(settings, be)
        second.settle()
        assertTrue(be.applied.single().enabled)
    }
}
