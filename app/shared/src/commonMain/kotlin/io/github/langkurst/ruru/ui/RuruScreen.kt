package io.github.langkurst.ruru.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.ConnectionReport
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.SUPPORT_URL
import io.github.langkurst.ruru.app.WEBSITE_URL
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.app.supportNoteKey
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.protocol.bose.AutoOffOptions
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A switch only some platforms have, such as "Start with Windows". */
class PlatformSwitch(val label: StringResource, val hint: StringResource?, val isOn: () -> Boolean, val set: (Boolean) -> Unit)

/** The whole app on one scrolling column; the same on desktop and phone. */
@Composable
fun RuruScreen(model: AppModel, appVersion: String, modifier: Modifier = Modifier, extras: List<PlatformSwitch> = emptyList()) {
    val controller by model.controller.collectAsState()
    val state by controller.state.collectAsState()
    val c = Ruru.colors
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    // The page colour goes on before the caller's modifier, so it also fills the strips behind a
    // phone's status and navigation bars that the safe-area padding in [modifier] leaves.
    Box(Modifier.fillMaxSize().background(c.night).then(modifier), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state)
            state.message?.let { MessageBar(it.text(), controller::dismissMessage) }
            if (state.phase == Phase.Connected) Connected(state.headset, controller) else NotConnected(state, controller, model)
            Footer(state, model, appVersion, extras) { clipboard.setText(AnnotatedString(ConnectionReport.build(state, appVersion))) }
        }
    }
}

@Composable
private fun Header(state: AppState) {
    val c = Ruru.colors
    val t = Ruru.type
    val h = state.headset
    val name = h.info?.customName?.takeIf { it.isNotBlank() }
        ?: h.connection.deviceName
        ?: (state.phase as? Phase.Connecting)?.name
        ?: "Ruru"
    val line = when (state.phase) {
        Phase.Connected -> h.info?.modelName ?: stringResource(Res.string.header_connected)
        is Phase.Connecting -> stringResource(Res.string.header_connecting)
        is Phase.Failed -> stringResource(Res.string.header_not_connected)
        is Phase.NoHeadset -> stringResource(Res.string.header_not_connected)
        Phase.Starting -> stringResource(Res.string.header_looking)
        Phase.Unsupported -> stringResource(Res.string.header_demo_available)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OwlMark(36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, t.title, c.ink, maxLines = 2)
            Txt(if (state.demo) stringResource(Res.string.header_with_demo, line) else line, t.small, c.ink2, maxLines = 2)
        }
    }
    if (state.phase == Phase.Connected) h.batteryPercent?.let { Battery(it, h.isCharging == true) }
}

@Composable
private fun Battery(percent: Int, charging: Boolean) {
    val c = Ruru.colors
    val low = percent <= 15 && !charging
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(if (charging) Res.string.battery_percent_charging else Res.string.battery_percent, percent), Ruru.type.figure, if (low) c.alarm else c.ink2)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(72.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.ink4.copy(alpha = 0.4f))) {
            Box(Modifier.width(72.dp * (percent.coerceIn(0, 100) / 100f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (low) c.alarm else c.ink2))
        }
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.bough).border(1.dp, c.alarm.copy(alpha = 0.5f), shape).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, // screen readers announce it when it appears
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, Ruru.type.body, c.ink, Modifier.weight(1f).padding(end = 12.dp))
        QuietButton(stringResource(Res.string.action_ok), onDismiss)
    }
}

@Composable
private fun NotConnected(state: AppState, controller: HeadsetController, model: AppModel) {
    val c = Ruru.colors
    val phone = state.platformName == "Android" || state.platformName == "iPhone"
    val (title, body) = when (val p = state.phase) {
        Phase.Starting -> stringResource(Res.string.starting_title) to stringResource(
            when {
                state.platformName == "iPhone" -> Res.string.starting_body_iphone
                phone -> Res.string.starting_body_phone
                else -> Res.string.starting_body_computer
            },
        )
        is Phase.NoHeadset -> if (p.blocked != null) {
            stringResource(Res.string.blocked_title) to p.blocked.text()
        } else if (state.platformName == "iPhone") {
            // The iPhone finds headphones over Bluetooth LE, not from the paired list.
            stringResource(Res.string.none_found_title) to stringResource(Res.string.none_found_body_iphone)
        } else if (p.pairedName != null) {
            // The 1st Gen sleeps about 5 minutes after its wear sensor reads "off your head" with
            // nothing playing (firmware 1.6.7), which owners take for a dropped connection.
            stringResource(Res.string.waiting_title, p.pairedName) to
                stringResource(if (phone) Res.string.waiting_body_phone else Res.string.waiting_body_computer)
        } else {
            // Ruru finds headphones by name, so renamed ones don't show up.
            stringResource(Res.string.none_found_title) to
                stringResource(if (phone) Res.string.none_found_body_phone else Res.string.none_found_body_computer)
        }
        is Phase.Connecting -> stringResource(Res.string.connecting_title, p.name) to stringResource(
            if (state.platformName == "iPhone") Res.string.connecting_body_iphone else Res.string.connecting_body,
        )
        is Phase.Failed -> stringResource(Res.string.failed_title, p.name) to p.reason.text()
        Phase.Unsupported -> stringResource(Res.string.unsupported_title) to stringResource(Res.string.unsupported_body)
        Phase.Connected -> "" to ""
    }
    Panel(null) {
        Txt(title, Ruru.type.bodyMedium, c.ink)
        Spacer(Modifier.height(4.dp))
        Txt(body, Ruru.type.body, c.ink2)
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            if (state.phase !is Phase.Unsupported && state.phase !is Phase.Connecting) QuietButton(stringResource(Res.string.action_try_again), controller::retryNow)
            if (!state.demo) QuietButton(stringResource(Res.string.action_try_demo), { model.setDemo(true) })
        }
    }
}

@Composable
private fun Connected(h: HeadphoneState, controller: HeadsetController) {
    val caps = h.capabilities
    if (caps.listeningModes && h.modes.isNotEmpty()) ModesPanel(h, controller)
    if (caps.noiseCancelling && h.noise != null) NoisePanel(h, h.noise!!, controller)
    h.eq?.let { EqPanel(it, h.eqRange ?: -10..10, controller) }
    h.shortcutButton?.let { button ->
        if (button.supportedActions.size > 1) {
            Panel(stringResource(Res.string.panel_shortcut)) {
                ChoiceRow(
                    label = buttonEventResource(button.eventLabel)?.let { stringResource(it) } ?: button.eventLabel ?: stringResource(Res.string.shortcut_press),
                    options = button.supportedActions.map { it to stringResource(it.resource()) },
                    selected = button.action,
                    onSelect = controller::setShortcut,
                )
            }
        }
    }
    SettingsPanel(h, controller)
    AboutPanel(h)
}

private fun ListeningMode.isEmptySlot() = !isPreset && (!isConfigured || name.isBlank())

@Composable
private fun ModesPanel(h: HeadphoneState, controller: HeadsetController) {
    val c = Ruru.colors
    val shown = h.modes.filter { !it.isEmptySlot() || it.index == h.currentModeIndex }
    val freeSlot = h.modes.firstOrNull { it.isEditable && it.isEmptySlot() }
    val current = h.modes.firstOrNull { it.index == h.currentModeIndex }
    var adding by remember { mutableStateOf(false) }
    var confirmRemove by remember(h.currentModeIndex) { mutableStateOf(false) }
    Panel(stringResource(Res.string.panel_modes), Modifier.animateContentSize(moveSpec())) {
        ModeList(shown, h.currentModeIndex, controller::setMode)
        if (adding && freeSlot != null) {
            Spacer(Modifier.height(8.dp))
            NewModeEditor(onSave = { name ->
                controller.saveMode(freeSlot.index, name, h.noise ?: NoiseSettings())
                adding = false
            }, onCancel = { adding = false })
        } else if (confirmRemove && current != null) {
            Spacer(Modifier.height(8.dp))
            Txt(stringResource(Res.string.modes_remove_confirm, current.name), Ruru.type.body, c.ink2)
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                QuietButton(stringResource(Res.string.action_remove), { controller.removeMode(current.index); confirmRemove = false }, danger = true)
                QuietButton(stringResource(Res.string.action_cancel), { confirmRemove = false })
            }
        } else if (h.capabilities.editableModes) {
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                if (freeSlot != null) QuietButton(stringResource(Res.string.modes_add), { adding = true })
                if (current != null && !current.isPreset && current.isEditable) QuietButton(stringResource(Res.string.modes_remove_named, current.name), { confirmRemove = true })
            }
        }
    }
}

@Composable
private fun ModeList(modes: List<ListeningMode>, current: Int?, onSelect: (Int) -> Unit) {
    val c = Ruru.colors
    val rowHeight = 40.dp
    val selectedRow = modes.indexOfFirst { it.index == current }
    val shape = RoundedCornerShape(7.dp)
    Box(Modifier.fillMaxWidth().selectableGroup()) {
        if (selectedRow >= 0) {
            val y by animateDpAsState(rowHeight * selectedRow, moveSpec())
            Box(Modifier.offset(y = y).fillMaxWidth().height(rowHeight).clip(shape).background(c.eye))
        }
        Column {
            modes.forEachIndexed { i, mode ->
                val selected = i == selectedRow
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState() // a tap on a phone, where there is no hover
                val tint by animateColorAsState(if ((hovered || pressed) && !selected) c.bark else Color.Transparent, hoverSpec())
                val ink by animateColorAsState(if (selected) c.onEye else c.ink, hoverSpec())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        // On the yellow marker the usual ring disappears; the marker's own ink shows.
                        .focusRing(interaction, shape, if (selected) c.onEye else null)
                        .clip(shape)
                        .background(tint)
                        .selectable(selected, role = Role.RadioButton, interactionSource = interaction, indication = null) { onSelect(mode.index) }
                        .hoverable(interaction)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(mode.name.ifBlank { stringResource(Res.string.modes_fallback_name, mode.index + 1) }, if (selected) Ruru.type.bodyMedium else Ruru.type.body, ink, Modifier.weight(1f), maxLines = 1)
                    if (!mode.isPreset) Txt(modeSummary(mode), Ruru.type.small, if (selected) c.onEye.copy(alpha = 0.7f) else c.ink3, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun modeSummary(mode: ListeningMode): String {
    val s = mode.settings ?: return ""
    val parts = buildList {
        add(stringResource(Res.string.modes_summary_noise, s.cancellingLevel))
        if (s.windBlock) add(stringResource(Res.string.noise_wind_block))
        if (s.spatialAudio != SpatialAudio.OFF) add(stringResource(Res.string.modes_summary_immersive))
    }
    return parts.joinToString(stringResource(Res.string.list_separator))
}

@Composable
private fun NewModeEditor(onSave: (String) -> Unit, onCancel: () -> Unit) {
    val c = Ruru.colors
    var name by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(7.dp)
    val focus = remember { FocusRequester() }
    val fieldLabel = stringResource(Res.string.modes_new_label)
    fun save() { if (name.isNotBlank()) onSave(name.trim()) }
    // Typing can start straight away; Enter saves and Escape cancels, as in any small form.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Txt(stringResource(Res.string.modes_new_hint), Ruru.type.small, c.ink3)
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = name,
        onValueChange = { if (it.length <= 24) name = it },
        singleLine = true,
        textStyle = Ruru.type.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier
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
            .semantics { contentDescription = fieldLabel },
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth().clip(shape).background(c.night).border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (name.isEmpty()) Txt(stringResource(Res.string.modes_new_placeholder), Ruru.type.body, c.ink3)
                field()
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    ButtonRow {
        QuietButton(stringResource(Res.string.action_save), { save() }, enabled = name.isNotBlank())
        QuietButton(stringResource(Res.string.action_cancel), onCancel)
    }
}

@Composable
private fun NoisePanel(h: HeadphoneState, noise: NoiseSettings, controller: HeadsetController) {
    val c = Ruru.colors
    val caps = h.capabilities
    val active = h.modes.firstOrNull { it.index == h.currentModeIndex }
    val editable = caps.liveNoiseControl || (active != null && !active.isPreset && active.isEditable)
    var level by remember(noise.cancellingLevel) { mutableStateOf(noise.cancellingLevel) }
    val levelLabel = stringResource(Res.string.noise_cancelling_level)
    val onLabel = stringResource(Res.string.noise_cancelling_on)
    val windLabel = stringResource(Res.string.noise_wind_block)
    val immersiveLabel = stringResource(Res.string.noise_immersive)
    Panel(stringResource(Res.string.panel_noise)) {
        SettingRow(stringResource(Res.string.noise_cancelling), enabled = editable) { Txt("$level", Ruru.type.figureMedium, if (editable) c.ink else c.ink3) }
        LevelSlider(
            value = noise.cancellingLevel,
            range = 0..10,
            onPreview = { level = it },
            onCommit = { controller.setNoise(noise.copy(cancellingLevel = it)) },
            label = levelLabel,
            enabled = editable && (noise.ancEnabled || !caps.ancToggle),
        )
        if (caps.ancToggle) {
            SettingRow(onLabel, enabled = editable) {
                Toggle(noise.ancEnabled, { controller.setNoise(noise.copy(ancEnabled = it)) }, onLabel, editable)
            }
        }
        if (caps.windBlock) {
            SettingRow(windLabel, stringResource(Res.string.noise_wind_block_hint), enabled = editable) {
                Toggle(noise.windBlock, { controller.setNoise(noise.copy(windBlock = it)) }, windLabel, editable)
            }
        }
        if (caps.spatialAudio) {
            Spacer(Modifier.height(4.dp))
            Txt(immersiveLabel, Ruru.type.body, if (editable) c.ink else c.ink3)
            Spacer(Modifier.height(6.dp))
            Segmented(SpatialAudio.entries.map { it to stringResource(it.resource()) }, noise.spatialAudio, { controller.setNoise(noise.copy(spatialAudio = it)) }, immersiveLabel, editable)
        }
        if (!editable) {
            Spacer(Modifier.height(10.dp))
            val activeName = active?.name?.ifBlank { null }
            Txt(
                if (activeName != null) stringResource(Res.string.noise_built_in_named, activeName) else stringResource(Res.string.noise_built_in_unnamed),
                Ruru.type.small,
                c.ink3,
            )
        }
    }
}

@Composable
private fun EqPanel(eq: HeadphoneEq, range: IntRange, controller: HeadsetController) {
    val c = Ruru.colors
    Panel(stringResource(Res.string.panel_equaliser)) {
        val bands = listOf(
            stringResource(Res.string.eq_bass) to eq.bass,
            stringResource(Res.string.eq_mid) to eq.mid,
            stringResource(Res.string.eq_treble) to eq.treble,
        )
        bands.forEachIndexed { i, (name, value) ->
            var shown by remember(value) { mutableStateOf(value) }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(name, Ruru.type.body, c.ink, Modifier.width(60.dp))
                LevelSlider(
                    value = value,
                    range = range,
                    onPreview = { shown = it },
                    onCommit = { v -> controller.setEq(when (i) { 0 -> eq.copy(bass = v); 1 -> eq.copy(mid = v); else -> eq.copy(treble = v) }) },
                    label = name,
                    modifier = Modifier.weight(1f),
                )
                Txt(signed(shown), Ruru.type.figureMedium, c.ink, Modifier.width(40.dp), align = TextAlign.End)
            }
        }
        if (eq != HeadphoneEq()) {
            Spacer(Modifier.height(8.dp))
            ButtonRow { QuietButton(stringResource(Res.string.eq_reset), { controller.setEq(HeadphoneEq()) }) }
        }
    }
}

private fun signed(v: Int) = when {
    v > 0 -> "+$v"
    v < 0 -> "−${-v}"
    else -> "0"
}

@Composable
private fun SettingsPanel(h: HeadphoneState, controller: HeadsetController) {
    val any = h.sidetone != null || h.multipointEnabled != null || h.autoOffMinutes != null ||
        h.autoPlayPause != null || h.autoAnswer != null || h.voicePrompts != null
    if (!any) return
    val c = Ruru.colors
    val selfVoice = stringResource(Res.string.setting_self_voice)
    val multipoint = stringResource(Res.string.setting_multipoint)
    val autoPlayPause = stringResource(Res.string.setting_auto_play_pause)
    val autoAnswer = stringResource(Res.string.setting_auto_answer)
    val voicePrompts = stringResource(Res.string.setting_voice_prompts)
    Panel(stringResource(Res.string.panel_settings)) {
        h.sidetone?.let { level ->
            Txt(selfVoice, Ruru.type.body, c.ink)
            Txt(stringResource(Res.string.setting_self_voice_hint), Ruru.type.small, c.ink3)
            Spacer(Modifier.height(6.dp))
            Segmented(Sidetone.entries.map { it to stringResource(it.resource()) }, level, controller::setSidetone, selfVoice)
            Spacer(Modifier.height(6.dp))
        }
        h.multipointEnabled?.let { on ->
            SettingRow(multipoint, stringResource(Res.string.setting_multipoint_hint)) { Toggle(on, controller::setMultipoint, multipoint) }
        }
        h.autoOffMinutes?.let { minutes ->
            ChoiceRow(
                label = stringResource(Res.string.setting_auto_off),
                hint = stringResource(Res.string.setting_auto_off_hint),
                options = AutoOffOptions.MINUTES.map { it to autoOffLabel(it) },
                selected = minutes,
                onSelect = controller::setAutoOff,
            )
        }
        h.autoPlayPause?.let { on ->
            SettingRow(autoPlayPause, stringResource(Res.string.setting_auto_play_pause_hint)) { Toggle(on, controller::setAutoPlayPause, autoPlayPause) }
        }
        h.autoAnswer?.let { on ->
            SettingRow(autoAnswer, stringResource(Res.string.setting_auto_answer_hint)) { Toggle(on, controller::setAutoAnswer, autoAnswer) }
        }
        h.voicePrompts?.let { prompts ->
            SettingRow(voicePrompts, stringResource(Res.string.setting_voice_prompts_hint)) { Toggle(prompts.enabled, controller::setVoicePrompts, voicePrompts) }
        }
    }
}

@Composable
private fun autoOffLabel(minutes: Int): String = when {
    minutes == AutoOffOptions.NEVER -> stringResource(Res.string.setting_auto_off_never)
    minutes < 60 -> pluralStringResource(Res.plurals.setting_auto_off_minutes, minutes, minutes)
    else -> (minutes / 60).let { hours -> pluralStringResource(Res.plurals.setting_auto_off_hours, hours, hours) }
}

@Composable
private fun AboutPanel(h: HeadphoneState) {
    val info = h.info ?: return
    val c = Ruru.colors
    Panel(stringResource(Res.string.panel_about)) {
        InfoRow(stringResource(Res.string.about_model), info.modelName)
        // Firmware reads like "1.6.7+g6ebabd2"; the part after + is a build tag, kept for the report.
        InfoRow(stringResource(Res.string.about_firmware), info.firmwareVersion?.substringBefore('+'))
        InfoRow(stringResource(Res.string.about_protocol), info.bmapVersion?.let { "BMAP $it" })
        // "Tested" means the code has run on this model's real hardware; "verified" only that its
        // layout matches captures, which is not the same promise.
        h.capabilities.supportNote?.let {
            Spacer(Modifier.height(6.dp))
            Txt(supportNoteKey(it)?.let { key -> Words(key).text() } ?: it, Ruru.type.small, c.ink3)
        }
        if (!h.capabilities.tested) {
            Spacer(Modifier.height(6.dp))
            Txt(stringResource(Res.string.about_untested), Ruru.type.small, c.ink3)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val c = Ruru.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Txt(label, Ruru.type.body, c.ink2, Modifier.weight(1f))
        Txt(value, Ruru.type.figure, c.ink, maxLines = 1)
    }
}

@Composable
private fun Footer(state: AppState, model: AppModel, appVersion: String, extras: List<PlatformSwitch>, onCopyReport: () -> Unit) {
    val c = Ruru.colors
    var copied by remember { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    Panel(null) {
        extras.forEach { extra ->
            var on by remember(extra) { mutableStateOf(extra.isOn()) }
            val extraLabel = stringResource(extra.label)
            SettingRow(extraLabel, extra.hint?.let { stringResource(it) }) {
                Toggle(on, { extra.set(it); on = extra.isOn() }, extraLabel)
            }
        }
        val demoLabel = stringResource(Res.string.footer_demo_mode)
        SettingRow(demoLabel, stringResource(Res.string.footer_demo_mode_hint)) {
            Toggle(state.demo, model::setDemo, demoLabel)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.footer_version, appVersion), Ruru.type.small, c.ink3, Modifier.weight(1f).padding(end = 8.dp))
            // Opens the download page in the browser, which says whether this version is the newest.
            QuietButton(stringResource(Res.string.footer_check_updates), { runCatching { browser.openUri("$WEBSITE_URL/?have=$appVersion") } })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.footer_report_hint), Ruru.type.small, c.ink3, Modifier.weight(1f).padding(end = 8.dp))
            QuietButton(stringResource(if (copied) Res.string.footer_report_copied else Res.string.footer_report_copy), { onCopyReport(); copied = true })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(Res.string.footer_support_hint), Ruru.type.small, c.ink3, Modifier.weight(1f).padding(end = 8.dp))
            // Opens the page in the browser. With no browser to hand, the button does nothing.
            QuietButton(stringResource(Res.string.footer_support), { runCatching { browser.openUri(SUPPORT_URL) } })
        }
    }
}
