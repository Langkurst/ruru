package io.github.langkurst.ruru.app.sleep

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Small pure helpers of the Sleep tab, kept out of the screen so they are tested. */

/** How an alarm's repeat days read. */
enum class DaysKind { ONCE, EVERY_DAY, WEEKDAYS, WEEKENDS, SOME }

fun daysKind(days: Set<Int>): DaysKind = when (days.filter { it in 1..7 }.toSet()) {
    emptySet<Int>() -> DaysKind.ONCE
    AlarmDays.everyDay -> DaysKind.EVERY_DAY
    AlarmDays.weekdays -> DaysKind.WEEKDAYS
    AlarmDays.weekend -> DaysKind.WEEKENDS
    else -> DaysKind.SOME
}

/** The time left as "H:MM" (a minute counts as soon as it has started), never negative. */
fun countdownText(remainingMillis: Long): String {
    val minutes = ((remainingMillis.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}

/** The hour and minute of [millis] on the wall clock in [zone]. */
fun clockOf(millis: Long, zone: TimeZone): Pair<Int, Int> {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).time
    return t.hour to t.minute
}

/** The minute marks the "Off after" choice of noise cancelling offers (15, 30 and 60) that fit inside a timer of [durationMinutes]. */
fun noiseOffChoices(durationMinutes: Int): List<Int?> =
    listOf<Int?>(null, 0) + listOf(15, 30, 60).filter { it < durationMinutes } + listOf(durationMinutes)
