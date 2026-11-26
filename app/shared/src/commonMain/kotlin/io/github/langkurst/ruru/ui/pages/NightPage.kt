package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.night.ModeChoice
import io.github.langkurst.ruru.app.night.NightLast
import io.github.langkurst.ruru.app.night.NightPreview
import io.github.langkurst.ruru.app.night.NightRunner
import io.github.langkurst.ruru.app.night.formatClockTime
import io.github.langkurst.ruru.app.night.hourOnFace
import io.github.langkurst.ruru.app.night.modeChoices
import io.github.langkurst.ruru.app.night.modeKey
import io.github.langkurst.ruru.app.night.previewOf
import io.github.langkurst.ruru.app.night.stepHour
import io.github.langkurst.ruru.app.night.stepMinute
import io.github.langkurst.ruru.app.night.uses24HourClock
import io.github.langkurst.ruru.app.night.withHalf
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.core.ModeResolver
import io.github.langkurst.ruru.sleep.DayMode
import io.github.langkurst.ruru.sleep.NightNote
import io.github.langkurst.ruru.ui.ChoiceRow
import io.github.langkurst.ruru.ui.ExtraSwitches
import io.github.langkurst.ruru.ui.HintMaxWidth
import io.github.langkurst.ruru.ui.LocalDesk
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.PlatformSwitch
import io.github.langkurst.ruru.ui.QuietButton
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Segmented
import io.github.langkurst.ruru.ui.SettingRow
import io.github.langkurst.ruru.ui.Toggle
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.focusRing
import io.github.langkurst.ruru.ui.res.*
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The runner that carries out the night schedule on this system; null where nothing does (phones for now, and screens drawn without one). */
val LocalNight = staticCompositionLocalOf<NightRunner?> { null }

private const val WHAT_IT_WAS = "what-it-was"
private val DayNames = listOf(
    Res.string.night_day_1, Res.string.night_day_2, Res.string.night_day_3, Res.string.night_day_4,
    Res.string.night_day_5, Res.string.night_day_6, Res.string.night_day_7,
)

/** Night: the schedule that switches the listening mode at bedtime and again in the morning. */
@Composable
internal fun NightPage(state: AppState, controller: HeadsetController, extras: List<PlatformSwitch>) {
    val night = LocalNight.current
    if (night == null) {
        PlaceholderPage(stringResource(Res.string.page_night), stringResource(Res.string.page_night_lead), state, controller)
        return
    }
    PageFrame(stringResource(Res.string.page_night), stringResource(Res.string.page_night_lead), state, controller) {
        NightPanels(night, state, extras)
    }
}

/** The Night page's panels, one under the other: on the Night page and, in a narrow window, in More. */
@Composable
internal fun NightPanels(night: NightRunner, state: AppState, extras: List<PlatformSwitch>) {
    Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SchedulePanel(night, state)
        WhenPanel(night, state, extras)
    }
}

@Composable
private fun SchedulePanel(night: NightRunner, state: AppState) {
    val c = Ruru.colors
    val schedule by night.currentSchedule.collectAsState()
    val on = schedule.on
    val twelve = !uses24HourClock()
    val chosen = listOf(schedule.nightMode) + listOfNotNull((schedule.dayMode as? DayMode.Mode)?.ref)
    val choices = remember(state.headset, schedule.nightMode, schedule.dayMode) { modeChoices(state.headset, chosen) }
    Panel(stringResource(Res.string.night_panel_title)) {
        val onLabel = stringResource(Res.string.night_on)
        SettingRow(onLabel, stringResource(Res.string.night_on_hint)) {
            Toggle(on, { v -> night.update { it.copy(on = v) } }, onLabel)
        }
        Spacer(Modifier.height(4.dp))
        TimeRow(stringResource(Res.string.night_bedtime), schedule.bedtime, twelve, on) { t -> night.update { it.copy(bedtime = t) } }
        TimeRow(stringResource(Res.string.night_wake), schedule.wake, twelve, on) { t -> night.update { it.copy(wake = t) } }
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(Res.string.night_days), Ruru.type.body, if (on) c.ink else c.ink3)
        Spacer(Modifier.height(6.dp))
        DayChips(schedule.days, on) { day ->
            night.update { s -> s.copy(days = if (day in s.days) s.days - day else s.days + day) }
        }
        Spacer(Modifier.height(4.dp))
        Txt(stringResource(Res.string.night_days_hint), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        Spacer(Modifier.height(8.dp))
        val nightOptions = choices.map { it.key to it.labelText() }
        ChoiceRow(
            stringResource(Res.string.night_mode_night), nightOptions, modeKey(schedule.nightMode),
            { key -> choices.firstOrNull { it.key == key }?.let { pick -> night.update { it.copy(nightMode = pick.ref) } } },
            enabled = on,
        )
        val morningOptions = listOf(WHAT_IT_WAS to stringResource(Res.string.night_what_it_was)) + nightOptions
        val morningKey = (schedule.dayMode as? DayMode.Mode)?.ref?.let(::modeKey) ?: WHAT_IT_WAS
        ChoiceRow(
            stringResource(Res.string.night_mode_morning), morningOptions, morningKey,
            { key ->
                val next = if (key == WHAT_IT_WAS) DayMode.WhatItWas else choices.firstOrNull { it.key == key }?.let { DayMode.Mode(it.ref) }
                if (next != null) night.update { it.copy(dayMode = next) }
            },
            enabled = on,
        )
    }
}

@Composable
private fun WhenPanel(night: NightRunner, state: AppState, extras: List<PlatformSwitch>) {
    val c = Ruru.colors
    val schedule by night.currentSchedule.collectAsState()
    val progress by night.currentProgress.collectAsState()
    val last by night.lastTry.collectAsState()
    // The clock is looked at again every half minute, so the next change and its day stay right while the page is open.
    var clock by remember { mutableStateOf(night.nowMillis()) }
    LaunchedEffect(night) {
        while (true) {
            delay(30_000)
            clock = night.nowMillis()
        }
    }
    val choices = remember(state.headset, schedule.nightMode, schedule.dayMode) {
        modeChoices(state.headset, listOf(schedule.nightMode) + listOfNotNull((schedule.dayMode as? DayMode.Mode)?.ref))
    }
    val zone = night.timeZone()
    val preview = remember(schedule, progress, clock) {
        night.nextAction()?.let { previewOf(it, schedule, night.nowMillis(), zone) }
    }
    Panel(stringResource(Res.string.night_when_title)) {
        val next = when {
            !schedule.on -> stringResource(Res.string.night_next_off)
            schedule.bedtime == schedule.wake -> stringResource(Res.string.night_next_same)
            schedule.days.none { it in 1..7 } -> stringResource(Res.string.night_next_no_days)
            preview != null -> previewText(preview, choices)
            else -> null
        }
        if (next != null) Txt(next, Ruru.type.bodyMedium, c.ink, Modifier.widthIn(max = HintMaxWidth))
        last?.let {
            Spacer(Modifier.height(6.dp))
            Txt(lastText(it, zone), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        }
        Spacer(Modifier.height(10.dp))
        val note = if (LocalSystemKind.current.phone) Res.string.night_runs_phone else Res.string.night_runs_only
        Txt(stringResource(note), Ruru.type.small, c.ink3, Modifier.widthIn(max = HintMaxWidth))
        val signIn = extras.filter { it.id == PlatformSwitch.START_AT_SIGN_IN }
        if (signIn.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ExtraSwitches(signIn)
        }
    }
}

@Composable
private fun ModeChoice.labelText(): String = label ?: stringResource(
    when (ref.meaning) {
        ModeMeaning.QUIET -> Res.string.night_mode_quiet
        ModeMeaning.AWARE -> Res.string.night_mode_aware
        ModeMeaning.OFF -> Res.string.night_mode_off
        else -> Res.string.night_mode_aware
    },
)

@Composable
private fun previewText(preview: NightPreview, choices: List<ModeChoice>): String {
    val time = formatClockTime(preview.time.hour, preview.time.minute)
    val labels = choices.associate { it.key to it.labelText() }
    fun name(ref: ModeRef): String = labels[modeKey(ref)] ?: fallbackName(ref)
    val day = preview.isoDay?.let { stringResource(DayNames[it - 1]) }
    return when (preview) {
        is NightPreview.ToNight ->
            if (day == null) stringResource(Res.string.night_next_to, name(preview.mode), time)
            else stringResource(Res.string.night_next_to_on, name(preview.mode), time, day)
        is NightPreview.ToDay -> {
            val mode = preview.mode?.let { name(it) } ?: stringResource(Res.string.night_what_it_was_short)
            val key = if (preview.mode == null) {
                if (day == null) Res.string.night_next_back else Res.string.night_next_back_on
            } else {
                if (day == null) Res.string.night_next_to else Res.string.night_next_to_on
            }
            if (day == null) stringResource(key, mode, time) else stringResource(key, mode, time, day)
        }
    }
}

private fun fallbackName(ref: ModeRef): String = ModeResolver.label(ref)

@Composable
private fun lastText(last: NightLast, zone: TimeZone): String {
    val at = Instant.fromEpochMilliseconds(last.atMillis).toLocalDateTime(zone).time
    val time = formatClockTime(at.hour, at.minute)
    val key: StringResource = when (last.note) {
        NightNote.NIGHT_APPLIED -> Res.string.night_note_night_applied
        NightNote.NIGHT_ALREADY_SET -> Res.string.night_note_night_already
        NightNote.NIGHT_MODE_MISSING -> Res.string.night_note_night_missing
        NightNote.DAY_APPLIED -> Res.string.night_note_day_applied
        NightNote.DAY_ALREADY_SET -> Res.string.night_note_day_already
        NightNote.DAY_MODE_MISSING -> Res.string.night_note_day_missing
        NightNote.NOTHING_TO_RESTORE -> Res.string.night_note_nothing_to_restore
        NightNote.NOT_READY -> return stringResource(Res.string.night_note_not_ready)
    }
    return stringResource(key, time)
}

// ---------------------------------------------------------------- time and days

/** A time of day to set: the hour and the minutes each with a step either way, and am or pm on a 12-hour clock. */
@Composable
private fun TimeRow(label: String, time: LocalTime, twelve: Boolean, enabled: Boolean, onChange: (LocalTime) -> Unit) {
    val c = Ruru.colors
    val labelText: @Composable (Modifier) -> Unit = { m -> Txt(label, Ruru.type.body, if (enabled) c.ink else c.ink3, m) }
    val controls: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val hourText = if (twelve) hourOnFace(time.hour).toString() else time.hour.toString().padStart(2, '0')
            Stepper(hourText, stringResource(Res.string.night_hour_down), stringResource(Res.string.night_hour_up), enabled,
                { onChange(stepHour(time, -1, twelve)) }, { onChange(stepHour(time, 1, twelve)) })
            Txt(":", Ruru.type.body, c.ink3)
            Stepper(time.minute.toString().padStart(2, '0'), stringResource(Res.string.night_minute_down), stringResource(Res.string.night_minute_up), enabled,
                { onChange(stepMinute(time, -1)) }, { onChange(stepMinute(time, 1)) })
            if (twelve) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(88.dp)) {
                    Segmented(
                        listOf(false to stringResource(Res.string.night_am), true to stringResource(Res.string.night_pm)),
                        time.hour >= 12, { pm -> onChange(withHalf(time, pm)) }, stringResource(Res.string.night_ampm_label), enabled,
                    )
                }
            }
        }
    }
    if (LocalDesk.current) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            labelText(Modifier.weight(1f).padding(end = 12.dp))
            controls()
        }
    } else {
        // A narrow screen has no room for the label beside the controls.
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            labelText(Modifier)
            Spacer(Modifier.height(4.dp))
            controls()
        }
    }
}

@Composable
private fun Stepper(value: String, downLabel: String, upLabel: String, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    val c = Ruru.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietButton("−", onDown, Modifier.semantics { contentDescription = downLabel }, enabled)
        Txt(value, Ruru.type.figure, if (enabled) c.ink else c.ink3, Modifier.width(28.dp), maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center)
        QuietButton("+", onUp, Modifier.semantics { contentDescription = upLabel }, enabled)
    }
}

/** Seven pills, Monday first; a lit one is a night the schedule runs. */
@Composable
private fun DayChips(days: Set<Int>, enabled: Boolean, onToggle: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (day in 1..7) {
            val name = stringResource(DayNames[day - 1])
            DayChip(name, day in days, enabled, Modifier.weight(1f)) { onToggle(day) }
        }
    }
}

@Composable
private fun DayChip(name: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 32.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .background(if (on) c.bark else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (on) c.ink3 else c.line, shape)
            .toggleable(value = on, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null) { onClick() }
            .hoverable(interaction)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(name, if (on) Ruru.type.bodyMedium else Ruru.type.body, if (!enabled) c.ink4 else if (on) c.ink else c.ink2, maxLines = 1)
    }
}
