package io.github.langkurst.ruru.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.PlaybackState
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.ui.res.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- what the headphones report

/** The time left on the battery the headphones report, or null (always on the QC Ultra 1st Gen). */
internal fun minutesLeft(h: HeadphoneState): Int? {
    val b = h.battery ?: return null
    return (b.overall?.minutesLeft ?: listOfNotNull(b.left?.minutesLeft, b.right?.minutesLeft).minOrNull())?.takeIf { it > 0 }
}

/** "about 14 h left", or "about 40 min left" under an hour. */
@Composable
internal fun hoursLeftText(minutes: Int): String =
    if (minutes < 60) stringResource(Res.string.battery_left_minutes, minutes) else stringResource(Res.string.battery_left_hours, (minutes + 30) / 60)

/** The mic chip shows only when the headphones say the mic is muted. */
internal fun showMicChip(h: HeadphoneState) = h.micMuted == true

/** The volume is shown when the headphones report both the level and the top step. */
internal fun hasVolume(h: HeadphoneState) = h.volume != null && (h.volumeMax ?: 0) > 0

/** The buttons the strip draws: only those the headphones list. Play and pause share one button. */
internal data class TransportButtons(val previous: Boolean, val playPause: Boolean, val next: Boolean) {
    val any get() = previous || playPause || next
}

internal fun transportButtons(h: HeadphoneState): TransportButtons {
    val s = h.supportedTransportControls
    return TransportButtons(
        previous = TransportControl.PREVIOUS in s,
        playPause = TransportControl.PLAY in s || TransportControl.PAUSE in s,
        next = TransportControl.NEXT in s,
    )
}

/** What the play/pause button sends now: pause while playing, otherwise play (each only if listed). */
internal fun playPauseTarget(h: HeadphoneState): TransportControl? {
    val s = h.supportedTransportControls
    val playing = h.playback?.state == PlaybackState.PLAYING
    return when {
        playing && TransportControl.PAUSE in s -> TransportControl.PAUSE
        !playing && TransportControl.PLAY in s -> TransportControl.PLAY
        else -> null
    }
}

/** Whether the now playing strip has anything to show. */
internal fun hasNowPlaying(h: HeadphoneState) = transportButtons(h).any || hasVolume(h)

// ---------------------------------------------------------------- mic chip

/** A small chip beside the connected status while the mic is muted. */
@Composable
internal fun MicChip(modifier: Modifier = Modifier) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.clip(shape).background(c.bark).border(1.dp, c.line, shape).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Txt(stringResource(Res.string.mic_muted_chip), Ruru.type.small, c.ink2, maxLines = 1)
    }
}

// ---------------------------------------------------------------- favourite star

/** The star on a mode row: filled when the mode is a favourite. In ink, not the accent; [onAccent] is for a row on the yellow marker. */
@Composable
internal fun FavouriteStar(mode: String, favourite: Boolean, onAccent: Boolean, onChange: (Boolean) -> Unit) {
    val c = Ruru.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (onAccent) c.onEye else c.ink
    val tone by animateColorAsState(if (favourite || hovered) base else base.copy(alpha = 0.5f), hoverSpec())
    val label = stringResource(if (favourite) Res.string.modes_favourite_remove else Res.string.modes_favourite_add, mode)
    Box(
        Modifier
            .fillMaxHeight()
            .width(36.dp)
            .focusRing(interaction, RoundedCornerShape(7.dp), if (onAccent) c.onEye else null)
            .toggleable(favourite, role = Role.Checkbox, interactionSource = interaction, indication = null, onValueChange = onChange)
            .hoverable(interaction)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) { drawStar(tone, filled = favourite) }
    }
}

private fun DrawScope.drawStar(colour: Color, filled: Boolean) {
    val r = size.width / 2f
    val centre = Offset(size.width / 2f, size.height / 2f + r * 0.06f)
    val path = Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) r else r * 0.42f
        val angle = -PI / 2 + i * PI / 5
        val x = centre.x + radius * cos(angle).toFloat()
        val y = centre.y + radius * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, colour, style = if (filled) Fill else Stroke(1.4.dp.toPx(), join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- now playing and volume

/**
 * The strip at the top of the Sound page and the Headphones tab: previous, play or pause and next
 * (only those the headphones list), what the headphones say they are doing, and the volume.
 * Nothing is drawn for what they do not report. No track title: the headphones send none.
 */
@Composable
internal fun NowPlayingPanel(h: HeadphoneState, controller: HeadsetController, modifier: Modifier = Modifier) {
    if (!hasNowPlaying(h)) return
    val buttons = transportButtons(h)
    Panel(null, modifier) {
        if (LocalDesk.current && buttons.any && hasVolume(h)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TransportRow(h, buttons, controller)
                Spacer(Modifier.width(28.dp))
                VolumeRow(h, controller, Modifier.weight(1f).widthIn(max = 460.dp))
            }
        } else {
            if (buttons.any) TransportRow(h, buttons, controller)
            if (buttons.any && hasVolume(h)) Spacer(Modifier.height(8.dp))
            if (hasVolume(h)) VolumeRow(h, controller, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TransportRow(h: HeadphoneState, buttons: TransportButtons, controller: HeadsetController) {
    val c = Ruru.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (buttons.previous) TransportButton(TransportGlyph.Previous, stringResource(Res.string.transport_previous), true) { controller.transport(TransportControl.PREVIOUS) }
        if (buttons.playPause) {
            val target = playPauseTarget(h)
            val pausing = target != TransportControl.PLAY
            TransportButton(
                if (pausing) TransportGlyph.Pause else TransportGlyph.Play,
                stringResource(if (pausing) Res.string.transport_pause else Res.string.transport_play),
                target != null,
            ) { target?.let(controller::transport) }
        }
        if (buttons.next) TransportButton(TransportGlyph.Next, stringResource(Res.string.transport_next), true) { controller.transport(TransportControl.NEXT) }
        h.playback?.state?.let {
            Spacer(Modifier.width(6.dp))
            Txt(stringResource(it.words()), Ruru.type.body, c.ink2, maxLines = 1)
        }
    }
}

private fun PlaybackState.words() = when (this) {
    PlaybackState.PLAYING -> Res.string.playback_playing
    PlaybackState.PAUSED -> Res.string.playback_paused
    PlaybackState.STOPPED -> Res.string.playback_stopped
    PlaybackState.FAST_FORWARDING -> Res.string.playback_forwarding
    PlaybackState.REWINDING -> Res.string.playback_rewinding
}

@Composable
private fun VolumeRow(h: HeadphoneState, controller: HeadsetController, modifier: Modifier) {
    val c = Ruru.colors
    val max = h.volumeMax ?: return
    val reported = h.volume ?: return
    // What the person just chose is shown at once; the headphones' own figure takes over a moment later.
    var chosen by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(chosen) {
        if (chosen != null) {
            delay(1_500)
            chosen = null
        }
    }
    val shown = (chosen ?: reported).coerceIn(0, max)
    val label = stringResource(Res.string.volume_label)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Ruru.type.body, c.ink, Modifier.padding(end = 12.dp), maxLines = 1)
        Box(Modifier.weight(1f)) {
            LevelSlider(
                value = shown,
                range = 0..max,
                onPreview = { chosen = it },
                onCommit = {
                    chosen = it
                    controller.setVolume(it)
                },
                label = label,
            )
        }
        // A fixed-width slot, end-aligned, so the slider does not move as the figure changes.
        Txt(stringResource(Res.string.volume_of, shown, max), Ruru.type.figureMedium, c.ink, Modifier.width(72.dp).padding(start = 12.dp), maxLines = 1, align = TextAlign.End)
    }
}

internal enum class TransportGlyph { Previous, Play, Pause, Next }

@Composable
private fun TransportButton(glyph: TransportGlyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(if ((hovered || pressed) && enabled) c.bark else Color.Transparent, hoverSpec())
    Box(
        Modifier
            .size(width = 44.dp, height = 36.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) { drawGlyph(glyph, if (enabled) c.ink else c.ink4) }
    }
}

private fun DrawScope.drawGlyph(glyph: TransportGlyph, colour: Color) {
    val s = size.width / 16f
    val round = Stroke(1.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // A triangle with its flat side at x0, pointing to x1; filled and outlined so its corners are soft.
    fun triangle(x0: Float, x1: Float) {
        val p = Path().apply {
            moveTo(x0 * s, 2.5f * s)
            lineTo(x1 * s, 8f * s)
            lineTo(x0 * s, 13.5f * s)
            close()
        }
        drawPath(p, colour)
        drawPath(p, colour, style = round)
    }
    fun bar(x: Float, w: Float) = drawRoundRect(colour, Offset(x * s, 3f * s), Size(w * s, 10f * s), CornerRadius(w * s / 3f))
    when (glyph) {
        TransportGlyph.Play -> triangle(4f, 13f)
        TransportGlyph.Pause -> {
            bar(3.5f, 3f)
            bar(9.5f, 3f)
        }
        TransportGlyph.Next -> {
            triangle(2.5f, 11f)
            bar(12f, 1.8f)
        }
        TransportGlyph.Previous -> {
            triangle(13.5f, 5f)
            bar(2.2f, 1.8f)
        }
    }
}
