package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.eq.EqBackendStatus
import io.github.langkurst.ruru.app.eq.EqSetupKind
import io.github.langkurst.ruru.app.eq.ProEqModel
import io.github.langkurst.ruru.app.eq.ProEqUi
import io.github.langkurst.ruru.dsp.EqResponse
import io.github.langkurst.ruru.dsp.FilterType
import io.github.langkurst.ruru.dsp.ParametricBand
import io.github.langkurst.ruru.dsp.PresetStore
import io.github.langkurst.ruru.dsp.ProEq
import io.github.langkurst.ruru.dsp.ProEqMode
import io.github.langkurst.ruru.ui.ButtonRow
import io.github.langkurst.ruru.ui.HintMaxWidth
import io.github.langkurst.ruru.ui.LevelSlider
import io.github.langkurst.ruru.ui.LocalDesk
import io.github.langkurst.ruru.ui.MessageBar
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.PrimaryButton
import io.github.langkurst.ruru.ui.QuietButton
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Segmented
import io.github.langkurst.ruru.ui.SettingRow
import io.github.langkurst.ruru.ui.Toggle
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.deviceName
import io.github.langkurst.ruru.ui.focusRing
import io.github.langkurst.ruru.ui.res.*
import io.github.langkurst.ruru.ui.text
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Pro EQ the Equaliser page edits. The app gives the page its model through this local, so a
 * system with no backend (a Mac, an iPhone, a build without one) has none and the page is not listed
 * (see [PageAvailability.hasProEq]).
 */
internal val LocalProEq = staticCompositionLocalOf<ProEqModel?> { null }

private val PageMaxWidth = 880.dp
private val FieldShape = RoundedCornerShape(7.dp)

/**
 * Equaliser: the status of the filtering program with its one button and the on/off switch, the response
 * curve, presets, a graphic (10 sliders) or parametric (band list) editor, and the preamp. On a phone or in a
 * narrow window it is the same, in one column.
 */
@Composable
internal fun EqualiserPage(state: AppState, controller: HeadsetController) {
    val model = LocalProEq.current
    if (model == null) {
        PlaceholderPage(stringResource(Res.string.panel_equaliser), stringResource(Res.string.page_equaliser_lead), state, controller)
        return
    }
    val ui by model.ui.collectAsState()
    val headphone = if (state.phase == Phase.Connected) deviceName(state) else null
    LaunchedEffect(model, state.demo) { model.setDemo(state.demo) }
    LaunchedEffect(model, headphone) { model.setHeadphone(headphone) }
    LaunchedEffect(model) { model.refresh() }
    // While a step waits for the person (installing the program, fixing a folder) look again now and then.
    val waiting = ui.status is EqBackendStatus.NeedsSetup || ui.status is EqBackendStatus.Unavailable
    LaunchedEffect(model, waiting) {
        while (waiting) {
            delay(RECHECK_MS)
            model.refresh()
        }
    }
    PageFrame(stringResource(Res.string.panel_equaliser), stringResource(Res.string.page_equaliser_lead), state, controller) {
        Column(Modifier.widthIn(max = PageMaxWidth), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusPanel(ui, model)
            ui.notice?.let { MessageBar(it.text(), model::dismissNotice) }
            Panel(stringResource(Res.string.eq_curve_label)) {
                val response = remember(ui.eq) { ui.eq.responseFn() }
                ProEqCurve(response, Modifier.fillMaxWidth().height(if (LocalDesk.current) 176.dp else 148.dp))
            }
            EditorPanel(ui, model)
        }
    }
}

private const val RECHECK_MS = 5_000L

/** One honest sentence under the headphones' own 3-band equaliser where a full one is not possible (a Mac, an iPhone). */
@Composable
internal fun EqNotPossibleNote() {
    val system = LocalSystemKind.current
    if (system != SystemKind.Mac && system != SystemKind.IPhone) return
    Txt(stringResource(Res.string.eq_not_possible_here), Ruru.type.small, Ruru.colors.ink3, Modifier.padding(top = 10.dp).widthIn(max = HintMaxWidth))
}

// ---------------------------------------------------------------- status

@Composable
private fun StatusPanel(ui: ProEqUi, model: ProEqModel) {
    val c = Ruru.colors
    val uri = LocalUriHandler.current
    val status = ui.status
    val label = stringResource(Res.string.eq_switch_label)
    Panel(null) {
        SettingRow(
            label = label,
            hint = stringResource(
                when (status) {
                    EqBackendStatus.On -> Res.string.eq_status_on
                    EqBackendStatus.Ready -> Res.string.eq_status_off
                    is EqBackendStatus.NeedsSetup -> if (status.action.kind == EqSetupKind.ALLOW) Res.string.eq_status_off else Res.string.eq_status_setup
                    is EqBackendStatus.Unavailable -> Res.string.eq_status_unavailable
                },
            ),
        ) {
            Toggle(ui.eq.enabled && status.switchable, model::setEnabled, label, enabled = status.switchable)
        }
        when (status) {
            is EqBackendStatus.NeedsSetup -> {
                val action = status.action
                Spacer(Modifier.height(6.dp))
                Txt(action.message.text(), Ruru.type.body, c.ink2, Modifier.widthIn(max = HintMaxWidth))
                Spacer(Modifier.height(10.dp))
                ButtonRow {
                    PrimaryButton(action.button.text(), {
                        if (action.kind == EqSetupKind.OPEN_WEBSITE) action.url?.let { runCatching { uri.openUri(it) } } else model.perform(action)
                    })
                }
            }
            is EqBackendStatus.Unavailable -> {
                Spacer(Modifier.height(6.dp))
                Txt(status.reason.text(), Ruru.type.body, c.ink2, Modifier.widthIn(max = HintMaxWidth))
            }
            else -> Unit
        }
        ui.setupNote?.let {
            Spacer(Modifier.height(10.dp))
            Txt(it.text(), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        }
    }
}

// ---------------------------------------------------------------- the editor

private sealed interface PresetEdit {
    data object SaveAs : PresetEdit
    data class Rename(val id: String, val name: String) : PresetEdit
    data class Delete(val id: String, val name: String) : PresetEdit
}

@Composable
private fun EditorPanel(ui: ProEqUi, model: ProEqModel) {
    val c = Ruru.colors
    val eq = ui.eq
    Panel(null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PresetSection(ui, model)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(Res.string.eq_mode_label), Ruru.type.section, c.ink2)
                Box(Modifier.widthIn(max = 320.dp)) {
                    Segmented(
                        listOf(
                            ProEqMode.GRAPHIC10 to stringResource(Res.string.eq_mode_graphic),
                            ProEqMode.PARAMETRIC to stringResource(Res.string.eq_mode_parametric),
                        ),
                        eq.mode,
                        model::setMode,
                        stringResource(Res.string.eq_mode_label),
                    )
                }
            }
            when (eq.mode) {
                ProEqMode.GRAPHIC10 -> GraphicSliders(ui, model)
                ProEqMode.PARAMETRIC -> BandList(ui, model)
            }
            PreampSection(ui, model)
        }
    }
}

// ---- presets

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(ui: ProEqUi, model: ProEqModel) {
    val c = Ruru.colors
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<PresetEdit?>(null) }
    val active = ui.activePreset
    val savedActive = active?.takeIf { p -> ui.presets.saved.any { it.id == p.id } }
    val importTitle = stringResource(Res.string.eq_preset_import)
    val exportTitle = stringResource(Res.string.eq_preset_export)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(stringResource(Res.string.eq_preset_label), Ruru.type.section, c.ink2)
        PresetButton(active?.name ?: stringResource(Res.string.eq_preset_custom), open, stringResource(Res.string.eq_preset_label)) { open = !open }
        if (open) {
            Column(
                Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(FieldShape).border(1.dp, c.line, FieldShape).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PresetGroup(stringResource(Res.string.eq_presets_builtin), ui.presets.all().filter { it.isBuiltIn }, ui.eq.activePresetId) {
                    model.applyPreset(it); open = false
                }
                if (ui.presets.saved.isNotEmpty()) {
                    PresetGroup(stringResource(Res.string.eq_presets_saved), ui.presets.saved, ui.eq.activePresetId) {
                        model.applyPreset(it); open = false
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(stringResource(Res.string.eq_preset_save_as), { edit = PresetEdit.SaveAs })
            if (savedActive != null) {
                QuietButton(stringResource(Res.string.eq_preset_rename), { edit = PresetEdit.Rename(savedActive.id, savedActive.name) })
                QuietButton(stringResource(Res.string.eq_preset_delete), { edit = PresetEdit.Delete(savedActive.id, savedActive.name) }, danger = true)
            }
            if (ui.canImportExport) {
                QuietButton(importTitle, { model.importFromFile(importTitle) })
                QuietButton(exportTitle, { model.exportToFile(exportTitle) })
            }
        }
        when (val e = edit) {
            PresetEdit.SaveAs -> NameField("", { model.saveAs(it); edit = null }, { edit = null })
            is PresetEdit.Rename -> NameField(e.name, { model.rename(e.id, it); edit = null }, { edit = null })
            is PresetEdit.Delete -> {
                Txt(stringResource(Res.string.eq_preset_delete_confirm, e.name), Ruru.type.body, c.ink)
                ButtonRow {
                    QuietButton(stringResource(Res.string.eq_preset_delete), { model.delete(e.id); edit = null }, danger = true)
                    QuietButton(stringResource(Res.string.action_cancel), { edit = null })
                }
            }
            null -> Unit
        }
        ui.headphone?.let {
            Txt(stringResource(Res.string.eq_preset_remembered, it), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        }
    }
}

@Composable
private fun PresetButton(text: String, open: Boolean, label: String, onClick: () -> Unit) {
    val c = Ruru.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .focusRing(interaction, FieldShape)
            .clip(FieldShape)
            .background(if (hovered) c.bark else Color.Transparent)
            .border(1.dp, c.line, FieldShape)
            .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .semantics { stateDescription = text; contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, Ruru.type.bodyMedium, c.ink, Modifier.weight(1f), maxLines = 1)
        Canvas(Modifier.size(12.dp, 8.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (open) { moveTo(0f, h); lineTo(w / 2, 0f); lineTo(w, h) } else { moveTo(0f, 0f); lineTo(w / 2, h); lineTo(w, 0f) }
            }
            drawPath(path, c.ink3, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun PresetGroup(title: String, presets: List<io.github.langkurst.ruru.dsp.ProEqPreset>, activeId: String?, onPick: (String) -> Unit) {
    val c = Ruru.colors
    Txt(title, Ruru.type.small, c.ink3, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    for (p in presets) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val selected = p.id == activeId
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .focusRing(interaction, FieldShape)
                .clip(FieldShape)
                .background(if (selected) c.bark else if (hovered) c.bark.copy(alpha = 0.6f) else Color.Transparent)
                .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton) { onPick(p.id) }
                .hoverable(interaction)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Txt(p.name, if (selected) Ruru.type.bodyMedium else Ruru.type.body, c.ink, maxLines = 1)
        }
    }
}

/** A one-line name box; Enter saves, Escape cancels. */
@Composable
private fun NameField(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val c = Ruru.colors
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val label = stringResource(Res.string.eq_preset_name_label)
    fun save() { if (name.isNotBlank()) onSave(name.trim()) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = name,
            onValueChange = { if (it.length <= PresetStore.MAX_NAME_LENGTH) name = it },
            singleLine = true,
            textStyle = Ruru.type.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { save(); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                }
                .semantics { contentDescription = label },
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth().clip(FieldShape).background(c.night).border(1.dp, c.line, FieldShape).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    if (name.isEmpty()) Txt(stringResource(Res.string.eq_preset_name_placeholder), Ruru.type.body, c.ink3)
                    field()
                }
            },
        )
        ButtonRow {
            QuietButton(stringResource(Res.string.action_save), { save() }, enabled = name.isNotBlank())
            QuietButton(stringResource(Res.string.action_cancel), onCancel)
        }
    }
}

// ---- graphic

private val GraphicLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

@Composable
private fun GraphicSliders(ui: ProEqUi, model: ProEqModel) {
    val c = Ruru.colors
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until ProEq.GRAPHIC_BAND_COUNT) {
            val gain = ui.eq.graphicGainsDb.getOrElse(i) { 0.0 }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(signedGain(gain, 1), Ruru.type.small, c.ink2, maxLines = 1)
                GainSlider(gain, "${GraphicLabels[i]} Hz", { model.setGraphicGain(i, it) }, Modifier.fillMaxWidth())
                Txt(GraphicLabels[i], Ruru.type.small, c.ink3, maxLines = 1)
            }
        }
    }
}

/** A vertical gain slider in 0.5 dB steps from -12 to +12; arrow keys step it. */
@Composable
private fun GainSlider(gainDb: Double, label: String, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val change by rememberUpdatedState(onChange)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val radius = with(LocalDensity.current) { 8.dp.toPx() }
    val lo = ProEq.MIN_GAIN_DB
    val hi = ProEq.MAX_GAIN_DB
    fun snap(v: Double) = (floor(v * 2 + 0.5) / 2).coerceIn(lo, hi)
    fun valueAt(y: Float, height: Float): Double {
        val f = ((y - radius) / (height - 2 * radius)).coerceIn(0f, 1f)
        return snap(hi - f * (hi - lo))
    }
    val steps = ((hi - lo) * 2).toInt()
    Box(
        modifier
            .height(150.dp)
            .semantics {
                contentDescription = label
                stateDescription = signedGain(gainDb, 1) + " dB"
                progressBarRangeInfo = ProgressBarRangeInfo(gainDb.toFloat(), lo.toFloat()..hi.toFloat(), steps - 1)
                setProgress { target -> change(snap(target.toDouble())); true }
            }
            .focusable(true, interaction)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.DirectionRight -> { change(snap(gainDb + 0.5)); true }
                    Key.DirectionDown, Key.DirectionLeft -> { change(snap(gainDb - 0.5)); true }
                    Key.PageUp -> { change(snap(gainDb + 3.0)); true }
                    Key.PageDown -> { change(snap(gainDb - 3.0)); true }
                    else -> false
                }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> change(valueAt(o.y, size.height.toFloat())) },
                        onVerticalDrag = { ch, _ -> ch.consume(); change(valueAt(ch.position.y, size.height.toFloat())) },
                    )
                }
                .pointerInput(Unit) { detectTapGestures { o -> change(valueAt(o.y, size.height.toFloat())) } },
        ) {
            val x = size.width / 2f
            val top = radius
            val bottom = size.height - radius
            fun yOf(g: Double) = top + ((hi - g) / (hi - lo)).toFloat() * (bottom - top)
            val track = 4.dp.toPx()
            val zero = yOf(0.0)
            val thumb = yOf(gainDb)
            drawRoundRect(c.ink4.copy(alpha = c.ink4.alpha * 0.6f), Offset(x - track / 2, top), Size(track, bottom - top), CornerRadius(track / 2))
            drawRoundRect(c.ink, Offset(x - track / 2, minOf(zero, thumb)), Size(track, abs(thumb - zero)), CornerRadius(track / 2))
            drawRect(c.ink3, Offset(x - 6.dp.toPx(), zero - 0.5.dp.toPx()), Size(12.dp.toPx(), 1.dp.toPx()))
            drawCircle(c.bough, radius + 2.dp.toPx(), Offset(x, thumb))
            drawCircle(c.ink, radius, Offset(x, thumb))
            if (focused) drawCircle(c.ink2, radius + 4.dp.toPx(), Offset(x, thumb), style = Stroke(1.5.dp.toPx()))
        }
    }
}

// ---- parametric

@Composable
private fun BandList(ui: ProEqUi, model: ProEqModel) {
    val c = Ruru.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.eq.bands.forEachIndexed { i, band -> BandRow(i, band, model) }
        val full = ui.eq.bands.size >= ProEq.MAX_BANDS
        ButtonRow {
            QuietButton(stringResource(Res.string.eq_band_add), { model.addBand() }, enabled = !full)
            if (full) Txt(stringResource(Res.string.eq_band_max, ProEq.MAX_BANDS), Ruru.type.small, c.ink3)
        }
    }
}

@Composable
private fun BandRow(index: Int, band: ParametricBand, model: ProEqModel) {
    val n = index + 1
    val onLabel = stringResource(Res.string.eq_band_on, n)
    val type = stringResource(
        when (band.type) {
            FilterType.PEAK -> Res.string.eq_band_peak
            FilterType.LOW_SHELF -> Res.string.eq_band_low_shelf
            FilterType.HIGH_SHELF -> Res.string.eq_band_high_shelf
        },
    )
    val next = when (band.type) {
        FilterType.PEAK -> FilterType.LOW_SHELF
        FilterType.LOW_SHELF -> FilterType.HIGH_SHELF
        FilterType.HIGH_SHELF -> FilterType.PEAK
    }
    val freq = stringResource(Res.string.eq_band_freq_label, n)
    val gain = stringResource(Res.string.eq_band_gain_label, n)
    val q = stringResource(Res.string.eq_band_q_label, n)
    @Composable
    fun toggle() = Toggle(band.enabled, { model.setBand(index, band.copy(enabled = it)) }, onLabel)
    @Composable
    fun typeButton(modifier: Modifier) = QuietButton(type, { model.setBand(index, band.copy(type = next)) }, modifier)
    @Composable
    fun remove() = QuietButton(stringResource(Res.string.action_remove), { model.removeBand(index) })
    @Composable
    fun freqField(modifier: Modifier) = NumberField(band.frequencyHz, ::formatFrequency, null, "Hz", freq, { model.setBand(index, band.copy(frequencyHz = it)) }, modifier)
    @Composable
    fun gainField(modifier: Modifier) = NumberField(band.gainDb, { fixed(it, 1) }, null, "dB", gain, { model.setBand(index, band.copy(gainDb = it)) }, modifier)
    @Composable
    fun qField(modifier: Modifier) = NumberField(band.q, { fixed(it, 2) }, "Q", null, q, { model.setBand(index, band.copy(q = it)) }, modifier)
    if (LocalDesk.current) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            toggle()
            typeButton(Modifier.width(112.dp))
            freqField(Modifier.weight(1f))
            gainField(Modifier.weight(1f))
            qField(Modifier.weight(1f))
            remove()
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                toggle()
                typeButton(Modifier.weight(1f))
                remove()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                freqField(Modifier.weight(1f))
                gainField(Modifier.weight(1f))
                qField(Modifier.weight(1f))
            }
        }
    }
}

/** A number the person types: kept as text while editing, applied on Enter or when the box loses focus, put back on Escape or a typo. */
@Composable
private fun NumberField(
    value: Double,
    format: (Double) -> String,
    prefix: String?,
    suffix: String?,
    label: String,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ruru.colors
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val v = text.trim().replace(',', '.').replace('−', '-').toDoubleOrNull()
        if (v != null && v.isFinite()) onCommit(v)
        text = format(value)
    }
    BasicTextField(
        value = text,
        onValueChange = { if (it.length <= 10) text = it },
        singleLine = true,
        textStyle = Ruru.type.figure.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        modifier = modifier
            .onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { commit(); focusManager.clearFocus(); true }
                    Key.Escape -> { text = format(value); focusManager.clearFocus(); true }
                    else -> false
                }
            }
            .semantics { contentDescription = label },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(FieldShape).background(c.night).border(1.dp, c.line, FieldShape).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (prefix != null) Txt(prefix, Ruru.type.small, c.ink3, Modifier.padding(end = 6.dp))
                Box(Modifier.weight(1f)) { field() }
                if (suffix != null) Txt(suffix, Ruru.type.small, c.ink3, Modifier.padding(start = 6.dp))
            }
        },
    )
}

// ---- preamp and limiter

@Composable
private fun PreampSection(ui: ProEqUi, model: ProEqModel) {
    val eq = ui.eq
    val shown = ui.shownPreampDb
    val preamp = stringResource(Res.string.eq_preamp)
    Column {
        SettingRow(preamp) { Txt(signedGain(shown, 1) + " dB", Ruru.type.figureMedium, Ruru.colors.ink) }
        SettingRow(stringResource(Res.string.eq_preamp_auto), stringResource(Res.string.eq_preamp_auto_hint)) {
            Toggle(eq.autoPreamp, model::setAutoPreamp, stringResource(Res.string.eq_preamp_auto))
        }
        if (!eq.autoPreamp) {
            LevelSlider(
                value = shown.roundToInt().coerceIn(ProEq.MIN_PREAMP_DB.toInt(), ProEq.MAX_PREAMP_DB.toInt()),
                range = ProEq.MIN_PREAMP_DB.toInt()..ProEq.MAX_PREAMP_DB.toInt(),
                onCommit = { model.setPreampDb(it.toDouble()) },
                label = preamp,
            )
        }
        if (ui.hasLimiter) {
            SettingRow(stringResource(Res.string.eq_limiter), stringResource(Res.string.eq_limiter_hint)) {
                Toggle(eq.limiterEnabled, model::setLimiter, stringResource(Res.string.eq_limiter))
            }
        }
    }
}

// ---------------------------------------------------------------- the response curve

private val CurveLabels = listOf(50.0 to "50", 200.0 to "200", 1000.0 to "1k", 5000.0 to "5k", 10000.0 to "10k")

/**
 * The response of the whole equaliser (bands and preamp) from 20 Hz to 20 kHz on a log axis, with a line at
 * 0 dB and the frequencies 50, 200, 1k, 5k and 10k under it. The height shows at least 12 dB either way, more when the curve needs it.
 */
@Composable
internal fun ProEqCurve(response: (Double) -> Double, modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = Ruru.type.small.copy(color = c.ink3)
    val zeroText = stringResource(Res.string.eq_zero_db)
    val description = stringResource(Res.string.eq_curve_label)
    val freqs = remember { EqResponse.logSpaced(CURVE_POINTS, ProEq.MIN_FREQUENCY_HZ, ProEq.MAX_FREQUENCY_HZ) }
    val gains = remember(response) { DoubleArray(freqs.size) { response(freqs[it]) } }
    Canvas(modifier.semantics { contentDescription = description }) {
        val labelRoom = 20.dp.toPx()
        val w = size.width
        val plotH = size.height - labelRoom
        val peak = max(gains.maxOrNull() ?: 0.0, abs(gains.minOrNull() ?: 0.0))
        val reach = (ceil(max(12.0, peak) / 6.0) * 6.0).coerceAtMost(36.0).toFloat()
        val lnMin = ln(ProEq.MIN_FREQUENCY_HZ)
        val lnSpan = ln(ProEq.MAX_FREQUENCY_HZ) - lnMin
        fun xOf(f: Double) = ((ln(f) - lnMin) / lnSpan).toFloat() * w
        fun yOf(g: Double) = plotH / 2f - (g.toFloat() / reach) * (plotH / 2f - 4.dp.toPx())
        val hair = 1.dp.toPx()
        for ((f, text) in CurveLabels) {
            val x = xOf(f)
            drawLine(c.lineSoft, Offset(x, 0f), Offset(x, plotH), hair)
            val layout = measurer.measure(text, labelStyle)
            drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, w - layout.size.width), plotH + 4.dp.toPx()))
        }
        for (step in 1..2) {
            val g = reach / 2.0 * step
            drawLine(c.lineSoft, Offset(0f, yOf(g)), Offset(w, yOf(g)), hair)
            drawLine(c.lineSoft, Offset(0f, yOf(-g)), Offset(w, yOf(-g)), hair)
        }
        drawLine(c.ink4, Offset(0f, yOf(0.0)), Offset(w, yOf(0.0)), hair)
        val zero = measurer.measure(zeroText, labelStyle)
        drawText(zero, topLeft = Offset(w - zero.size.width, (yOf(0.0) - zero.size.height - 1.dp.toPx()).coerceAtLeast(0f)))
        val curve = Path()
        for (i in freqs.indices) {
            val x = xOf(freqs[i])
            val y = yOf(gains[i]).coerceIn(0f, plotH)
            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        drawPath(curve, c.ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private const val CURVE_POINTS = 240

// ---------------------------------------------------------------- numbers

/** [v] with [decimals] places (a dot, half up). */
internal fun fixed(v: Double, decimals: Int): String {
    val f = 10.0.pow(decimals)
    val scaled = floor(abs(v) * f + 0.5).toLong()
    val sign = if (v < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return sign + scaled
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val cut = digits.length - decimals
    return sign + digits.substring(0, cut) + "." + digits.substring(cut)
}

/** A gain with its sign: +3.5, −2.0, 0.0. */
internal fun signedGain(v: Double, decimals: Int): String {
    val body = fixed(abs(v), decimals)
    val zero = body.all { it == '0' || it == '.' }
    return if (zero) body else if (v > 0) "+$body" else "−$body"
}

internal fun formatFrequency(v: Double): String = if (abs(v - floor(v + 0.5)) < 0.05) fixed(v, 0) else fixed(v, 1)
