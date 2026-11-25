package io.github.langkurst.ruru.ui.pages

import androidx.compose.runtime.Composable
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** Tools (Find my headphones, mic check, the read-only Lab): not built yet. The feature replaces the body and adds the page to [PageAvailability.ShippedPages]. */
@Composable
internal fun ToolsPage(state: AppState, controller: HeadsetController) {
    PlaceholderPage(stringResource(Res.string.page_tools), stringResource(Res.string.page_tools_lead), state, controller)
}
