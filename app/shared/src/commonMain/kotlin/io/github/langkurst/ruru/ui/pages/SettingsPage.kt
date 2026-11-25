package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.ui.ExtraSwitches
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.SettingsPanel
import io.github.langkurst.ruru.ui.ShortcutPanel
import io.github.langkurst.ruru.ui.hasHeadphoneSettings
import io.github.langkurst.ruru.ui.hasShortcutChoices
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** Settings: what the headphones do (their settings and the shortcut button), then how Ruru runs on this computer. */
@Composable
internal fun SettingsPage(state: AppState, controller: HeadsetController, extras: List<PlatformSwitch>) {
    PageFrame(stringResource(Res.string.panel_settings), stringResource(Res.string.page_settings_lead), state, controller) {
        val h = state.headset
        val connected = state.phase == Phase.Connected
        val left = connected && hasHeadphoneSettings(h)
        val right = (connected && hasShortcutChoices(h)) || extras.isNotEmpty()
        // Two columns that keep their own heights: the left is one tall panel, the right stacks two short ones.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (left) SettingsPanel(h, controller, title = stringResource(Res.string.panel_headphones))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (right && connected) ShortcutPanel(h, controller)
                if (extras.isNotEmpty()) Panel(stringResource(Res.string.panel_computer)) { ExtraSwitches(extras) }
            }
        }
    }
}
