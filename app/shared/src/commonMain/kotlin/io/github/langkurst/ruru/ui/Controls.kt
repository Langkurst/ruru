package io.github.langkurst.ruru.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(11.dp)
private val RowShape = RoundedCornerShape(7.dp)

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (align == null) style.copy(color = color) else style.copy(color = color, textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A visible ring while the control has keyboard focus, in [colour] (ink-2 unless given). With
 * [outside] it is drawn 3 dp outside a pill-shaped control, for controls whose own fill is ink,
 * where a ring on top would vanish (a switch that is on).
 */
fun Modifier.focusRing(
    interaction: MutableInteractionSource,
    shape: Shape,
    colour: Color? = null,
    outside: Boolean = false,
): Modifier = composed {
    val focused by interaction.collectIsFocusedAsState()
    val ring = colour ?: Ruru.colors.ink2
    when {
        !focused -> this
        outside -> drawBehind {
            val width = 2.dp.toPx()
            val inset = 3.dp.toPx() + width / 2
            drawRoundRect(
                ring,
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + 2 * inset, size.height + 2 * inset),
                cornerRadius = CornerRadius(size.height / 2 + inset),
                style = Stroke(width),
            )
        }
        else -> border(2.dp, ring, shape)
    }
}

@Composable
fun Panel(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Ruru.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(c.bough)
            .border(1.dp, c.line, PanelShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (title != null) {
            Txt(title, Ruru.type.section, c.ink2, Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
fun SettingRow(label: String, hint: String? = null, enabled: Boolean = true, trailing: @Composable () -> Unit) {
    val c = Ruru.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(label, Ruru.type.body, if (enabled) c.ink else c.ink3)
            if (hint != null) Txt(hint, Ruru.type.small, c.ink3)
        }
        trailing()
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val c = Ruru.colors
    val interaction = remember { MutableInteractionSource() }
    val offset by animateDpAsState(if (checked) 16.dp else 0.dp, moveSpec())
    val track by animateColorAsState(if (checked) c.ink else c.ink4, hoverSpec())
    Box(
        Modifier
            .size(width = 36.dp, height = 20.dp)
            .focusRing(interaction, CircleShape, outside = true)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = track.alpha * 0.5f))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, interactionSource = interaction, indication = null, onValueChange = onChange)
            .semantics { contentDescription = label }
            .padding(2.dp),
    ) {
        Box(Modifier.offset(x = offset).size(16.dp).clip(CircleShape).background(if (checked) c.night else c.bough))
    }
}

/**
 * A level from [range]. Dragging shows the value through [onPreview] and sends it once, on
 * release, through [onCommit]; arrow keys step it. Ranges that cross zero fill from zero.
 */
@Composable
fun LevelSlider(
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPreview: (Int) -> Unit = {},
) {
    val c = Ruru.colors
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    val steps = (range.last - range.first).coerceAtLeast(1)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val thumbRadius = with(LocalDensity.current) { 8.dp.toPx() }
    val active = if (enabled) c.ink else c.ink4
    val inactive = c.ink4.copy(alpha = c.ink4.alpha * 0.6f)
    val ring = c.bough
    val focusColour = c.ink2
    val tick = c.ink3
    fun step(delta: Int) = onCommit((value + delta).coerceIn(range))
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = label
                stateDescription = shown.toString() // read out as the number shown, not a percentage
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.first.toFloat()..range.last.toFloat(), steps - 1)
                if (enabled) setProgress { target -> onCommit(target.roundToInt().coerceIn(range)); true } else disabled()
            }
            .focusable(enabled, interaction)
            .onKeyEvent { e ->
                if (!enabled || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionDown -> { step(-1); true }
                    Key.DirectionRight, Key.DirectionUp -> { step(1); true }
                    else -> false
                }
            },
    ) {
        val width = constraints.maxWidth.toFloat()
        fun valueAt(x: Float): Int {
            val f = ((x - thumbRadius) / (width - 2 * thumbRadius)).coerceIn(0f, 1f)
            return range.first + (f * steps).roundToInt()
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, range, width) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = valueAt(o.x).also(onPreview) },
                        onDragEnd = { dragging?.let(onCommit); dragging = null },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val v = valueAt(change.position.x)
                            if (v != dragging) { dragging = v; onPreview(v) }
                        },
                    )
                }
                .pointerInput(enabled, range, width) {
                    if (enabled) detectTapGestures { o -> onCommit(valueAt(o.x)) }
                },
        ) {
            val y = size.height / 2f
            val start = thumbRadius
            val end = size.width - thumbRadius
            val track = 4.dp.toPx()
            val x = start + (end - start) * (shown - range.first) / steps
            val bipolar = range.first < 0 && range.last > 0
            val zero = if (bipolar) start + (end - start) * (0 - range.first) / steps else start
            drawRoundRect(inactive, Offset(start, y - track / 2), Size(end - start, track), CornerRadius(track / 2))
            drawRoundRect(active, Offset(min(zero, x), y - track / 2), Size(abs(x - zero), track), CornerRadius(track / 2))
            if (bipolar) drawRect(tick, Offset(zero - 0.5.dp.toPx(), y - 5.dp.toPx()), Size(1.dp.toPx(), 10.dp.toPx()))
            drawCircle(ring, thumbRadius + 2.dp.toPx(), Offset(x, y))
            drawCircle(active, thumbRadius, Offset(x, y))
            if (focused) drawCircle(focusColour, thumbRadius + 4.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Two to five choices side by side; the selected one sits on a raised block that slides. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, label: String, enabled: Boolean = true) {
    val c = Ruru.colors
    val index = options.indexOfFirst { it.first == selected }
    val outer = RoundedCornerShape(7.dp)
    val inner = RoundedCornerShape(5.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(outer)
            .background(c.night)
            .border(1.dp, c.lineSoft, outer)
            .padding(2.dp)
            .selectableGroup()
            .semantics { contentDescription = label },
    ) {
        val each = maxWidth / options.size
        val x by animateDpAsState(each * index.coerceAtLeast(0), moveSpec())
        if (index >= 0) {
            Box(Modifier.offset(x = x).width(each).fillMaxHeight().clip(inner).background(c.bark).border(1.dp, c.line, inner))
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (value, text) ->
                val isSelected = i == index
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRing(interaction, inner)
                        .selectable(isSelected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(text, if (isSelected) Ruru.type.bodyMedium else Ruru.type.body, if (!enabled) c.ink4 else if (isSelected) c.ink else c.ink2, maxLines = 1)
                }
            }
        }
    }
}

/** A setting with several choices: shows the current one, opens a list in place. */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val c = Ruru.colors
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, moveSpec())
    val interaction = remember { MutableInteractionSource() }
    val current = options.firstOrNull { it.first == selected }?.second ?: "Not set"
    Column(Modifier.fillMaxWidth().animateContentSize(moveSpec())) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .focusRing(interaction, RowShape)
                .clip(RowShape)
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null) { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Txt(label, Ruru.type.body, if (enabled) c.ink else c.ink3)
                if (hint != null) Txt(hint, Ruru.type.small, c.ink3)
            }
            Txt(current, Ruru.type.body, c.ink2, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            val chevron = c.ink3
            Canvas(Modifier.size(12.dp).rotate(turn)) {
                val p = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.38f)
                    lineTo(size.width * 0.5f, size.height * 0.66f)
                    lineTo(size.width * 0.8f, size.height * 0.38f)
                }
                drawPath(p, chevron, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).selectableGroup()) {
                options.forEach { (value, text) ->
                    val isSelected = value == selected
                    val rowInteraction = remember { MutableInteractionSource() }
                    val hovered by rowInteraction.collectIsHoveredAsState()
                    val tint by animateColorAsState(if (isSelected || hovered) c.bark else Color.Transparent, hoverSpec())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .focusRing(rowInteraction, RowShape)
                            .clip(RowShape)
                            .background(tint)
                            .selectable(isSelected, enabled = enabled, role = Role.RadioButton, interactionSource = rowInteraction, indication = null) {
                                onSelect(value)
                                open = false
                            }
                            .hoverable(rowInteraction)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(text, if (isSelected) Ruru.type.bodyMedium else Ruru.type.body, if (isSelected) c.ink else c.ink2, Modifier.weight(1f), maxLines = 1)
                        if (isSelected) {
                            val tickColour = c.ink
                            Canvas(Modifier.size(12.dp)) {
                                val p = Path().apply {
                                    moveTo(size.width * 0.15f, size.height * 0.52f)
                                    lineTo(size.width * 0.42f, size.height * 0.78f)
                                    lineTo(size.width * 0.88f, size.height * 0.25f)
                                }
                                drawPath(p, tickColour, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val c = Ruru.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered && enabled) c.bark else Color.Transparent, hoverSpec())
    Box(
        modifier
            .heightIn(min = 32.dp)
            .focusRing(interaction, RowShape)
            .clip(RowShape)
            .background(bg)
            .border(1.dp, if (danger) c.alarm.copy(alpha = 0.6f) else c.line, RowShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Ruru.type.bodyMedium, if (!enabled) c.ink3 else if (danger) c.alarm else c.ink, maxLines = 1)
    }
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
