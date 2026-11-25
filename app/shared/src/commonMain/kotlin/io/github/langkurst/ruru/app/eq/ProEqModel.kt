package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.dsp.AutoEqParser
import io.github.langkurst.ruru.dsp.EqPreset
import io.github.langkurst.ruru.dsp.FilterType
import io.github.langkurst.ruru.dsp.ParametricBand
import io.github.langkurst.ruru.dsp.PresetStore
import io.github.langkurst.ruru.dsp.ProEq
import io.github.langkurst.ruru.dsp.ProEqMode
import io.github.langkurst.ruru.dsp.ProEqPreset
import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Equaliser page draws. */
data class ProEqUi(
    val eq: ProEqState = ProEqState(),
    val presets: PresetStore = PresetStore(),
    val status: EqBackendStatus = EqBackendStatus.Ready,
    val hasLimiter: Boolean = false,
    val setupNote: Words? = null,
    /** The headphones the presets are remembered for (their name), or null when none are connected. */
    val headphone: String? = null,
    /** A sentence about something that did not work (an unreadable preset file), until dismissed. */
    val notice: Words? = null,
    val canImportExport: Boolean = false,
) {
    /** The preset the sound currently is, or null for "Custom". */
    val activePreset: ProEqPreset? get() = presets.byId(eq.activePresetId)

    /** The preamp the backend really runs, which the page shows: the limiter's value, or the headroom-held one without a limiter. */
    val shownPreampDb: Double get() = if (hasLimiter) eq.effectivePreampDb() else eq.desktopPreampDb()
}

/**
 * The Pro EQ state, its presets and its backend, for the Equaliser page. Edits show at once, are
 * written to the settings store a moment after the last one, and reach the backend after a short
 * pause ([debounceMs]) so dragging a slider does not rewrite a file for every pixel. Switching it
 * on or off goes to the backend straight away.
 *
 * Settings keys: `eq.state` (the [ProEqState] as text), `eq.presets` (one entry: the [PresetStore],
 * which is also what a backup carries) and `eq.headphone` (the name last used). Which preset a pair of
 * headphones uses is kept in the preset store, by the headphones' name, never an address.
 *
 * Demo mode ([setDemo]) swaps in [demoBackend] and an in-memory store, so nothing the person tries in
 * the demo changes their real settings or their real system.
 */
class ProEqModel(
    realSettings: SettingsStore,
    private val scope: CoroutineScope,
    private val real: EqBackend?,
    private val demoBackend: () -> EqBackend = { FakeEqBackend() },
    val files: EqFiles? = null,
    private val debounceMs: Long = 150,
    private val saveMs: Long = 250,
) {
    private val realStore: SettingsStore = realSettings
    private var settings: SettingsStore = realStore
    private var backend: EqBackend? = real
    private var demo = false
    private var statusJob: Job? = null
    private var applyJob: Job? = null
    private var saveJob: Job? = null

    private val current = MutableStateFlow(load(settings, null))
    val ui: StateFlow<ProEqUi> = current

    init {
        attach(real)
        // What was on when Ruru last closed is running again (a file written by Ruru stays in place while Ruru is closed).
        if (current.value.eq.enabled) scheduleApply(immediate = true)
    }

    // ---------------------------------------------------------------- switch and refresh

    fun setEnabled(on: Boolean) = edit(keepPreset = true, immediate = true) { it.copy(enabled = on) }

    /** Look at the system again (the page does this when it opens and while a step is waiting for the person). */
    fun refresh() {
        backend?.refresh()
    }

    /** The person pressed the button of the status row (except "open the website", which the page opens). */
    fun perform(action: EqSetupAction) {
        backend?.perform(action)
    }

    /** Demo mode swaps the backend and the settings; leaving it brings the real ones back and runs what was on. */
    fun setDemo(on: Boolean) {
        if (on == demo) return
        flushSave()
        demo = on
        settings = if (on) MemorySettingsStore() else realStore
        current.update { load(settings, it.headphone) }
        attach(if (on) demoBackend() else real)
        if (current.value.eq.enabled) scheduleApply(immediate = true)
    }

    // ---------------------------------------------------------------- per-headphone presets

    /**
     * The headphones now connected (their name; null for none). A pair that has a preset remembered
     * gets it as soon as they appear, unless they are the pair used last time (then the sound is left as the
     * person had it, edits included).
     */
    fun setHeadphone(name: String?) {
        val key = name?.let { PresetStore.headsetKey(it) }
        if (key == current.value.headphone?.let { PresetStore.headsetKey(it) }) return
        current.update { it.copy(headphone = name) }
        if (name == null || key == null) return
        val last = settings.getString(KEY_HEADPHONE)?.let { PresetStore.headsetKey(it) }
        settings.putString(KEY_HEADPHONE, key)
        if (key == last) return
        val ui = current.value
        val preset = ui.presets.presetIdFor(name)?.let { ui.presets.byId(it) } ?: return
        edit(keepPreset = true, remember = false) { preset.applyTo(it) }
    }

    // ---------------------------------------------------------------- editor

    fun setMode(mode: ProEqMode) = edit(keepPreset = mode == ProEqMode.PARAMETRIC) {
        if (it.mode == mode) it else if (mode == ProEqMode.PARAMETRIC) it.toParametric() else it.copy(mode = mode)
    }

    fun setGraphicGain(index: Int, gainDb: Double) = edit { it.withGraphicGain(index, gainDb) }

    fun setBand(index: Int, band: ParametricBand) = edit { s ->
        if (index !in s.bands.indices) s else s.copy(bands = s.bands.mapIndexed { i, b -> if (i == index) ProEq.clampBand(band) else b })
    }

    fun addBand() = edit { s ->
        if (s.bands.size >= ProEq.MAX_BANDS) s else s.copy(bands = s.bands + ParametricBand(1000.0, 0.0, ProEq.GRAPHIC_Q, FilterType.PEAK))
    }

    fun removeBand(index: Int) = edit { s ->
        if (index !in s.bands.indices) s else s.copy(bands = s.bands.filterIndexed { i, _ -> i != index })
    }

    fun setAutoPreamp(auto: Boolean) = edit { it.copy(autoPreamp = auto, preampDb = if (auto) it.preampDb else it.effectivePreampDb()) }

    fun setPreampDb(db: Double) = edit { it.copy(preampDb = ProEq.clampPreamp(db), autoPreamp = false) }

    fun setLimiter(on: Boolean) = edit(keepPreset = true) { it.copy(limiterEnabled = on) }

    // ---------------------------------------------------------------- presets

    /** Play a built-in or saved preset, and remember it for the connected headphones. */
    fun applyPreset(id: String) {
        val preset = current.value.presets.byId(id) ?: return
        edit(keepPreset = true) { preset.applyTo(it) }
    }

    /** Save the sound as a new preset called [name] and make it the current one. */
    fun saveAs(name: String) {
        val (store, preset) = current.value.presets.saveNew(name, current.value.eq)
        current.update { it.copy(presets = store) }
        edit(keepPreset = true) { it.copy(activePresetId = preset.id) }
    }

    fun rename(id: String, name: String) {
        current.update { it.copy(presets = it.presets.rename(id, name)) }
        scheduleSave()
    }

    fun delete(id: String) {
        current.update { ui ->
            ui.copy(presets = ui.presets.delete(id), eq = if (ui.eq.activePresetId == id) ui.eq.copy(activePresetId = null) else ui.eq)
        }
        scheduleSave()
    }

    /** Import AutoEq text as a new preset called after [fileName] and play it. A file that is not one gives a [ProEqUi.notice]. */
    fun importAutoEq(fileName: String, text: String) {
        val name = fileName.substringBeforeLast('.').removeSuffix("ParametricEQ").trim().ifEmpty { PresetStore.DEFAULT_NAME }
        val imported = runCatching { current.value.presets.importAutoEq(text, name) }.getOrNull()
        if (imported == null) {
            current.update { it.copy(notice = Words("eq_import_failed")) }
            return
        }
        current.update { it.copy(presets = imported.first, notice = null) }
        edit(keepPreset = true) { imported.second.applyTo(it) }
    }

    /** Pick a file and import it. Does nothing where there are no file dialogs. */
    fun importFromFile(title: String) {
        val files = files ?: return
        scope.launch {
            val picked = files.pickText(title)
            if (picked != null) importAutoEq(picked.fileName, picked.text)
        }
    }

    /** The sound as an AutoEq ParametricEQ file. */
    fun exportText(): String {
        val s = current.value.eq
        return AutoEqParser.export(EqPreset(id = "export", name = "export", bands = s.outputBands(), preampDb = s.desktopPreampDb()))
    }

    /** Ask where to save it and write it. */
    fun exportToFile(title: String) {
        val files = files ?: return
        val ui = current.value
        val name = (ui.activePreset?.name ?: PresetStore.DEFAULT_NAME) + ".txt"
        scope.launch { files.saveText(title, name, exportText()) }
    }

    fun dismissNotice() = current.update { it.copy(notice = null) }

    // ---------------------------------------------------------------- plumbing

    /** Wait until the backend has run the latest edit (tests, and leaving the app). */
    suspend fun settle() {
        applyJob?.join()
    }

    /** Write what is pending to the settings store now. */
    fun flushSave() {
        saveJob?.cancel()
        save()
    }

    fun close() {
        flushSave()
        statusJob?.cancel()
        applyJob?.cancel()
    }

    private fun attach(next: EqBackend?) {
        statusJob?.cancel()
        applyJob?.cancel()
        backend = next
        current.update {
            it.copy(
                status = next?.status?.value ?: EqBackendStatus.Unavailable(Words("eq_unavailable_here")),
                hasLimiter = next?.hasLimiter ?: false,
                setupNote = next?.setupNote,
                canImportExport = files != null,
            )
        }
        statusJob = next?.let { b -> scope.launch { b.status.collect { s -> current.update { it.copy(status = s) } } } }
    }

    /**
     * Change the sound. Edits that change what is heard drop the preset name ("Custom") unless [keepPreset];
     * [remember] stores a chosen preset as the one for the connected headphones.
     */
    private fun edit(keepPreset: Boolean = false, immediate: Boolean = false, remember: Boolean = true, change: (ProEqState) -> ProEqState) {
        var changed = false
        current.update { ui ->
            val before = ui.eq
            var after = change(before).sanitized()
            if (!keepPreset && after != before && after.activePresetId == before.activePresetId) after = after.copy(activePresetId = null)
            changed = after != before
            if (!changed) return@update ui
            var presets = ui.presets
            val id = after.activePresetId
            if (remember && id != null && id != before.activePresetId && ui.headphone != null) presets = presets.withPresetFor(ui.headphone, id)
            ui.copy(eq = after, presets = presets)
        }
        if (changed) {
            scheduleSave()
            scheduleApply(immediate)
        }
    }

    private fun scheduleApply(immediate: Boolean) {
        val b = backend ?: return
        applyJob?.cancel()
        applyJob = scope.launch {
            if (!immediate && debounceMs > 0) delay(debounceMs)
            val s = current.value.eq
            if (s.enabled) b.apply(s) else b.off()
            // The status flow reaches the screen a moment later on its own; this makes it true as soon as the backend has answered.
            current.update { it.copy(status = b.status.value) }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (saveMs > 0) delay(saveMs)
            save()
        }
    }

    private fun save() {
        val ui = current.value
        settings.putString(KEY_STATE, ui.eq.encode())
        settings.putStringList(KEY_PRESETS, listOf(ui.presets.encode()))
    }

    companion object {
        const val KEY_STATE = "eq.state"
        const val KEY_PRESETS = "eq.presets"
        const val KEY_HEADPHONE = "eq.headphone"

        private fun load(settings: SettingsStore, headphone: String?): ProEqUi = ProEqUi(
            eq = ProEqState.decode(settings.getString(KEY_STATE)),
            presets = PresetStore.decode(settings.getStringList(KEY_PRESETS).firstOrNull()),
            headphone = headphone,
        )
    }
}
