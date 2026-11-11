package io.github.langkurst.ruru.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.ConnectionReport
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.protocol.bose.AutoOffOptions

/** The whole app on one scrolling column; the same on desktop and phone. */
@Composable
fun RuruScreen(model: AppModel, appVersion: String, modifier: Modifier = Modifier) {
    val controller by model.controller.collectAsState()
    val state by controller.state.collectAsState()
    val c = Ruru.colors
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Box(modifier.fillMaxSize().background(c.night), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state)
            state.message?.let { MessageBar(it, controller::dismissMessage) }
            if (state.phase == Phase.Connected) Connected(state.headset, controller) else NotConnected(state, controller, model)
            Footer(state, model, appVersion) { clipboard.setText(AnnotatedString(ConnectionReport.build(state, appVersion))) }
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
    val line = when (val p = state.phase) {
        Phase.Connected -> h.info?.modelName ?: "Connected"
        is Phase.Connecting -> "Connecting"
        is Phase.Failed -> "Not connected"
        is Phase.NoHeadset -> "Not connected"
        Phase.Starting -> "Looking for headphones"
        Phase.Unsupported -> "Demo mode is available"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OwlMark(36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, t.title, c.ink, maxLines = 1)
            Txt(if (state.demo) "$line, demo mode" else line, t.small, c.ink2, maxLines = 1)
        }
    }
    if (state.phase == Phase.Connected) h.batteryPercent?.let { Battery(it, h.isCharging == true) }
}

@Composable
private fun Battery(percent: Int, charging: Boolean) {
    val c = Ruru.colors
    val low = percent <= 15 && !charging
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("Battery $percent%${if (charging) ", charging" else ""}", Ruru.type.figure, if (low) c.alarm else c.ink2)
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
        Modifier.fillMaxWidth().clip(shape).background(c.bough).border(1.dp, c.alarm.copy(alpha = 0.5f), shape).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, Ruru.type.body, c.ink, Modifier.weight(1f).padding(end = 12.dp))
        QuietButton("OK", onDismiss)
    }
}

@Composable
private fun NotConnected(state: AppState, controller: HeadsetController, model: AppModel) {
    val c = Ruru.colors
    val (title, body) = when (val p = state.phase) {
        Phase.Starting -> "Looking for your headphones" to "Ruru checks the Bluetooth devices paired with this ${noun(state)}."
        is Phase.NoHeadset -> if (p.pairedName != null) {
            "${p.pairedName} is not connected" to "Turn the headphones on. If they connect to another device first, choose this ${noun(state)} in their Bluetooth list. Ruru connects by itself."
        } else {
            "No Bose headphones paired" to "Pair your headphones in this ${noun(state)}'s Bluetooth settings, then come back. Ruru connects by itself."
        }
        is Phase.Connecting -> "Connecting to ${p.name}" to "This takes a few seconds."
        is Phase.Failed -> "Could not connect to ${p.name}" to p.reason
        Phase.Unsupported -> "Not available on this system yet" to "Ruru can't open the headphones' control channel here. Demo mode shows how the app works."
        Phase.Connected -> "" to ""
    }
    Panel(null) {
        Txt(title, Ruru.type.bodyMedium, c.ink)
        Spacer(Modifier.height(4.dp))
        Txt(body, Ruru.type.body, c.ink2)
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            if (state.phase !is Phase.Unsupported && state.phase !is Phase.Connecting) QuietButton("Try again", controller::retryNow)
            if (!state.demo) QuietButton("Try the demo", { model.setDemo(true) })
        }
    }
}

private fun noun(state: AppState) = if (state.platformName == "Android" || state.platformName == "iPhone") "phone" else "computer"

@Composable
private fun Connected(h: HeadphoneState, controller: HeadsetController) {
    val caps = h.capabilities
    if (caps.listeningModes && h.modes.isNotEmpty()) ModesPanel(h, controller)
    if (caps.noiseCancelling && h.noise != null) NoisePanel(h, h.noise!!, controller)
    h.eq?.let { EqPanel(it, h.eqRange ?: -10..10, controller) }
    h.shortcutButton?.let { button ->
        if (button.supportedActions.size > 1) {
            Panel("Shortcut button") {
                ChoiceRow(
                    label = button.eventLabel ?: "Press",
                    hint = "What the headphones do when you use the shortcut",
                    options = button.supportedActions.map { it to it.label },
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
    Panel("Listening mode", Modifier.animateContentSize(moveSpec())) {
        ModeList(shown, h.currentModeIndex, controller::setMode)
        if (adding && freeSlot != null) {
            Spacer(Modifier.height(8.dp))
            NewModeEditor(onSave = { name ->
                controller.saveMode(freeSlot.index, name, h.noise ?: NoiseSettings())
                adding = false
            }, onCancel = { adding = false })
        } else if (confirmRemove && current != null) {
            Spacer(Modifier.height(8.dp))
            Txt("Remove ${current.name}? Its settings are cleared from the headphones.", Ruru.type.body, c.ink2)
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                QuietButton("Remove", { controller.removeMode(current.index); confirmRemove = false }, danger = true)
                QuietButton("Cancel", { confirmRemove = false })
            }
        } else if (h.capabilities.editableModes) {
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                if (freeSlot != null) QuietButton("Add a mode", { adding = true })
                if (current != null && !current.isPreset && current.isEditable) QuietButton("Remove ${current.name}", { confirmRemove = true })
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
                val tint by animateColorAsState(if (hovered && !selected) c.bark else Color.Transparent, hoverSpec())
                val ink by animateColorAsState(if (selected) c.onEye else c.ink, hoverSpec())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .focusRing(interaction, shape)
                        .clip(shape)
                        .background(tint)
                        .selectable(selected, role = Role.RadioButton, interactionSource = interaction, indication = null) { onSelect(mode.index) }
                        .hoverable(interaction)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(mode.name.ifBlank { "Mode ${mode.index + 1}" }, if (selected) Ruru.type.bodyMedium else Ruru.type.body, ink, Modifier.weight(1f), maxLines = 1)
                    if (!mode.isPreset) Txt(modeSummary(mode), Ruru.type.small, if (selected) c.onEye.copy(alpha = 0.7f) else c.ink3, maxLines = 1)
                }
            }
        }
    }
}

private fun modeSummary(mode: ListeningMode): String {
    val s = mode.settings ?: return ""
    val parts = buildList {
        add("Noise ${s.cancellingLevel}")
        if (s.windBlock) add("Wind Block")
        if (s.spatialAudio != SpatialAudio.OFF) add("Immersive")
    }
    return parts.joinToString(", ")
}

@Composable
private fun NewModeEditor(onSave: (String) -> Unit, onCancel: () -> Unit) {
    val c = Ruru.colors
    var name by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(7.dp)
    Txt("Name for the new mode. It starts with the current noise settings.", Ruru.type.small, c.ink3)
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = name,
        onValueChange = { if (it.length <= 24) name = it },
        singleLine = true,
        textStyle = Ruru.type.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth().clip(shape).background(c.night).border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (name.isEmpty()) Txt("For example, Commute", Ruru.type.body, c.ink4)
                field()
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    ButtonRow {
        QuietButton("Save", { onSave(name.trim()) }, enabled = name.isNotBlank())
        QuietButton("Cancel", onCancel)
    }
}

@Composable
private fun NoisePanel(h: HeadphoneState, noise: NoiseSettings, controller: HeadsetController) {
    val c = Ruru.colors
    val caps = h.capabilities
    val active = h.modes.firstOrNull { it.index == h.currentModeIndex }
    val editable = caps.liveNoiseControl || (active != null && !active.isPreset && active.isEditable)
    var level by remember(noise.cancellingLevel) { mutableStateOf(noise.cancellingLevel) }
    Panel("Noise") {
        SettingRow("Noise cancelling", enabled = editable) { Txt("$level", Ruru.type.figureMedium, if (editable) c.ink else c.ink3) }
        LevelSlider(
            value = noise.cancellingLevel,
            range = 0..10,
            onPreview = { level = it },
            onCommit = { controller.setNoise(noise.copy(cancellingLevel = it)) },
            label = "Noise cancelling level",
            enabled = editable && (noise.ancEnabled || !caps.ancToggle),
        )
        if (caps.ancToggle) {
            SettingRow("Noise cancelling on", enabled = editable) {
                Toggle(noise.ancEnabled, { controller.setNoise(noise.copy(ancEnabled = it)) }, "Noise cancelling on", editable)
            }
        }
        if (caps.windBlock) {
            SettingRow("Wind Block", "Cuts wind noise outdoors", enabled = editable) {
                Toggle(noise.windBlock, { controller.setNoise(noise.copy(windBlock = it)) }, "Wind Block", editable)
            }
        }
        if (caps.spatialAudio) {
            Spacer(Modifier.height(4.dp))
            Txt("Immersive Audio", Ruru.type.body, if (editable) c.ink else c.ink3)
            Spacer(Modifier.height(6.dp))
            Segmented(SpatialAudio.entries.map { it to it.label }, noise.spatialAudio, { controller.setNoise(noise.copy(spatialAudio = it)) }, "Immersive Audio", editable)
        }
        if (!editable) {
            Spacer(Modifier.height(10.dp))
            Txt(
                "${active?.name?.ifBlank { null } ?: "This mode"} is built into the headphones and can't be changed. Choose or add one of your own modes to set these.",
                Ruru.type.small,
                c.ink3,
            )
        }
    }
}

@Composable
private fun EqPanel(eq: HeadphoneEq, range: IntRange, controller: HeadsetController) {
    val c = Ruru.colors
    Panel("Equaliser") {
        val bands = listOf("Bass" to eq.bass, "Mid" to eq.mid, "Treble" to eq.treble)
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
            ButtonRow { QuietButton("Reset to flat", { controller.setEq(HeadphoneEq()) }) }
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
    Panel("Settings") {
        h.sidetone?.let { level ->
            Txt("Self voice", Ruru.type.body, c.ink)
            Txt("How much of your own voice you hear on calls", Ruru.type.small, c.ink3)
            Spacer(Modifier.height(6.dp))
            Segmented(Sidetone.entries.map { it to it.label }, level, controller::setSidetone, "Self voice")
            Spacer(Modifier.height(6.dp))
        }
        h.multipointEnabled?.let { on ->
            SettingRow("Multipoint", "Stay connected to two devices at once") { Toggle(on, controller::setMultipoint, "Multipoint") }
        }
        h.autoOffMinutes?.let { minutes ->
            ChoiceRow(
                label = "Auto-off",
                hint = "Turn off after this long without use",
                options = AutoOffOptions.MINUTES.map { it to autoOffLabel(it) },
                selected = minutes,
                onSelect = controller::setAutoOff,
            )
        }
        h.autoPlayPause?.let { on ->
            SettingRow("Auto play/pause", "Pause when you take them off") { Toggle(on, controller::setAutoPlayPause, "Auto play/pause") }
        }
        h.autoAnswer?.let { on ->
            SettingRow("Auto-answer calls", "Answer when you put them on") { Toggle(on, controller::setAutoAnswer, "Auto-answer calls") }
        }
        h.voicePrompts?.let { prompts ->
            SettingRow("Voice prompts", "Spoken updates such as battery level") { Toggle(prompts.enabled, controller::setVoicePrompts, "Voice prompts") }
        }
    }
}

private fun autoOffLabel(minutes: Int) = when {
    minutes == AutoOffOptions.NEVER -> "Never"
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}

@Composable
private fun AboutPanel(h: HeadphoneState) {
    val info = h.info ?: return
    val c = Ruru.colors
    Panel("About these headphones") {
        InfoRow("Model", info.modelName)
        InfoRow("Firmware", info.firmwareVersion)
        InfoRow("Control protocol", info.bmapVersion?.let { "BMAP $it" })
        if (!h.capabilities.verified) {
            Spacer(Modifier.height(6.dp))
            Txt("Ruru has not been tested on this model yet. If something looks wrong, a connection report helps.", Ruru.type.small, c.ink3)
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
private fun Footer(state: AppState, model: AppModel, appVersion: String, onCopyReport: () -> Unit) {
    val c = Ruru.colors
    var copied by remember { mutableStateOf(false) }
    Panel(null) {
        SettingRow("Demo mode", "A simulated QC Ultra Headphones, for trying Ruru without headphones") {
            Toggle(state.demo, model::setDemo, "Demo mode")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Ruru $appVersion. Not affiliated with Bose.", Ruru.type.small, c.ink3, Modifier.weight(1f).padding(end = 8.dp))
            QuietButton(if (copied) "Copied" else "Copy connection report", { onCopyReport(); copied = true })
        }
    }
}
