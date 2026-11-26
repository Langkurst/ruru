package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

/** The sound tools of this system. Tests and snapshots can provide their own. */
val LocalAudioTools = staticCompositionLocalOf { audioTools() }

/**
 * Whether the Tools page has anything to show: Find needs the headphones connected (a tone for headphones
 * Ruru can't reach is no use), and the mic check works with whatever microphone the system has.
 */
internal fun hasTools(tools: AudioTools, connected: Boolean): Boolean = tools.mic != null || (connected && tools.find != null)

/** Tools: Find my headphones and the mic check. */
@Composable
internal fun ToolsPage(state: AppState, controller: HeadsetController) {
    PageFrame(stringResource(Res.string.page_tools), stringResource(Res.string.page_tools_lead), state, controller) {
        ToolPanels(state)
    }
}

/** The tools' panels, one under the other: on the Tools page and, on a phone, in More. */
@Composable
internal fun ToolPanels(state: AppState) {
    val tools = LocalAudioTools.current
    val connected = state.phase == Phase.Connected
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tools.find?.let { if (connected) FindPanel(it) }
        tools.mic?.let { MicCheckPanel(it) }
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
