package io.github.langkurst.ruru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.ISSUES_URL
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.SupportTier
import io.github.langkurst.ruru.ui.pages.LocalSystemKind
import io.github.langkurst.ruru.ui.pages.SystemKind
import io.github.langkurst.ruru.ui.res.*
import io.github.langkurst.ruru.vendors.NotPossibleReason
import io.github.langkurst.ruru.vendors.SupportCell
import io.github.langkurst.ruru.vendors.SupportLevel
import io.github.langkurst.ruru.vendors.SupportMatrix
import io.github.langkurst.ruru.vendors.SupportSystem
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Support labels (0.3): a chip by the device name, one plain sentence behind it, and the maker x system table in About.

/** The tier of the connected headphones on the system the app runs on; null when there is nothing to label. */
@Composable
internal fun supportTier(state: AppState): SupportTier? {
    if (state.phase != Phase.Connected) return null
    return supportTier(state.headset, LocalSystemKind.current)
}

internal fun supportTier(h: HeadphoneState, system: SystemKind): SupportTier? =
    SupportTier.of(h, system.platformName)?.takeIf { it != SupportTier.ANY_HEADSET }

private fun chipWord(tier: SupportTier): StringResource = when (tier) {
    SupportTier.TESTED -> Res.string.support_chip_tested
    SupportTier.EXPERIMENTAL, SupportTier.ANY_HEADSET -> Res.string.support_chip_experimental
    SupportTier.BATTERY_ONLY -> Res.string.support_chip_battery
}

private fun explainSentence(tier: SupportTier): StringResource = when (tier) {
    SupportTier.TESTED -> Res.string.support_explain_tested
    SupportTier.EXPERIMENTAL, SupportTier.ANY_HEADSET -> Res.string.support_explain_experimental
    SupportTier.BATTERY_ONLY -> Res.string.support_explain_battery
}

/** The small chip by the name: "Tested", "Experimental" or "Battery only". Pressing it asks [onClick] to show the note. */
@Composable
internal fun SupportChip(tier: SupportTier, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val word = stringResource(chipWord(tier))
    Box(
        modifier
            .focusRing(interaction, shape)
            .clip(shape)
            .background(if (open || hovered) c.bark else c.bark.copy(alpha = 0.6f))
            .border(1.dp, c.line, shape)
            .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .semantics { contentDescription = word },
    ) {
        Txt(word, Ruru.type.small, if (tier == SupportTier.TESTED) c.ink else c.ink2, maxLines = 1)
    }
}

/**
 * What the chip means, in one plain sentence. For Experimental headphones a link-style button copies
 * the connection report and opens GitHub's new-issue page in the browser: the person goes online
 * themselves and pastes it; Ruru sends nothing.
 */
@Composable
internal fun SupportNote(tier: SupportTier, onCopyReport: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val browser = LocalUriHandler.current
    var sent by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(c.bough).border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Txt(stringResource(explainSentence(tier)), Ruru.type.small, c.ink2)
        if (tier == SupportTier.EXPERIMENTAL) {
            Spacer(Modifier.height(6.dp))
            LinkButton(stringResource(if (sent) Res.string.support_report_copied else Res.string.support_send_report)) {
                onCopyReport()
                runCatching { browser.openUri(ISSUES_URL) }
                sent = true
            }
            if (sent) Txt(stringResource(Res.string.support_report_paste), Ruru.type.small, c.ink3, Modifier.padding(top = 2.dp))
        }
    }
}

/** Small text that is a button: ink, underlined on hover. */
@Composable
internal fun LinkButton(text: String, onClick: () -> Unit) {
    val c = Ruru.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)
    Txt(
        text,
        Ruru.type.small.copy(textDecoration = if (hovered) TextDecoration.Underline else null),
        c.ink,
        Modifier
            .focusRing(interaction, shape)
            .clip(shape)
            .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .padding(vertical = 4.dp),
        maxLines = 1,
    )
}

// ---------------------------------------------------------------- the table in About

private fun levelWord(level: SupportLevel): StringResource = when (level) {
    SupportLevel.TESTED -> Res.string.support_chip_tested
    SupportLevel.EXPERIMENTAL -> Res.string.support_chip_experimental
    SupportLevel.BATTERY_ONLY -> Res.string.support_chip_battery
    SupportLevel.NOT_POSSIBLE -> Res.string.support_level_not_possible
}

private fun reasonSentence(reason: NotPossibleReason): StringResource = when (reason) {
    NotPossibleReason.NO_SERVICE_LINK -> Res.string.support_why_service
    NotPossibleReason.NO_ADVERTS -> Res.string.support_why_adverts
}

/**
 * Which maker works on which system, from [SupportMatrix] (the one data source the README and the site can be
 * generated from too). A computer's window shows the whole table; a phone shows just its own system.
 */
@Composable
internal fun SupportTablePanel(modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val system = SupportSystem.ofPlatform(LocalSystemKind.current.platformName)
    val wide = LocalDesk.current
    Panel(stringResource(Res.string.support_table_title), modifier) {
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Spacer(Modifier.weight(1.5f))
                SupportSystem.entries.forEach { s ->
                    Txt(s.label, Ruru.type.small, if (s == system) c.ink else c.ink3, Modifier.weight(1f), maxLines = 1)
                }
            }
            SupportMatrix.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(row.brand, Ruru.type.body, c.ink, Modifier.weight(1.5f), maxLines = 1)
                    SupportSystem.entries.forEach { s ->
                        val cell = row.cells.getValue(s)
                        Txt(stringResource(levelWord(cell.level)), Ruru.type.small, cellColour(cell, s == system), Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        } else {
            val mine = system ?: SupportSystem.ANDROID
            Txt(mine.label, Ruru.type.small, c.ink3, Modifier.padding(bottom = 6.dp))
            SupportMatrix.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(row.brand, Ruru.type.body, c.ink, Modifier.weight(1f), maxLines = 1)
                    val cell = row.cells.getValue(mine)
                    Txt(stringResource(levelWord(cell.level)), Ruru.type.small, cellColour(cell, true), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(max = HintMaxWidth)) {
            Txt(stringResource(Res.string.support_legend_tested, SupportMatrix.TESTED_MODEL), Ruru.type.small, c.ink3)
            Txt(stringResource(Res.string.support_legend_experimental), Ruru.type.small, c.ink3)
            SupportMatrix.rows.flatMap { it.cells.values }.mapNotNull { it.why }.distinct().forEach {
                Txt(stringResource(reasonSentence(it)), Ruru.type.small, c.ink3)
            }
        }
    }
}

@Composable
private fun cellColour(cell: SupportCell, mine: Boolean): Color = when {
    cell.level == SupportLevel.NOT_POSSIBLE -> Ruru.colors.ink4
    mine -> Ruru.colors.ink
    else -> Ruru.colors.ink2
}
