package io.github.langkurst.ruru.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.night.formatClockTime
import io.github.langkurst.ruru.app.night.uses24HourClock
import io.github.langkurst.ruru.app.sleep.DaysKind
import io.github.langkurst.ruru.app.sleep.SleepDurationChoices
import io.github.langkurst.ruru.app.sleep.SleepModel
import io.github.langkurst.ruru.app.sleep.SleepSoundChoices
import io.github.langkurst.ruru.app.sleep.clockOf
import io.github.langkurst.ruru.app.sleep.countdownText
import io.github.langkurst.ruru.app.sleep.daysKind
import io.github.langkurst.ruru.app.sleep.noiseOffChoices
import io.github.langkurst.ruru.sleep.AlarmOutput
import io.github.langkurst.ruru.sleep.EndOutcome
import io.github.langkurst.ruru.sleep.HeadsetAbilities
import io.github.langkurst.ruru.sleep.NightExtra
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepEndAction
import io.github.langkurst.ruru.sleep.SleepNoteCode
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.sleep.sound.AlarmTone
import io.github.langkurst.ruru.sleep.sound.SleepSound
import io.github.langkurst.ruru.ui.ChoiceRow
import io.github.langkurst.ruru.ui.LevelSlider
import io.github.langkurst.ruru.ui.Panel
import io.github.langkurst.ruru.ui.PrimaryButton
import io.github.langkurst.ruru.ui.QuietButton
import io.github.langkurst.ruru.ui.Ruru
import io.github.langkurst.ruru.ui.Segmented
import io.github.langkurst.ruru.ui.SettingRow
import io.github.langkurst.ruru.ui.Toggle
import io.github.langkurst.ruru.ui.Txt
import io.github.langkurst.ruru.ui.focusRing
import io.github.langkurst.ruru.ui.res.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The Sleep tab's model on this system; null where the tab has no engine behind it (computers, and an iPhone build without one). */
val LocalSleep = staticCompositionLocalOf<SleepModel?> { null }

/** Sleep (the phone tab): tonight's summary, the timer, the sounds and saved mixes, the alarms and what happens to the headphones. */
@Composable
internal fun SleepPage(state: AppState, controller: HeadsetController) {
    val sleep = LocalSleep.current
    if (sleep == null) {
        PlaceholderPage(stringResource(Res.string.page_sleep), stringResource(Res.string.page_sleep_lead), state, controller)
        return
    }
    PageFrame(stringResource(Res.string.page_sleep), stringResource(Res.string.page_sleep_lead), state, controller) {
        val session by sleep.engine.session.collectAsState()
        // The clock is looked at every few seconds while the page is open, for the countdown and the "tonight" times.
        var clock by remember { mutableStateOf(sleep.nowMillis()) }
        LaunchedEffect(sleep) {
            while (true) {
                delay(5_000)
                clock = sleep.nowMillis()
            }
        }
        val abilities = remember(state.headset) { sleep.abilities(state.headset) }
        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val s = session) {
                SleepSessionState.Idle -> TonightPanel(sleep, abilities, clock)
                is SleepSessionState.Running -> RunningPanel(sleep, s, clock)
                is SleepSessionState.Ringing -> RingingPanel(sleep, s)
                is SleepSessionState.Snoozed -> SnoozedPanel(sleep, s)
            }
            NotesPanel(sleep)
            TimerPanel(sleep, session is SleepSessionState.Idle)
            SoundsPanel(sleep)
            MixesPanel(sleep)
            AlarmsPanel(sleep)
            EndPanel(sleep, abilities)
        }
    }
}

// ---------------------------------------------------------------- tonight, running, ringing

@Composable
private fun TonightPanel(sleep: SleepModel, abilities: HeadsetAbilities, clock: Long) {
    val c = Ruru.colors
    val plan by sleep.plan.collectAsState()
    val alarms by sleep.alarms.collectAsState()
    // Recomputed when the plan, the alarms or the clock change.
    val tonight = remember(plan, alarms, abilities, clock) { sleep.tonight(abilities) }
    val zone = sleep.timeZone()
    Panel(stringResource(Res.string.sleep_tonight_title)) {
        val length = durationText(tonight.durationMinutes)
        val summary = if (tonight.soundIds.isEmpty()) {
            stringResource(Res.string.sleep_tonight_timer_only, length)
        } else {
            stringResource(Res.string.sleep_tonight_sounds, soundList(tonight.soundIds), length, durationText(tonight.fadeMinutes))
        }
        Txt(summary, Ruru.type.bodyMedium, c.ink)
        Spacer(Modifier.height(6.dp))
        val alarm = tonight.nextAlarm
        val alarmAt = tonight.nextAlarmAtMillis
        if (alarm != null && alarmAt != null) {
            val (h, m) = clockOf(alarmAt, zone)
            val time = formatClockTime(h, m)
            val line = when (alarm.output) {
                AlarmOutput.EARBUDS -> Res.string.sleep_tonight_alarm_earbuds
                AlarmOutput.PHONE -> Res.string.sleep_tonight_alarm_phone
                AlarmOutput.BOTH -> Res.string.sleep_tonight_alarm_both
            }
            Txt(stringResource(line, time), Ruru.type.body, c.ink2)
        } else {
            Txt(stringResource(Res.string.sleep_tonight_no_alarm), Ruru.type.body, c.ink2)
        }
        val outcome = when (tonight.endOutcome) {
            EndOutcome.STAYS_CONNECTED -> Res.string.sleep_end_stays
            EndOutcome.DISCONNECTS -> Res.string.sleep_end_disconnects
            EndOutcome.SWITCHES_OFF -> Res.string.sleep_end_switches_off
            EndOutcome.STAYS_CONNECTED_CANT -> Res.string.sleep_end_cant
        }
        Spacer(Modifier.height(4.dp))
        Txt(stringResource(outcome), Ruru.type.small, c.ink3)
        if (tonight.alarmMovesToPhone) {
            Spacer(Modifier.height(4.dp))
            Txt(stringResource(Res.string.sleep_alarm_moves_phone), Ruru.type.small, c.ink3)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(Res.string.sleep_start), sleep::start, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RunningPanel(sleep: SleepModel, s: SleepSessionState.Running, clock: Long) {
    val c = Ruru.colors
    // The countdown ticks with the page's own clock, which moves every few seconds.
    val left = s.remainingMillis(maxOf(clock, sleep.nowMillis()))
    val (h, m) = clockOf(s.timeline.endsAtMillis, sleep.timeZone())
    Panel(stringResource(Res.string.sleep_running_title)) {
        Txt(stringResource(Res.string.sleep_running_left, countdownText(left)), Ruru.type.title, c.ink)
        Spacer(Modifier.height(2.dp))
        val ends = if (s.hasSounds) Res.string.sleep_running_ends else Res.string.sleep_running_timer_only
        Txt(stringResource(ends, formatClockTime(h, m)), Ruru.type.small, c.ink3)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton(stringResource(Res.string.sleep_plus, 15), { sleep.engine.extend(15) })
            QuietButton(stringResource(Res.string.sleep_stop), sleep.engine::stop, danger = true)
        }
    }
}

@Composable
private fun RingingPanel(sleep: SleepModel, s: SleepSessionState.Ringing) {
    Panel(stringResource(Res.string.sleep_ringing_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(Res.string.sleep_stop), sleep.engine::stopAlarm)
            val minutes = s.snoozeMinutes
            if (s.canSnooze && minutes != null) QuietButton(stringResource(Res.string.sleep_ringing_snooze, minutes), sleep.engine::snoozeAlarm)
        }
    }
}

@Composable
private fun SnoozedPanel(sleep: SleepModel, s: SleepSessionState.Snoozed) {
    val (h, m) = clockOf(s.ringsAtMillis, sleep.timeZone())
    Panel(stringResource(Res.string.sleep_ringing_title)) {
        Txt(stringResource(Res.string.sleep_snoozed, formatClockTime(h, m)), Ruru.type.body, Ruru.colors.ink)
        Spacer(Modifier.height(10.dp))
        QuietButton(stringResource(Res.string.sleep_stop), sleep.engine::stopAlarm)
    }
}

@Composable
private fun NotesPanel(sleep: SleepModel) {
    val notes by sleep.engine.notes.collectAsState()
    val lines = notes.mapNotNull { n -> sleepNoteText(n.code)?.let { it to n.isProblem } }.distinct()
    if (lines.isEmpty()) return
    Panel(stringResource(Res.string.sleep_log_title)) {
        for ((text, problem) in lines) {
            Txt(stringResource(text), Ruru.type.body, if (problem) Ruru.colors.ink else Ruru.colors.ink2, Modifier.padding(vertical = 2.dp))
        }
    }
}

/** The sentence a night step's note is shown with; null for a note that only gives a reason. */
internal fun sleepNoteText(code: SleepNoteCode): StringResource? = when (code) {
    SleepNoteCode.TOUCH_LOCKED -> Res.string.sleep_note_touch_locked
    SleepNoteCode.PROMPTS_OFF -> Res.string.sleep_note_prompts_off
    SleepNoteCode.NOISE_OFF -> Res.string.sleep_note_noise_off
    SleepNoteCode.NOISE_ALREADY_OFF -> Res.string.sleep_note_noise_already_off
    SleepNoteCode.POWERED_OFF -> Res.string.sleep_note_powered_off
    SleepNoteCode.DISCONNECTED -> Res.string.sleep_note_disconnected
    SleepNoteCode.RESTORED -> Res.string.sleep_note_restored
    SleepNoteCode.TOUCH_FAILED, SleepNoteCode.PROMPTS_FAILED, SleepNoteCode.NOISE_FAILED, SleepNoteCode.DISCONNECT_FAILED -> Res.string.sleep_note_failed
    SleepNoteCode.NO_DRIVER, SleepNoteCode.CONTROLS_OFFLINE, SleepNoteCode.NO_HEADSET -> Res.string.sleep_note_cant_change
    SleepNoteCode.NOISE_NONE, SleepNoteCode.TOUCH_NONE, SleepNoteCode.PROMPTS_NONE, SleepNoteCode.POWER_OFF_NONE -> Res.string.sleep_note_unsupported
    SleepNoteCode.DISCONNECT_UNSUPPORTED, SleepNoteCode.DISCONNECT_NOT_LINKED -> Res.string.sleep_note_no_disconnect
    SleepNoteCode.POWER_OFF_REFUSED -> Res.string.sleep_note_power_off_refused
    SleepNoteCode.POWER_OFF_FALLBACK -> null
}

// ---------------------------------------------------------------- timer and sounds

@Composable
private fun TimerPanel(sleep: SleepModel, idle: Boolean) {
    val plan by sleep.plan.collectAsState()
    Panel(stringResource(Res.string.sleep_timer_title)) {
        Txt(stringResource(Res.string.sleep_timer_hint), Ruru.type.small, Ruru.colors.ink3)
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            for (minutes in SleepDurationChoices) {
                Chip(durationText(minutes), plan.durationMinutes == minutes, idle) { sleep.setDuration(minutes) }
            }
        }
    }
}

@Composable
private fun SoundsPanel(sleep: SleepModel) {
    val c = Ruru.colors
    val plan by sleep.plan.collectAsState()
    Panel(stringResource(Res.string.sleep_sounds_title)) {
        Txt(stringResource(if (plan.layers.isEmpty()) Res.string.sleep_sounds_none else Res.string.sleep_sounds_hint), Ruru.type.small, c.ink3)
        Spacer(Modifier.height(8.dp))
        ChipFlow {
            for (sound in SleepSoundChoices) {
                val on = plan.layers.any { it.soundId == sound.storedId }
                val room = plan.layers.size < SleepPlan.MAX_LAYERS
                Chip(soundName(sound), on, on || room) {
                    if (on) sleep.removeSound(sound.storedId) else sleep.addSound(sound.storedId)
                }
            }
        }
        for (layer in plan.layers) {
            val name = soundName(layer.soundId)
            Spacer(Modifier.height(10.dp))
            Txt(name, Ruru.type.body, c.ink)
            Spacer(Modifier.height(4.dp))
            LevelSlider(
                value = (layer.gain * 100).toInt(),
                range = 0..100,
                onCommit = { sleep.setGain(layer.soundId, it / 100f) },
                label = stringResource(Res.string.sleep_layer_level, name),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (plan.layers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Txt(stringResource(Res.string.sleep_volume), Ruru.type.body, c.ink)
            Txt(stringResource(Res.string.sleep_volume_hint), Ruru.type.small, c.ink3)
            Spacer(Modifier.height(4.dp))
            LevelSlider(
                value = (plan.volume * 100).toInt(),
                range = 0..100,
                onCommit = { sleep.setVolume(it / 100f) },
                label = stringResource(Res.string.sleep_volume),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MixesPanel(sleep: SleepModel) {
    val c = Ruru.colors
    val plan by sleep.plan.collectAsState()
    val mixes by sleep.mixes.collectAsState()
    val saved = mixes.any { it.layers == plan.layers }
    Panel(stringResource(Res.string.sleep_mixes_title)) {
        if (mixes.isEmpty()) Txt(stringResource(Res.string.sleep_mixes_empty), Ruru.type.small, c.ink3)
        for (mix in mixes) {
            val on = mix.layers == plan.layers
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Chip(mix.name, on, true) { sleep.applyMix(mix.id) } }
                QuietButton("×", { sleep.deleteMix(mix.id) }, Modifier.semanticsLabel(stringResource(Res.string.sleep_mix_delete, mix.name)))
            }
        }
        if (plan.layers.isNotEmpty() && !saved) {
            Spacer(Modifier.height(6.dp))
            val name = plan.layers.map { soundName(it.soundId) }.joinToString(" + ")
            QuietButton(stringResource(Res.string.sleep_mix_save), { sleep.saveMix(name) })
        }
    }
}

// ---------------------------------------------------------------- alarms

@Composable
private fun AlarmsPanel(sleep: SleepModel) {
    val c = Ruru.colors
    val alarms by sleep.alarms.collectAsState()
    val allowed by sleep.engine.alarmsAllowed.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    Panel(stringResource(Res.string.sleep_alarms_title)) {
        if (!allowed && alarms.any { it.enabled }) {
            Txt(stringResource(Res.string.sleep_alarms_blocked), Ruru.type.small, c.ink2)
            Spacer(Modifier.height(8.dp))
            QuietButton(stringResource(Res.string.sleep_alarms_allow), sleep.engine::openAlarmAccess)
            Spacer(Modifier.height(10.dp))
        }
        if (alarms.isEmpty()) Txt(stringResource(Res.string.sleep_alarms_empty), Ruru.type.body, c.ink3)
        for (alarm in alarms) {
            AlarmRow(sleep, alarm, open = editing == alarm.id, onToggleEdit = { editing = if (editing == alarm.id) null else alarm.id })
        }
        Spacer(Modifier.height(8.dp))
        QuietButton(stringResource(Res.string.sleep_alarm_add), {
            val fresh = sleep.newAlarm()
            sleep.saveAlarm(fresh)
            editing = fresh.id
        })
    }
}

@Composable
private fun AlarmRow(sleep: SleepModel, alarm: SleepAlarm, open: Boolean, onToggleEdit: () -> Unit) {
    val c = Ruru.colors
    val time = formatClockTime(alarm.hour, alarm.minute)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Txt(time, Ruru.type.title, if (alarm.enabled) c.ink else c.ink3)
                Txt(daysText(alarm.days) + " · " + outputName(alarm.output), Ruru.type.small, c.ink3)
            }
            QuietButton(stringResource(if (open) Res.string.sleep_alarm_done else Res.string.sleep_alarm_edit), onToggleEdit)
            Spacer(Modifier.width(8.dp))
            Toggle(alarm.enabled, { sleep.setAlarmOn(alarm.id, it) }, stringResource(Res.string.sleep_alarm_on, time))
        }
        if (open) AlarmEditor(sleep, alarm, onDone = onToggleEdit)
    }
}

@Composable
private fun AlarmEditor(sleep: SleepModel, alarm: SleepAlarm, onDone: () -> Unit) {
    val c = Ruru.colors
    val twelve = !uses24HourClock()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        TimeRow(stringResource(Res.string.sleep_alarm_time), kotlinx.datetime.LocalTime(alarm.hour, alarm.minute), twelve, true) { t ->
            sleep.saveAlarm(alarm.copy(hour = t.hour, minute = t.minute))
        }
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(Res.string.sleep_alarm_repeat), Ruru.type.body, c.ink)
        Spacer(Modifier.height(6.dp))
        DayChips(alarm.days, true) { day ->
            sleep.saveAlarm(alarm.copy(days = if (day in alarm.days) alarm.days - day else alarm.days + day))
        }
        Spacer(Modifier.height(10.dp))
        Txt(stringResource(Res.string.sleep_alarm_rings), Ruru.type.body, c.ink)
        Spacer(Modifier.height(6.dp))
        Segmented(
            AlarmOutput.entries.map { it to outputName(it) },
            alarm.output,
            { sleep.saveAlarm(alarm.copy(output = it)) },
            stringResource(Res.string.sleep_alarm_rings),
        )
        if (alarm.output == AlarmOutput.EARBUDS) {
            Spacer(Modifier.height(4.dp))
            Txt(stringResource(Res.string.sleep_alarm_earbuds_hint), Ruru.type.small, c.ink3)
        }
        Spacer(Modifier.height(6.dp))
        val tones = listOf(AlarmTone.CHIME.storedId, AlarmTone.BIRDS.storedId, AlarmTone.CLASSIC.storedId, SleepAlarm.TONE_SYSTEM)
        ChoiceRow(
            stringResource(Res.string.sleep_alarm_sound),
            tones.map { it to toneName(it) },
            alarm.toneId,
            { sleep.saveAlarm(alarm.copy(toneId = it)) },
        )
        ChoiceRow(
            stringResource(Res.string.sleep_alarm_snooze),
            SleepAlarm.SNOOZE_CHOICES.map { it to if (it == null) stringResource(Res.string.sleep_snooze_off) else stringResource(Res.string.sleep_snooze_minutes, it) },
            alarm.snoozeMinutes,
            { sleep.saveAlarm(alarm.copy(snoozeMinutes = it)) },
        )
        Spacer(Modifier.height(8.dp))
        QuietButton(stringResource(Res.string.sleep_alarm_delete), { sleep.deleteAlarm(alarm.id); onDone() }, danger = true)
    }
}

// ---------------------------------------------------------------- the end of the night

@Composable
private fun EndPanel(sleep: SleepModel, abilities: HeadsetAbilities) {
    val c = Ruru.colors
    val plan by sleep.plan.collectAsState()
    Panel(stringResource(Res.string.sleep_end_title)) {
        ChoiceRow(
            stringResource(Res.string.sleep_end_action),
            listOf(
                SleepEndAction.STOP_SOUNDS to stringResource(Res.string.sleep_end_action_stop),
                SleepEndAction.DISCONNECT to stringResource(Res.string.sleep_end_action_disconnect),
                SleepEndAction.POWER_OFF to stringResource(Res.string.sleep_end_action_off),
            ),
            plan.endAction,
            { a -> sleep.updatePlan { it.copy(endAction = a) } },
        )
        val fades = listOf(0, 5, 10, 20, 30)
        ChoiceRow(
            stringResource(Res.string.sleep_fade_title),
            fades.map { it to if (it == 0) stringResource(Res.string.sleep_snooze_off) else durationText(it) },
            plan.fadeOutMinutes,
            { m -> sleep.updatePlan { it.copy(fadeOutMinutes = m) } },
        )
        val noise = noiseOffChoices(plan.durationMinutes)
        ChoiceRow(
            stringResource(Res.string.sleep_noise_title),
            noise.map { it to noiseOffName(it, plan.durationMinutes) },
            plan.noiseOffAfterMinutes?.let { if (it >= plan.durationMinutes) plan.durationMinutes else it },
            { m -> sleep.updatePlan { it.copy(noiseOffAfterMinutes = m) } },
            enabled = abilities.noiseOff.available,
        )
        Spacer(Modifier.height(4.dp))
        ExtraRow(sleep, plan, NightExtra.TOUCH_LOCK, Res.string.sleep_extra_touch, Res.string.sleep_extra_touch_hint, abilities.touchLock.available)
        ExtraRow(sleep, plan, NightExtra.PROMPTS_OFF, Res.string.sleep_extra_prompts, Res.string.sleep_extra_prompts_hint, abilities.promptsOff.available)
        if (plan.nightExtras.isNotEmpty() || plan.noiseOffAfterMinutes != null) {
            Spacer(Modifier.height(6.dp))
            Txt(stringResource(Res.string.sleep_extra_restored), Ruru.type.small, c.ink3)
        }
    }
}

@Composable
private fun ExtraRow(sleep: SleepModel, plan: SleepPlan, extra: NightExtra, label: StringResource, hint: StringResource, available: Boolean) {
    val text = stringResource(label)
    SettingRow(text, stringResource(hint), enabled = available) {
        Toggle(extra in plan.nightExtras && available, { on ->
            sleep.updatePlan { p -> p.copy(nightExtras = if (on) p.nightExtras + extra else p.nightExtras - extra) }
        }, text, enabled = available)
    }
}

// ---------------------------------------------------------------- words

@Composable
private fun durationText(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(Res.string.sleep_chip_hours, minutes / 60) else stringResource(Res.string.sleep_chip_minutes, minutes)

@Composable
private fun noiseOffName(minutes: Int?, durationMinutes: Int): String = when {
    minutes == null -> stringResource(Res.string.sleep_noise_keep)
    minutes == 0 -> stringResource(Res.string.sleep_noise_now)
    minutes >= durationMinutes -> stringResource(Res.string.sleep_noise_end)
    else -> stringResource(Res.string.sleep_noise_after, durationText(minutes))
}

@Composable
private fun soundName(sound: SleepSound): String = stringResource(
    when (sound) {
        SleepSound.WHITE_NOISE -> Res.string.sleep_sound_white
        SleepSound.PINK_NOISE -> Res.string.sleep_sound_pink
        SleepSound.BROWN_NOISE -> Res.string.sleep_sound_brown
        SleepSound.RAIN -> Res.string.sleep_sound_rain
        SleepSound.HEAVY_RAIN -> Res.string.sleep_sound_heavy_rain
        SleepSound.OCEAN -> Res.string.sleep_sound_ocean
        SleepSound.STREAM -> Res.string.sleep_sound_stream
        SleepSound.WIND -> Res.string.sleep_sound_wind
        SleepSound.FIRE -> Res.string.sleep_sound_fire
        SleepSound.FAN -> Res.string.sleep_sound_fan
    },
)

@Composable
private fun soundName(id: String): String = SleepSound.fromStoredId(id)?.let { soundName(it) } ?: id

/** "Rain", "Rain and Ocean", "Rain, Ocean and Fan". */
@Composable
private fun soundList(ids: List<String>): String {
    val names = ids.map { soundName(it) }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> stringResource(Res.string.sleep_and, names.dropLast(1).joinToString(stringResource(Res.string.list_separator)), names.last())
    }
}

@Composable
private fun outputName(output: AlarmOutput): String = stringResource(
    when (output) {
        AlarmOutput.EARBUDS -> Res.string.sleep_out_earbuds
        AlarmOutput.PHONE -> Res.string.sleep_out_phone
        AlarmOutput.BOTH -> Res.string.sleep_out_both
    },
)

@Composable
private fun toneName(id: String): String = stringResource(
    when (id) {
        AlarmTone.CHIME.storedId -> Res.string.sleep_tone_chime
        AlarmTone.BIRDS.storedId -> Res.string.sleep_tone_birds
        AlarmTone.CLASSIC.storedId -> Res.string.sleep_tone_classic
        else -> Res.string.sleep_tone_system
    },
)

@Composable
private fun daysText(days: Set<Int>): String = when (daysKind(days)) {
    DaysKind.ONCE -> stringResource(Res.string.sleep_alarm_once)
    DaysKind.EVERY_DAY -> stringResource(Res.string.sleep_alarm_every_day)
    DaysKind.WEEKDAYS -> stringResource(Res.string.sleep_alarm_weekdays)
    DaysKind.WEEKENDS -> stringResource(Res.string.sleep_alarm_weekends)
    DaysKind.SOME -> days.filter { it in 1..7 }.sorted().map { stringResource(DayNames[it - 1]) }.joinToString(stringResource(Res.string.list_separator))
}

// ---------------------------------------------------------------- chips

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** A pill you tap to pick; a lit one is picked. */
@Composable
private fun Chip(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Ruru.colors
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .background(if (on) c.bark else Color.Transparent)
            .border(1.dp, if (on) c.ink3 else c.line, shape)
            .toggleable(value = on, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null) { onClick() }
            .hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, if (on) Ruru.type.bodyMedium else Ruru.type.body, if (!enabled) c.ink4 else if (on) c.ink else c.ink2, maxLines = 1)
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = this.then(
    Modifier.semantics { contentDescription = label },
)
