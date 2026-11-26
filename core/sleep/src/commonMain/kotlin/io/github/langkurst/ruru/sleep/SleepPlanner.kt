package io.github.langkurst.ruru.sleep

import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.sleep.sound.FadeCurve
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The Sleep tab's rules as pure functions: the night's timeline, the sound envelope, alarm times
 * and the battery guess. Everything time-based takes `now` and a [TimeZone], so the tests pin both
 * and a daylight-saving change is just another case.
 */
object SleepPlanner {
    const val MINUTE = 60_000L

    /** Sounds fade in over this long at the start. */
    const val FADE_IN_MS = 30_000L

    /** [SleepPlan] clamped to what the tab allows (a stored plan from a newer build may be out of range). */
    fun sanitise(plan: SleepPlan): SleepPlan = plan.copy(
        durationMinutes = plan.durationMinutes.coerceIn(SleepPlan.MIN_MINUTES, SleepPlan.MAX_MINUTES),
        layers = plan.layers.take(SleepPlan.MAX_LAYERS).map { it.copy(gain = it.gain.coerceIn(0f, 1f)) },
        fadeOutMinutes = plan.fadeOutMinutes.coerceIn(0, 30),
        volume = plan.volume.coerceIn(0f, 1f),
        volumeCapPercent = plan.volumeCapPercent.coerceIn(5, 100),
        noiseOffAfterMinutes = plan.noiseOffAfterMinutes?.coerceAtLeast(0),
    )

    fun timeline(plan: SleepPlan, startedAtMillis: Long): SleepTimeline {
        val p = sanitise(plan)
        val ends = startedAtMillis + p.durationMinutes * MINUTE
        // A fade never takes more than half the night: a 15-minute timer with a 10-minute fade
        // would otherwise spend most of its time getting quieter.
        val fade = minOf(p.fadeOutMinutes * MINUTE, (ends - startedAtMillis) / 2)
        val noiseOff = p.noiseOffAfterMinutes?.let { minutes -> minOf(startedAtMillis + minutes * MINUTE, ends) }
        return SleepTimeline(startedAtMillis, ends, ends - fade, noiseOff)
    }

    /** The timeline after "+N min": the end and the fade move, noise-off stays where it was unless it was "at the end". */
    fun extended(timeline: SleepTimeline, plan: SleepPlan, minutes: Int): SleepTimeline {
        val p = sanitise(plan)
        val ends = timeline.endsAtMillis + minutes * MINUTE
        val fade = minOf(p.fadeOutMinutes * MINUTE, (ends - timeline.startedAtMillis) / 2)
        val noiseAtEnd = timeline.noiseOffAtMillis == timeline.endsAtMillis
        return timeline.copy(
            endsAtMillis = ends,
            fadeOutStartsAtMillis = ends - fade,
            noiseOffAtMillis = if (noiseAtEnd) ends else timeline.noiseOffAtMillis,
        )
    }

    fun phase(timeline: SleepTimeline, hasSounds: Boolean, nowMillis: Long): SleepPhase = when {
        !hasSounds -> SleepPhase.TIMER_ONLY
        nowMillis < timeline.startedAtMillis + FADE_IN_MS -> SleepPhase.FADING_IN
        nowMillis >= timeline.fadeOutStartsAtMillis -> SleepPhase.FADING_OUT
        else -> SleepPhase.PLAYING
    }

    /** 0..1: fade in over [FADE_IN_MS], hold, then the long fade out to silence at the end. */
    fun envelope(timeline: SleepTimeline, nowMillis: Long): Float {
        if (nowMillis >= timeline.endsAtMillis) return 0f
        val fadeIn = FadeCurve.fadeIn(nowMillis - timeline.startedAtMillis, FADE_IN_MS)
        val fadeLength = timeline.endsAtMillis - timeline.fadeOutStartsAtMillis
        val fadeOut = if (nowMillis < timeline.fadeOutStartsAtMillis || fadeLength <= 0) 1f
        else FadeCurve.fadeOut(nowMillis - timeline.fadeOutStartsAtMillis, fadeLength)
        return fadeIn * fadeOut
    }

    /**
     * The next time [alarm] rings strictly after [nowMillis] (an alarm set for this very minute
     * rings tomorrow), or null when it is disabled. [SleepAlarm.days] empty = the next occurrence.
     */
    fun nextRing(alarm: SleepAlarm, nowMillis: Long, zone: TimeZone): Long? {
        if (!alarm.enabled) return null
        val time = LocalTime(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
        val days = alarm.days.filter { it in 1..7 }.toSet()
        return nextLocalTime(time, days, nowMillis, zone)
    }

    /** The soonest enabled alarm and when it rings. */
    fun nextAlarm(alarms: List<SleepAlarm>, nowMillis: Long, zone: TimeZone): Pair<SleepAlarm, Long>? =
        alarms.mapNotNull { a -> nextRing(a, nowMillis, zone)?.let { a to it } }.minByOrNull { it.second }

    /**
     * The wake rings to arm for [alarms], in list order: each enabled alarm's next ring after
     * [nowMillis] (or after its [skipUntil] time) and the start of its wake-up window when that is
     * still ahead.
     *
     * None at all when exact alarms aren't allowed: an inexact wake alarm may ring up to an hour
     * late, or not at all on a locked phone, so an alarm that is switched on without the access
     * waits unarmed instead of looking set.
     */
    fun ringsToArm(
        alarms: List<SleepAlarm>,
        nowMillis: Long,
        skipUntil: Map<String, Long>,
        exactAllowed: Boolean,
        zone: TimeZone,
    ): List<PlannedRing> {
        if (!exactAllowed) return emptyList()
        return alarms.mapNotNull { alarm ->
            val at = nextRing(alarm, maxOf(nowMillis, skipUntil[alarm.id] ?: 0L), zone) ?: return@mapNotNull null
            val windowStart = (at - alarm.wakeWindowMinutes * MINUTE).takeIf { alarm.wakeWindowMinutes > 0 && it > nowMillis }
            PlannedRing(alarm, at, windowStart)
        }
    }

    /**
     * Listening minutes left: the headset's own figure when it gives one, else a rough guess from
     * the percentage (earbuds ~8 h, headphones ~24 h at 100 %, playing quiet sounds). Null when
     * nothing is known. The second value says whether it is a guess.
     */
    fun minutesLeft(percent: Int?, headsetMinutes: Int?, formFactor: FormFactor): Pair<Int?, Boolean> {
        if (headsetMinutes != null && headsetMinutes > 0) return headsetMinutes to false
        val p = percent?.takeIf { it in 0..100 } ?: return null to true
        val fullHours = when (formFactor) {
            FormFactor.HEADPHONES -> 24
            FormFactor.EARBUDS, FormFactor.OPEN_EARBUDS -> 8
        }
        return (fullHours * 60 * p / 100) to true
    }
}

/**
 * Wall-clock helpers shared by the alarms and the night schedule. A local time inside a spring-
 * forward gap resolves to the next valid instant (never to an earlier day); a time that happens
 * twice when clocks go back resolves to its first occurrence.
 */
internal fun instantOf(date: LocalDate, time: LocalTime, zone: TimeZone): Long =
    date.atTime(time).toInstant(zone).toEpochMilliseconds()

internal fun dateOf(millis: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

internal fun LocalDate.plusDays(n: Int): LocalDate = plus(n, DateTimeUnit.DAY)

internal fun LocalDate.isoDay(): Int = dayOfWeek.isoDayNumber

/** The first [time] strictly after [nowMillis] on one of [isoDays] (1 = Monday; empty = any day). */
internal fun nextLocalTime(time: LocalTime, isoDays: Set<Int>, nowMillis: Long, zone: TimeZone): Long? {
    var date = dateOf(nowMillis, zone)
    repeat(8) {
        val candidate = instantOf(date, time, zone)
        if (candidate > nowMillis && (isoDays.isEmpty() || date.isoDay() in isoDays)) return candidate
        date = date.plusDays(1)
    }
    return null
}

