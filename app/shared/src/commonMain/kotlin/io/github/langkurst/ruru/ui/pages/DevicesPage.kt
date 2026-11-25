package io.github.langkurst.ruru.ui.pages

import androidx.compose.runtime.Composable
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** Devices (paired devices and switching): not built yet. The feature replaces the body and adds the page to [PageAvailability.ShippedPages]. */
@Composable
internal fun DevicesPage(state: AppState, controller: HeadsetController) {
    PlaceholderPage(stringResource(Res.string.page_devices), stringResource(Res.string.page_devices_lead), state, controller)
}
