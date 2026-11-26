package io.github.langkurst.ruru.android.sleep

import io.github.langkurst.ruru.sleep.NightAction
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepPlanner
import io.github.langkurst.ruru.sleep.SleepTimeline
import kotlinx.datetime.TimeZone

/** What an armed alarm is for. The name is part of the alarm's identity, so keep them. */
enum class ArmKind(val action: String) {
    /** A wake-up alarm rings (a clock alarm: shown in the status bar, exact on every Android that has one). */
    WAKE("io.github.langkurst.ruru.action.RING"),

    /** A snoozed alarm rings again. */
    SNOOZE("io.github.langkurst.ruru.action.RING_SNOOZED"),

    /** A moment inside a night (the timer ends, noise cancelling goes off): a backstop that wakes the service. */
    STAGE("io.github.langkurst.ruru.action.STAGE"),

    /** The night schedule has a step coming: wakes Ruru a little before it. */
    NIGHT("io.github.langkurst.ruru.action.NIGHT"),
}

/** One alarm to be set: [key] tells two alarms of the same [kind] apart (an alarm's id, a stage name). */
data class ArmedAlarm(val kind: ArmKind, val key: String, val atMillis: Long) {
    /** The name it is remembered under, to be cancelled later. */
    val id: String get() = "${kind.name}:$key"
}

/**
 * Which alarms to set for the saved alarms, the running night and the night schedule, and which
 * earlier ones are now stale. All plain arithmetic over the planner in `:core:sleep`, so it is tested
 * on the JVM; [AlarmArmer] only hands the result to Android's AlarmManager.
 */
object AlarmArming {
    /** The wake-up alarms: each enabled alarm's next ring, skipping any [skipUntil] says has been dealt with. */
    fun wake(alarms: List<SleepAlarm>, nowMillis: Long, skipUntil: Map<String, Long>, zone: TimeZone): List<ArmedAlarm> =
        SleepPlanner.ringsToArm(alarms, nowMillis, skipUntil, exactAllowed = true, zone = zone)
            .map { ArmedAlarm(ArmKind.WAKE, it.alarm.id, it.atMillis) }

    /** The two moments of a night worth waking for: noise cancelling going off, and the end. Moments already past are left out. */
    fun stages(timeline: SleepTimeline, nowMillis: Long): List<ArmedAlarm> = buildList {
        timeline.noiseOffAtMillis?.takeIf { it > nowMillis && it < timeline.endsAtMillis }?.let { add(ArmedAlarm(ArmKind.STAGE, "noise", it)) }
        if (timeline.endsAtMillis > nowMillis) add(ArmedAlarm(ArmKind.STAGE, "end", timeline.endsAtMillis))
    }

    /**
     * The night schedule's next step, as an alarm that wakes Ruru [LEAD_MS] early (so the Bluetooth link is up and
     * the runner is waiting when the step is due). A step due now or within the lead is armed for a moment from now.
     */
    fun night(action: NightAction?, nowMillis: Long): ArmedAlarm? {
        action ?: return null
        val at = maxOf(action.atMillis - LEAD_MS, nowMillis + SOON_MS)
        return ArmedAlarm(ArmKind.NIGHT, "step", at)
    }

    /** What to cancel: everything in [previous] (as [ArmedAlarm.id]s) that is not in [now]. */
    fun stale(previous: Set<String>, now: List<ArmedAlarm>): Set<String> = previous - now.map { it.id }.toSet()

    /** A stable request code for a pending intent, so setting the same alarm twice replaces it. */
    fun requestCode(alarm: ArmedAlarm): Int = alarm.id.hashCode() and 0x7fffffff

    /** How long before a night-schedule step Ruru wakes. */
    const val LEAD_MS = 2 * 60_000L

    /** The least wait for an alarm set because a step is already due. */
    const val SOON_MS = 5_000L
}
