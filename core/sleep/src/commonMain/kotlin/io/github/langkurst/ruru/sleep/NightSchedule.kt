package io.github.langkurst.ruru.sleep

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.core.ModeResolver
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What the headset goes back to when the night window ends: a mode picked by meaning, or whatever
 * it was before the night mode went on.
 */
@Serializable(with = DayModeSerializer::class)
sealed interface DayMode {
    data class Mode(val ref: ModeRef) : DayMode

    /** The mode the headset was in before the night began; [NightProgress.whatItWas] holds it. */
    data object WhatItWas : DayMode
}

/**
 * The night noise schedule, the same on every system: from [bedtime] to [wake] on the [days] the
 * night begins on, the headset sits in [nightMode], then goes to [dayMode]. Saved as JSON under
 * [SleepStorage.NIGHT_SCHEDULE_KEY], so every name here is frozen once released.
 *
 * The window crosses midnight when [wake] is earlier than [bedtime] (22:30 to 07:00); when they
 * are equal there is no window and nothing happens. [days] are ISO weekday numbers (Monday = 1)
 * of the day the night *begins*: Friday 22:30 to 07:00 is Friday's night even though it ends on
 * Saturday. Empty days = never. Times are wall-clock in the phone's or PC's current time zone, so
 * a daylight-saving change or a trip moves the window with the clock.
 */
@Serializable
data class NightSchedule(
    val on: Boolean = false,
    @Serializable(with = ClockTimeSerializer::class) val bedtime: LocalTime = LocalTime(22, 30),
    @Serializable(with = ClockTimeSerializer::class) val wake: LocalTime = LocalTime(7, 0),
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val nightMode: ModeRef = ModeRef(ModeMeaning.QUIET),
    val dayMode: DayMode = DayMode.WhatItWas,
) {
    /** True when switching it on would do something: it is on, has a window and has days. */
    val isActive: Boolean get() = on && bedtime != wake && days.any { it in 1..7 }
}

/** One night's window in epoch milliseconds, [startMillis] inclusive and [endMillis] exclusive. */
data class NightWindow(val startMillis: Long, val endMillis: Long)

/**
 * What the runner has already done, kept between runs (the app stores it as JSON beside the
 * schedule; the key is the runner's choice, never part of a backup). It is what makes the
 * catch-up rule fire once: a night counts as applied from the moment its night mode went on, so
 * reconnecting at 3 am does not undo a mode the person changed by hand.
 */
@Serializable
data class NightProgress(
    /** The start of the last night whose night mode was applied; null = none yet. */
    val nightStartMillis: Long? = null,
    val nightEndMillis: Long? = null,
    /** Its day mode has been applied (or it needed none). */
    val dayApplied: Boolean = false,
    /** The mode before the night mode went on, for [DayMode.WhatItWas]; null once restored. */
    val whatItWas: ModeRef? = null,
) {
    /** A night mode is on and its day mode is still to come. */
    val dayPending: Boolean get() = nightStartMillis != null && !dayApplied
}

/** The next thing the night schedule wants done, and when. [atMillis] at or before now means due now. */
sealed interface NightAction {
    val atMillis: Long
    val window: NightWindow

    /** Put the headset in the night mode. */
    data class ApplyNight(override val atMillis: Long, override val window: NightWindow) : NightAction

    /** The window has ended (or ends at [atMillis]): put the headset in its day mode. */
    data class ApplyDay(override val atMillis: Long, override val window: NightWindow) : NightAction
}

/** What a runner's attempt at one [NightAction] did, for the Night page's last-run line. */
enum class NightNote {
    NIGHT_APPLIED, NIGHT_ALREADY_SET, NIGHT_MODE_MISSING,
    DAY_APPLIED, DAY_ALREADY_SET, DAY_MODE_MISSING, NOTHING_TO_RESTORE,

    /** The headset isn't connected or hasn't read its modes yet: nothing changed, ask again later. */
    NOT_READY,
}

/** The result of [NightScheduler.apply]: the one command to run (if any), the progress to store, and the note. */
data class NightApplication(val command: HeadsetCommand?, val progress: NightProgress, val note: NightNote)

/**
 * The night schedule's rules as pure functions of the schedule, what has been done so far, the
 * time and the time zone. A runner (the desktop tray, the Android alarm receiver, the iPhone app)
 * asks [nextAction], waits until [NightAction.atMillis], calls [apply] with the connected headset
 * and stores the progress it returns. On every connect it asks again: that is the catch-up.
 *
 * The catch-up rule: connect during a night window and the night mode is applied once for that
 * night, even though bedtime has passed. Connect after the wake time with a night mode still on,
 * and the day mode is applied once.
 */
object NightScheduler {
    /** The window of the night that begins on [startDate], or null when [schedule] has none that day. */
    fun windowFor(schedule: NightSchedule, startDate: LocalDate, zone: TimeZone): NightWindow? {
        if (!schedule.isActive || startDate.isoDay() !in schedule.days) return null
        val start = instantOf(startDate, schedule.bedtime, zone)
        val endDate = if (schedule.wake > schedule.bedtime) startDate else startDate.plusDays(1)
        val end = instantOf(endDate, schedule.wake, zone)
        // A spring-forward gap can squeeze an odd schedule to nothing; never return a backwards window.
        return if (end > start) NightWindow(start, end) else null
    }

    /** The night whose window contains [nowMillis], or null. */
    fun windowAt(schedule: NightSchedule, nowMillis: Long, zone: TimeZone): NightWindow? {
        val today = dateOf(nowMillis, zone)
        for (back in 0..1) {
            val w = windowFor(schedule, today.plusDays(-back), zone) ?: continue
            if (nowMillis >= w.startMillis && nowMillis < w.endMillis) return w
        }
        return null
    }

    /** The first night that starts after [nowMillis], or null when the schedule has none (within a week). */
    fun nextWindow(schedule: NightSchedule, nowMillis: Long, zone: TimeZone): NightWindow? {
        val today = dateOf(nowMillis, zone)
        for (ahead in 0..8) {
            val w = windowFor(schedule, today.plusDays(ahead), zone) ?: continue
            if (w.startMillis > nowMillis) return w
        }
        return null
    }

    /**
     * The next action to take, or null when the schedule is off or has nothing to do. In order:
     *  1. a night mode still on whose window has ended (by the schedule as it is now): its day mode (overdue until it is done);
     *  2. inside a night: its night mode if not yet applied, else its day mode at the window's end;
     *  3. otherwise the next night's night mode at its bedtime.
     */
    fun nextAction(schedule: NightSchedule, progress: NightProgress, nowMillis: Long, zone: TimeZone): NightAction? {
        if (!schedule.isActive) return null
        val start = progress.nightStartMillis
        val end = progress.nightEndMillis
        if (progress.dayPending && start != null && end != null) {
            // The wake time may have been edited since the night began: the same night under today's schedule
            // ends when it says, so an earlier wake time ends it earlier (and a later one keeps it going).
            val edited = windowFor(schedule, dateOf(start, zone), zone)?.takeIf { it.startMillis == start }
            val dayAt = edited?.endMillis ?: end
            if (nowMillis >= dayAt) return NightAction.ApplyDay(dayAt, NightWindow(start, dayAt))
        }
        val current = windowAt(schedule, nowMillis, zone)
        if (current != null) {
            return if (progress.nightStartMillis != current.startMillis) {
                NightAction.ApplyNight(current.startMillis, current)
            } else if (!progress.dayApplied) {
                NightAction.ApplyDay(current.endMillis, current)
            } else {
                nextWindow(schedule, nowMillis, zone)?.let { NightAction.ApplyNight(it.startMillis, it) }
            }
        }
        return nextWindow(schedule, nowMillis, zone)?.let { NightAction.ApplyNight(it.startMillis, it) }
    }

    /**
     * Carries out [action] on the headset in [s]: the one [HeadsetCommand] to run (picked by
     * meaning, so the same schedule works on every maker's slot layout) and the progress to store.
     * Nothing changes (and the action stays due) while the headset isn't connected with its modes read.
     */
    fun apply(action: NightAction, schedule: NightSchedule, progress: NightProgress, s: HeadphoneState): NightApplication {
        if (!s.isConnected || s.modes.none { it.isConfigured }) return NightApplication(null, progress, NightNote.NOT_READY)
        val address = s.connection.deviceAddress
        return when (action) {
            is NightAction.ApplyNight -> {
                // A day mode still pending from an earlier night keeps its "what it was": the night mode on
                // the headset now is not what the person had.
                val before = if (progress.dayPending) progress.whatItWas
                else s.currentMode?.let { ModeResolver.refFor(it, address) }
                val slot = ModeResolver.slotFor(schedule.nightMode, s)
                val next = NightProgress(action.window.startMillis, action.window.endMillis, dayApplied = false, whatItWas = before)
                when {
                    slot == null -> NightApplication(null, next, NightNote.NIGHT_MODE_MISSING)
                    slot == s.currentModeIndex -> NightApplication(null, next, NightNote.NIGHT_ALREADY_SET)
                    else -> NightApplication(HeadsetCommand.SetMode(slot), next, NightNote.NIGHT_APPLIED)
                }
            }
            is NightAction.ApplyDay -> {
                val done = progress.copy(
                    nightStartMillis = action.window.startMillis,
                    nightEndMillis = action.window.endMillis,
                    dayApplied = true,
                    whatItWas = null,
                )
                val target = when (val d = schedule.dayMode) {
                    is DayMode.Mode -> d.ref
                    DayMode.WhatItWas -> progress.whatItWas
                } ?: return NightApplication(null, done, NightNote.NOTHING_TO_RESTORE)
                val slot = ModeResolver.slotFor(target, s)
                when {
                    slot == null -> NightApplication(null, done, NightNote.DAY_MODE_MISSING)
                    slot == s.currentModeIndex -> NightApplication(null, done, NightNote.DAY_ALREADY_SET)
                    else -> NightApplication(HeadsetCommand.SetMode(slot), done, NightNote.DAY_APPLIED)
                }
            }
        }
    }
}

/** "HH:mm" in the saved JSON. */
internal object ClockTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.github.langkurst.ruru.sleep.ClockTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString("${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}")
    }

    override fun deserialize(decoder: Decoder): LocalTime {
        val text = decoder.decodeString()
        val parts = text.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull()
        val m = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && h != null && m != null && h in 0..23 && m in 0..59) { "Not a time of day: $text" }
        return LocalTime(h, m)
    }
}

/** [DayMode]'s stored shape: `{"whatItWas":true}` or `{"mode":{...}}`. */
@Serializable
private class StoredDayMode(val whatItWas: Boolean = false, val mode: ModeRef? = null)

internal object DayModeSerializer : KSerializer<DayMode> {
    override val descriptor: SerialDescriptor = StoredDayMode.serializer().descriptor

    override fun serialize(encoder: Encoder, value: DayMode) = encoder.encodeSerializableValue(
        StoredDayMode.serializer(),
        when (value) {
            is DayMode.Mode -> StoredDayMode(mode = value.ref)
            DayMode.WhatItWas -> StoredDayMode(whatItWas = true)
        },
    )

    override fun deserialize(decoder: Decoder): DayMode {
        val stored = decoder.decodeSerializableValue(StoredDayMode.serializer())
        return stored.mode?.let { DayMode.Mode(it) } ?: DayMode.WhatItWas
    }
}
