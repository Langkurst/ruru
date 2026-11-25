package io.github.langkurst.ruru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.Updates
import io.github.langkurst.ruru.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * What the Check for updates button is doing, as a small inset card under its row: the small owl,
 * one line saying where it has got to, and the button the person is expected to press.
 * Draws nothing when there is nothing to say.
 */
@Composable
fun UpdateCard(state: UpdateState, updates: Updates, modifier: Modifier = Modifier) {
    if (state == UpdateState.Idle) return
    val c = Ruru.colors
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.night)
            .border(1.dp, if (state is UpdateState.Failed) c.alarm.copy(alpha = 0.5f) else c.line, shape)
            .padding(12.dp)
            // Screen readers announce each change of state as it happens.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        OwlMark(28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Title(stringResource(Res.string.update_checking))
                is UpdateState.Downloading -> {
                    val percent = (state.fraction * 100).toInt().coerceIn(0, 100)
                    Title(stringResource(Res.string.update_downloading, state.version, percent))
                    ProgressBar(state.fraction)
                }
                is UpdateState.Ready -> {
                    Title(stringResource(Res.string.update_ready_title, state.version))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(c.calm))
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(Res.string.update_ready_detail), Ruru.type.small, c.ink2)
                    }
                    Txt(stringResource(Res.string.update_ready_note), Ruru.type.small, c.ink3)
                    Spacer(Modifier.height(2.dp))
                    ButtonRow {
                        PrimaryButton(stringResource(Res.string.update_install), updates::installAndRestart)
                        QuietButton(stringResource(Res.string.update_later), updates::dismiss)
                    }
                }
                is UpdateState.Installing -> Title(stringResource(Res.string.update_installing, state.version))
                UpdateState.UpToDate -> {
                    Title(stringResource(Res.string.update_up_to_date))
                    ButtonRow { QuietButton(stringResource(Res.string.action_ok), updates::dismiss) }
                }
                UpdateState.OpenedWebsite -> {
                    Title(stringResource(Res.string.update_opened_website))
                    ButtonRow { QuietButton(stringResource(Res.string.action_ok), updates::dismiss) }
                }
                is UpdateState.Failed -> {
                    Txt(state.reason.text(), Ruru.type.body, c.ink)
                    ButtonRow {
                        QuietButton(stringResource(Res.string.action_try_again), updates::check)
                        QuietButton(stringResource(Res.string.action_ok), updates::dismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Txt(text, Ruru.type.bodyMedium, Ruru.colors.ink)

/** A 4 dp track in ink: the same bar the battery uses, a little wider. */
@Composable
private fun ProgressBar(fraction: Float) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(shape).background(c.ink4.copy(alpha = 0.4f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(shape).background(c.ink))
    }
}
