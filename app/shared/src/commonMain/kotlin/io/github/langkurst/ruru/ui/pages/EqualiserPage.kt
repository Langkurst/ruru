package io.github.langkurst.ruru.ui.pages

import androidx.compose.runtime.Composable
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** Equaliser (the Pro EQ, on Windows, Linux and Android): not built yet. The feature replaces the body and adds the page to [PageAvailability.ShippedPages]. */
@Composable
internal fun EqualiserPage(state: AppState, controller: HeadsetController) {
    PlaceholderPage(stringResource(Res.string.panel_equaliser), stringResource(Res.string.page_equaliser_lead), state, controller)
}
