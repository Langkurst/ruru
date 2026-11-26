package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.app.tools.LabController
import io.github.langkurst.ruru.app.tools.LabReport
import io.github.langkurst.ruru.app.tools.LabState
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.ui.ButtonRow
import io.github.langkurst.ruru.ui.QuietButton
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.tools.AudioTools
import io.github.langkurst.ruru.app.tools.FindController
import io.github.langkurst.ruru.app.tools.FindState
import io.github.langkurst.ruru.app.tools.FindTone
import io.github.langkurst.ruru.app.tools.MicCheckController
import io.github.langkurst.ruru.app.tools.MicCheckState
import io.github.langkurst.ruru.app.tools.audioTools
import io.github.langkurst.ruru.ui.HintMaxWidth
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.PrimaryButton
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.res.*
import io.github.langkurst.ruru.ui.text
import org.jetbrains.compose.resources.stringResource

/** True in a snapshot that should show the Lab with its result: the scan starts by itself. */
val LocalLabAutoStart = staticCompositionLocalOf { false }

/** The sound tools of this system. Tests and snapshots can provide their own. */
val LocalAudioTools = staticCompositionLocalOf { audioTools() }

/**
 * Whether the Tools page has anything to show: Find needs the headphones connected (a tone for headphones
 * Ruru can't reach is no use), and the mic check works with whatever microphone the system has.
 */
internal fun hasTools(tools: AudioTools, connected: Boolean, lab: Boolean = false): Boolean = tools.mic != null || (connected && tools.find != null) || (connected && lab)

/**
 * Whether the Lab has something to scan: headphones Ruru drives with a session of their own. AirPods and Beats
 * (battery from adverts, no session) and any headset Ruru does not open have nothing to read.
 */
internal fun hasLab(h: HeadphoneState): Boolean = h.capabilities.ownSettings && !h.capabilities.batteryOnly

/** Tools: Find my headphones, the mic check and the Lab. */
@Composable
internal fun ToolsPage(state: AppState, controller: HeadsetController, appVersion: String = RURU_VERSION) {
    PageFrame(stringResource(Res.string.page_tools), stringResource(Res.string.page_tools_lead), state, controller) {
        ToolPanels(state, controller, appVersion)
    }
}

/** The tools' panels, one under the other: on the Tools page and, on a phone, in More. */
@Composable
internal fun ToolPanels(state: AppState, controller: HeadsetController, appVersion: String = RURU_VERSION) {
    val tools = LocalAudioTools.current
    val connected = state.phase == Phase.Connected
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tools.find?.let { if (connected) FindPanel(it) }
        tools.mic?.let { MicCheckPanel(it) }
        if (connected && hasLab(state.headset)) LabPanel(state, controller, appVersion)
    }
}

/**
 * The Lab: a scan that only reads. It asks the open session to read what the headphones report (GET only,
 * through the session's write guards) and lists the answers; Copy report puts the list on the clipboard.
 */
@Composable
private fun LabPanel(state: AppState, controller: HeadsetController, appVersion: String) {
    val c = Ruru.colors
    val scope = rememberCoroutineScope()
    val lab = remember(controller) { LabController(scope, controller) }
    val phase = lab.state.collectAsState().value
    DisposableEffect(lab) { onDispose { lab.clear() } }
    val autoStart = LocalLabAutoStart.current
    LaunchedEffect(lab) { if (autoStart) lab.start() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(phase) { mutableStateOf(false) }
    Panel(stringResource(Res.string.tools_lab_title), Modifier.widthIn(max = 620.dp)) {
        Txt(stringResource(Res.string.tools_lab_text), Ruru.type.body, c.ink2, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(4.dp))
        Txt(stringResource(Res.string.tools_lab_note), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            PrimaryButton(stringResource(Res.string.tools_lab_start), lab::start, enabled = phase != LabState.Scanning)
            if (phase is LabState.Done) {
                val system = LocalSystemKind.current.platformName.ifEmpty { state.platformName }
                QuietButton(stringResource(if (copied) Res.string.tools_lab_copied else Res.string.tools_lab_copy), {
                    clipboard.setText(AnnotatedString(LabReport.build(appVersion, system, state.headset.info?.modelName, phase.lines)))
                    copied = true
                })
            }
        }
        when (phase) {
            LabState.Idle -> Unit
            LabState.Scanning -> {
                Spacer(Modifier.height(8.dp))
                Txt(stringResource(Res.string.tools_lab_scanning), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
            }
            is LabState.Problem -> {
                Spacer(Modifier.height(8.dp))
                Txt(phase.words.text(), Ruru.type.small, c.ink2, Modifier.widthIn(max = HintMaxWidth))
            }
            is LabState.Done -> {
                Spacer(Modifier.height(8.dp))
                Txt(pluralStringResource(Res.plurals.tools_lab_done, phase.lines.size, phase.lines.size), Ruru.type.small, c.ink3)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    phase.lines.forEach { Txt(it, Ruru.type.small, c.ink2) }
                }
            }
        }
    }
}

@Composable
private fun FindPanel(find: FindController) {
    val c = Ruru.colors
    val ringing = find.state.collectAsState().value
    // Leaving the page, or losing the headphones, ends the tone.
    DisposableEffect(find) { onDispose { find.stop() } }
    Panel(stringResource(Res.string.tools_find_title), Modifier.widthIn(max = 620.dp)) {
        Txt(
            stringResource(Res.string.tools_find_text, FindTone.MAX_RING_MS.toInt() / 1000),
            Ruru.type.body, c.ink2, Modifier.widthIn(max = HintMaxWidth),
        )
        Spacer(Modifier.height(4.dp))
        val note = if (LocalSystemKind.current.phone) Res.string.tools_find_note_phone else Res.string.tools_find_note
        Txt(stringResource(note), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(12.dp))
        if (ringing == FindState.Ringing) {
            PrimaryButton(stringResource(Res.string.tools_stop), find::stop)
            Spacer(Modifier.height(8.dp))
            Txt(stringResource(Res.string.tools_find_ringing), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        } else {
            PrimaryButton(stringResource(Res.string.tools_find_start), find::start)
        }
        (ringing as? FindState.Problem)?.let {
            Spacer(Modifier.height(8.dp))
            Txt(it.words.text(), Ruru.type.small, c.ink2, Modifier.widthIn(max = HintMaxWidth))
        }
    }
}

@Composable
private fun MicCheckPanel(mic: MicCheckController) {
    val c = Ruru.colors
    val phase = mic.state.collectAsState().value
    val kind = LocalSystemKind.current
    DisposableEffect(mic) { onDispose { mic.stop() } }
    Panel(stringResource(Res.string.tools_mic_title), Modifier.widthIn(max = 620.dp)) {
        Txt(stringResource(Res.string.tools_mic_text, MicCheckController.SECONDS), Ruru.type.body, c.ink2, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(4.dp))
        val note = when {
            kind == SystemKind.Windows -> Res.string.tools_mic_note_windows
            kind.phone -> Res.string.tools_mic_note_phone
            else -> Res.string.tools_mic_note_other
        }
        Txt(stringResource(note), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(12.dp))
        val running = phase is MicCheckState.Recording || phase == MicCheckState.Playing
        if (running) PrimaryButton(stringResource(Res.string.tools_stop), mic::stop)
        else PrimaryButton(stringResource(Res.string.tools_mic_start), mic::start)
        val line = when (phase) {
            is MicCheckState.Recording -> stringResource(Res.string.tools_mic_recording, phase.secondsLeft)
            MicCheckState.Playing -> stringResource(Res.string.tools_mic_playing)
            MicCheckState.Finished -> stringResource(Res.string.tools_mic_finished)
            MicCheckState.NeedsPermission -> stringResource(Res.string.tools_mic_permission)
            is MicCheckState.Problem -> phase.words.text()
            MicCheckState.Idle -> null
        }
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            Txt(line, Ruru.type.small, if (phase is MicCheckState.Problem) c.ink2 else c.ink3, Modifier.widthIn(max = HintMaxWidth))
        }
    }
}
