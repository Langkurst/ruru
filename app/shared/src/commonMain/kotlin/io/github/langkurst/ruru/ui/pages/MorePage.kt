package io.github.langkurst.ruru.ui.pages

import androidx.compose.runtime.Composable
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.Updates
import io.github.langkurst.ruru.ui.AboutPanel
import io.github.langkurst.ruru.ui.Footer
import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.SettingsPanel
import io.github.langkurst.ruru.ui.ShortcutPanel
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * More, the last tab of the bottom bar: the shortcut button and the headphones' settings, the headphones'
 * details, then the switches for this system, the version, updates, the connection report and support.
 */
@Composable
internal fun MorePage(
    model: AppModel,
    state: AppState,
    controller: HeadsetController,
    appVersion: String,
    extras: List<PlatformSwitch>,
    updates: Updates,
    onCopyReport: () -> Unit,
) {
    PageFrame(stringResource(Res.string.page_more), stringResource(Res.string.page_more_lead), state, controller) {
        if (state.phase == Phase.Connected) {
            ShortcutPanel(state.headset, controller)
            SettingsPanel(state.headset, controller)
            if (hasDevices(state.headset)) DevicesPanel(state.headset, controller)
            AboutPanel(state.headset)
        }
        if (hasTools(LocalAudioTools.current, state.phase == Phase.Connected)) ToolPanels(state)
        Footer(state, model, appVersion, extras, updates, onCopyReport)
    }
}
