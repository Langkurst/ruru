package io.github.langkurst.ruru.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.PhoneTab
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.focusRing
import io.github.langkurst.ruru.ui.hoverSpec
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/** The height of the bottom bar, as in docs/DESIGN.md. */
internal val BottomBarHeight = 56.dp

/**
 * The bottom bar of a phone or a narrow window: up to four tabs, each an icon over its name, in ink when
 * chosen and ink-3 otherwise. No accent colour: the accent is for the active listening mode. A 1px line
 * separates it from the page. The system's gesture bar is left to the caller, which pads the whole
 * screen by the safe area, so the bar sits above it on the same night colour.
 */
@Composable
internal fun BottomBar(tabs: List<PhoneTab>, selected: PhoneTab, onSelect: (PhoneTab) -> Unit) {
    val c = Ruru.colors
    val label = stringResource(Res.string.nav_tabs)
    Column(Modifier.fillMaxWidth().background(c.night)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().height(BottomBarHeight - 1.dp).selectableGroup().semantics { contentDescription = label },
            horizontalArrangement = Arrangement.Center,
        ) {
            tabs.forEach { tab -> BarTab(tab, tab == selected, Modifier.weight(1f).widthIn(max = 160.dp)) { onSelect(tab) } }
        }
    }
}

@Composable
private fun BarTab(tab: PhoneTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink by animateColorAsState(if (selected) c.ink else if (hovered) c.ink2 else c.ink3, hoverSpec())
    val name = stringResource(
        when (tab) {
            PhoneTab.Headphones -> Res.string.panel_headphones
            PhoneTab.Sound -> Res.string.page_sound
            PhoneTab.Sleep -> Res.string.page_sleep
            PhoneTab.More -> Res.string.page_more
        },
    )
    val icon = when (tab) {
        PhoneTab.Headphones -> NavIcon.Headphones
        PhoneTab.Sound -> NavIcon.Speaker
        PhoneTab.Sleep -> NavIcon.Moon
        PhoneTab.More -> NavIcon.More
    }
    Column(
        modifier
            .height(BottomBarHeight - 1.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .selectable(selected, role = Role.Tab, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NavIconView(icon, ink, 22.dp)
        Spacer(Modifier.height(3.dp))
        Txt(name, Ruru.type.small, ink, maxLines = 1)
    }
}
