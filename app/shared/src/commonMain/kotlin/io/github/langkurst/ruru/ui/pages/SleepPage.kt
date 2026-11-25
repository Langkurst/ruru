package io.github.langkurst.ruru.ui.pages

import androidx.compose.runtime.Composable
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** Sleep (the phone tab: timer, sounds and alarms): not built yet. The feature replaces the body and adds the page to [PageAvailability.ShippedPages]. */
@Composable
internal fun SleepPage(state: AppState, controller: HeadsetController) {
    PlaceholderPage(stringResource(Res.string.page_sleep), stringResource(Res.string.page_sleep_lead), state, controller)
}
