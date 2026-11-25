package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.ui.LocalDesk
import io.github.langkurst.ruru.ui.MessageBar
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.text
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * A page: its title, one plain sentence, then the content. On a computer it is a column that scrolls
 * and fills the window; on a phone it is the stack of panels inside the tab's own scrolling column.
 */
@Composable
internal fun PageFrame(title: String, lead: String, state: AppState, controller: HeadsetController, content: @Composable ColumnScope.() -> Unit) {
    val c = Ruru.colors
    if (LocalDesk.current) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
            Column(Modifier.fillMaxWidth()) {
                state.message?.let {
                    MessageBar(it.text(), controller::dismissMessage)
                    Spacer(Modifier.height(16.dp))
                }
                Txt(title, Ruru.type.page, c.ink, Modifier.semantics { heading() })
                Txt(lead, Ruru.type.body, c.ink3, Modifier.padding(top = 4.dp, bottom = 22.dp))
                content()
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.message?.let { MessageBar(it.text(), controller::dismissMessage) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Txt(title, Ruru.type.page, c.ink, Modifier.semantics { heading() })
                Txt(lead, Ruru.type.body, c.ink3, Modifier.padding(top = 4.dp))
            }
            content()
        }
    }
}

/**
 * The body of a page whose feature is not built yet: its title, one sentence of what will be there, and
 * a plain note. Such a page is not in the navigation (see [PageAvailability]); this is only what a
 * feature replaces when it gets its content.
 */
@Composable
internal fun PlaceholderPage(title: String, lead: String, state: AppState, controller: HeadsetController) {
    PageFrame(title, lead, state, controller) {
        Panel(null, Modifier.widthIn(max = 460.dp)) {
            Txt(stringResource(Res.string.page_placeholder_note), Ruru.type.body, Ruru.colors.ink3)
        }
    }
}

/** What a page is called in the sidebar, the bottom bar and its own title. */
@Composable
internal fun pageTitle(page: DeskPage): String = stringResource(
    when (page) {
        DeskPage.Sound -> Res.string.page_sound
        DeskPage.Equaliser -> Res.string.panel_equaliser
        DeskPage.Night -> Res.string.page_night
        DeskPage.Devices -> Res.string.page_devices
        DeskPage.Settings -> Res.string.panel_settings
        DeskPage.Tools -> Res.string.page_tools
        DeskPage.Sleep -> Res.string.page_sleep
        DeskPage.More -> Res.string.page_more
        DeskPage.About -> Res.string.page_about_title
        DeskPage.Status -> Res.string.page_sound
    },
)

internal fun pageIcon(page: DeskPage): NavIcon = when (page) {
    DeskPage.Sound -> NavIcon.Headphones
    DeskPage.Equaliser -> NavIcon.Sliders
    DeskPage.Night, DeskPage.Sleep -> NavIcon.Moon
    DeskPage.Devices -> NavIcon.Devices
    DeskPage.Tools -> NavIcon.Tools
    DeskPage.More -> NavIcon.More
    DeskPage.Settings, DeskPage.About, DeskPage.Status -> NavIcon.Gear
}

/**
 * Panels side by side in equal columns, all as tall as the tallest, with a 16dp gap. A panel on its
 * own keeps the width of one column rather than stretching across the page.
 */
@Composable
internal fun Columns(items: Int, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        content()
        if (items < 2) Spacer(Modifier.weight(1f))
    }
}

/** One column of [Columns]: half the width, as tall as the row. */
internal fun RowScope.column(): Modifier = Modifier.weight(1f).fillMaxHeight()
