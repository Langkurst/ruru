package io.github.langkurst.ruru.app.night

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.core.ModeResolver
import io.github.langkurst.ruru.sleep.DayMode
import io.github.langkurst.ruru.sleep.NightAction
import io.github.langkurst.ruru.sleep.NightSchedule
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The pure parts of the Night page, kept out of the screen so they can be tested: stepping a time of
 * day, the modes on offer, and what the next change is.
 */

/** One hour step. In a 12-hour clock the hour stays in its half of the day (am or pm); [delta] is +1 or -1. */
fun stepHour(time: LocalTime, delta: Int, twelveHour: Boolean): LocalTime {
    val hour = if (twelveHour) {
        val half = (time.hour / 12) * 12
        half + ((time.hour % 12) + delta + 12) % 12
    } else {
        (time.hour + delta + 24) % 24
    }
    return LocalTime(hour, time.minute)
}

/** One minute step of five: to the next multiple of five in the direction of [delta] (+1 or -1), wrapping inside the hour. */
fun stepMinute(time: LocalTime, delta: Int): LocalTime {
    val m = time.minute
    val next = if (delta > 0) (m / 5 + 1) * 5 else if (m % 5 == 0) m - 5 else (m / 5) * 5
    return LocalTime(time.hour, ((next % 60) + 60) % 60)
}

/** Moves [time] to the am or pm half of the day, keeping the hour on the clock face. */
fun withHalf(time: LocalTime, pm: Boolean): LocalTime = LocalTime((time.hour % 12) + if (pm) 12 else 0, time.minute)

/** The hour on a 12-hour clock face: 12, 1 ... 11. */
fun hourOnFace(hour: Int): Int = if (hour % 12 == 0) 12 else hour % 12

/** A mode the person can pick, and the name to show; [label] is null for a built-in meaning the page names in the person's language. */
data class ModeChoice(val key: String, val ref: ModeRef, val label: String?)

/** What two refs have in common when they mean the same mode, whichever headset they were picked on. */
fun modeKey(ref: ModeRef): String =
    if (ref.meaning == ModeMeaning.CUSTOM) "custom:" + (ref.name?.trim()?.lowercase() ?: (ref.slot ?: ref.legacySlot)?.toString().orEmpty())
    else ref.meaning.storedName

/**
 * The modes on offer: the connected headset's own modes (by what they are called there), or Quiet,
 * Aware and Off when no headset has told us its modes. Whatever the schedule already holds stays on
 * the list, so a choice made on another headset is still shown.
 */
fun modeChoices(h: HeadphoneState, chosen: List<ModeRef>): List<ModeChoice> {
    val out = LinkedHashMap<String, ModeChoice>()
    val configured = h.modes.filter { it.isConfigured }
    if (h.isConnected && configured.isNotEmpty()) {
        for (m in configured.sortedBy { it.index }) {
            val ref = ModeResolver.refFor(m, h.connection.deviceAddress)
            out.getOrPut(modeKey(ref)) { ModeChoice(modeKey(ref), ref, m.name.trim().ifEmpty { null }) }
        }
    } else {
        for (meaning in ModeResolver.GENERIC_CHOICES) {
            val ref = ModeRef(meaning)
            out[modeKey(ref)] = ModeChoice(modeKey(ref), ref, null)
        }
    }
    for (ref in chosen) {
        out.getOrPut(modeKey(ref)) {
            ModeChoice(modeKey(ref), ref, ref.name?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
    return out.values.toList()
}

/** What the next scheduled change is, in the pieces the page words. [isoDay] is set when it is not today. */
sealed interface NightPreview {
    val time: LocalTime
    val isoDay: Int?

    /** The night mode goes on. */
    data class ToNight(val mode: ModeRef, override val time: LocalTime, override val isoDay: Int?) : NightPreview

    /** The morning mode goes on ([mode] null means "what it was"). */
    data class ToDay(val mode: ModeRef?, override val time: LocalTime, override val isoDay: Int?) : NightPreview
}

fun previewOf(action: NightAction, schedule: NightSchedule, nowMillis: Long, zone: TimeZone): NightPreview {
    val at = Instant.fromEpochMilliseconds(action.atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    // A change already due (the headphones are not here yet) is shown as today.
    val day = if (action.atMillis <= nowMillis || at.date == today) null else at.date.dayOfWeek.isoDayNumber
    return when (action) {
        is NightAction.ApplyNight -> NightPreview.ToNight(schedule.nightMode, at.time, day)
        is NightAction.ApplyDay -> NightPreview.ToDay((schedule.dayMode as? DayMode.Mode)?.ref, at.time, day)
    }
}
