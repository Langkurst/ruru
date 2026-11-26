package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.ui.HintMaxWidth
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.QuietButton
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** One paired device as the Devices page shows it. */
internal data class DeviceRow(
    val address: String,
    val name: String?,
    /** The sound is going to this device now. */
    val playing: Boolean,
    /** Null when the headphones don't say. */
    val connected: Boolean?,
    val thisDevice: Boolean,
    /** The page offers "Switch to this": only for a device that is connected and is not already playing. */
    val canSwitch: Boolean,
)

/** What the headphones report as paired. The page and its panel are shown only when this has something. */
internal fun deviceRows(h: HeadphoneState): List<DeviceRow> = h.pairedDevices.map {
    DeviceRow(
        address = it.address,
        name = it.name?.takeIf { n -> n.isNotBlank() },
        playing = it.isActiveSource,
        connected = it.isConnected,
        thisDevice = it.isThisPhone,
        canSwitch = !it.isActiveSource && it.isConnected != false,
    )
}

internal fun hasDevices(h: HeadphoneState) = h.pairedDevices.isNotEmpty()

/**
 * Devices: what the headphones are paired with, and a button to move the sound to another connected
 * device. Nothing here removes or forgets a device: Ruru has no such command.
 */
@Composable
internal fun DevicesPage(state: AppState, controller: HeadsetController) {
    PageFrame(stringResource(Res.string.page_devices), stringResource(Res.string.page_devices_lead), state, controller) {
        if (state.phase == Phase.Connected && hasDevices(state.headset)) DevicesPanel(state.headset, controller, Modifier.widthIn(max = 620.dp))
    }
}

@Composable
internal fun DevicesPanel(h: HeadphoneState, controller: HeadsetController, modifier: Modifier = Modifier) {
    val rows = deviceRows(h)
    if (rows.isEmpty()) return
    val c = Ruru.colors
    Panel(stringResource(Res.string.devices_panel_title), modifier) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Txt(row.name ?: stringResource(Res.string.devices_unnamed), Ruru.type.body, c.ink, maxLines = 1)
                    Txt(statusText(row), Ruru.type.small, c.ink3)
                }
                if (row.canSwitch) {
                    val label = stringResource(Res.string.devices_switch_to, row.name ?: stringResource(Res.string.devices_unnamed))
                    QuietButton(stringResource(Res.string.devices_switch), { controller.switchDevice(row.address) }, Modifier.semantics { contentDescription = label })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(Res.string.devices_switch_hint), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(6.dp))
        Txt(stringResource(Res.string.devices_manage_note), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
    }
}

@Composable
private fun statusText(row: DeviceRow): String {
    val base = stringResource(
        when {
            row.playing -> Res.string.devices_state_playing
            row.connected == true -> Res.string.devices_state_connected
            row.connected == false -> Res.string.devices_state_not_connected
            else -> Res.string.devices_state_paired
        },
    )
    return if (row.thisDevice) stringResource(Res.string.devices_state_this_device, base) else base
}
