package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.core.PlaybackState
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.sleep.DayMode
import io.github.langkurst.ruru.sleep.NightSchedule
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import kotlin.time.Instant

/** One thing the hardware check can change and put back. */
internal enum class Step(val flag: String) {
    MODE("mode"), BASS("bass"), FAVOURITES("favourites"), VOLUME("volume"),

    /** The night schedule, set to begin in about a minute, switches the mode and then puts it back. It takes about three minutes, so only `--only=night` runs it. */
    NIGHT("night"),
}

/**
 * Which steps a run does. Default: mode and bass. `--all` adds favourites and volume (with pause/play), not the night step.
 * `--only=<step>` runs that one step alone. Null (with [error] set) when `--only` names no step.
 */
internal class StepChoice(val steps: Set<Step>, val error: String? = null)

internal fun chooseSteps(args: List<String>): StepChoice {
    val only = args.firstOrNull { it.startsWith("--only=") }?.removePrefix("--only=")?.trim()?.lowercase()
    if (only != null) {
        val step = Step.entries.firstOrNull { it.flag == only }
            ?: return StepChoice(emptySet(), "--only=$only is not a step (use ${Step.entries.joinToString("|") { it.flag }})")
        return StepChoice(setOf(step))
    }
    return StepChoice(if ("--all" in args) Step.entries.toSet() - Step.NIGHT else setOf(Step.MODE, Step.BASS))
}

/** The night step's schedule: a two-minute window that begins at the next whole minute at least 20 s away, every night of the week. */
internal class NightPlan(val schedule: NightSchedule, val bedMillis: Long, val wakeMillis: Long)

internal fun planNightSchedule(nowMillis: Long, zone: TimeZone, nightMode: ModeRef): NightPlan {
    val minute = 60_000L
    val bed = ((nowMillis + 20_000L) / minute + 1) * minute
    val wake = bed + 2 * minute
    fun clock(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).time
    val schedule = NightSchedule(
        on = true, bedtime = clock(bed), wake = clock(wake), days = (1..7).toSet(),
        nightMode = nightMode, dayMode = DayMode.WhatItWas,
    )
    return NightPlan(schedule, bed, wake)
}

/** The favourite set before and while a test changes it. */
internal data class FavouritePlan(val original: Set<Int>, val changed: Set<Int>, val dropped: Int)

/** Un-favourite one favourite that is not the current mode; null when favourites are not reported or fewer than two are set. */
internal fun planFavourites(h: HeadphoneState): FavouritePlan? {
    if (!h.capabilities.favoriteModes) return null
    val favs = h.modes.filter { it.isFavorite }.map { it.index }.toSet()
    if (favs.size < 2) return null
    val drop = favs.sorted().firstOrNull { it != h.currentModeIndex } ?: return null
    return FavouritePlan(favs, favs - drop, drop)
}

/** Volume +1, or -1 when already at the top; null when the headphones do not report both the level and the top step. */
internal fun planVolume(h: HeadphoneState): Pair<Int, Int>? {
    val v = h.volume ?: return null
    val max = h.volumeMax ?: return null
    if (max <= 0) return null
    return v to (if (v < max) v + 1 else v - 1)
}

/** Pause and play are only tested when both are supported and something is playing right now. */
internal fun canTestPlayback(h: HeadphoneState): Boolean =
    TransportControl.PAUSE in h.supportedTransportControls &&
        TransportControl.PLAY in h.supportedTransportControls &&
        h.playback?.state == PlaybackState.PLAYING

internal fun favouriteSet(h: HeadphoneState): Set<Int> = h.modes.filter { it.isFavorite }.map { it.index }.toSet()

/**
 * What a run is about to change, as "original>changed" pairs. Kept in a file so that a later run
 * can undo exactly this if the run is cut short.
 */
internal class PlannedChanges(
    private val mode: Int?,
    private val modeChangedTo: Int?,
    private val eq: HeadphoneEq?,
    private val eqChangedTo: HeadphoneEq?,
    private val favourites: Set<Int>? = null,
    private val favouritesChangedTo: Set<Int>? = null,
    private val volume: Int? = null,
    private val volumeChangedTo: Int? = null,
    /** True when the run planned to pause what was playing. */
    private val paused: Boolean = false,
) {
    /** The original mode, if [now] still shows the one that run switched to. Anything else was chosen since. */
    fun modeToPutBack(now: HeadphoneState): Int? =
        mode?.takeIf { modeChangedTo != null && now.currentModeIndex == modeChangedTo }

    /** The original equaliser, if [now] still shows the one that run set. Anything else was chosen since. */
    fun eqToPutBack(now: HeadphoneState): HeadphoneEq? =
        eq?.takeIf { eqChangedTo != null && now.eq == eqChangedTo }

    /** The original favourites, if [now] still shows the set that run left. */
    fun favouritesToPutBack(now: HeadphoneState): Set<Int>? =
        favourites?.takeIf { favouritesChangedTo != null && favouriteSet(now) == favouritesChangedTo }

    /** The original volume, if [now] still shows the one that run set. */
    fun volumeToPutBack(now: HeadphoneState): Int? =
        volume?.takeIf { volumeChangedTo != null && now.volume == volumeChangedTo }

    /** True when that run paused playback and [now] is still paused (and play is supported). */
    fun playToPutBack(now: HeadphoneState): Boolean =
        paused && now.playback?.state == PlaybackState.PAUSED && TransportControl.PLAY in now.supportedTransportControls

    fun text(): String = listOfNotNull(
        if (mode != null && modeChangedTo != null) "mode=$mode>$modeChangedTo" else null,
        if (eq != null && eqChangedTo != null) "eq=${eq.text()}>${eqChangedTo.text()}" else null,
        if (favourites != null && favouritesChangedTo != null) "fav=${favourites.text()}>${favouritesChangedTo.text()}" else null,
        if (volume != null && volumeChangedTo != null) "vol=$volume>$volumeChangedTo" else null,
        if (paused) "playback=paused" else null,
    ).joinToString("") { it + "\n" }

    fun write(file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(text())
    }

    companion object {
        fun read(file: File): PlannedChanges? = if (file.isFile) parse(file.readText()) else null

        fun parse(text: String): PlannedChanges {
            val values = text.lines()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }
            val mode = values["mode"]?.split('>')?.map { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
            val eq = values["eq"]?.split('>')?.map { eqOf(it) }?.takeIf { it.size == 2 }
            val fav = values["fav"]?.split('>')?.map { indicesOf(it) }?.takeIf { it.size == 2 && null !in it }
            val vol = values["vol"]?.split('>')?.map { it.trim().toIntOrNull() }?.takeIf { it.size == 2 && null !in it }
            return PlannedChanges(
                mode?.get(0), mode?.get(1), eq?.get(0), eq?.get(1),
                fav?.get(0), fav?.get(1), vol?.get(0), vol?.get(1),
                paused = values["playback"] == "paused",
            )
        }

        private fun eqOf(text: String): HeadphoneEq? =
            text.split(',').map { it.trim().toIntOrNull() }
                .takeIf { it.size == 3 && null !in it }
                ?.let { HeadphoneEq(it[0]!!, it[1]!!, it[2]!!) }

        private fun indicesOf(text: String): Set<Int>? =
            text.split(',').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() }
                .takeIf { null !in it }?.map { it!! }?.toSet()
    }
}

private fun HeadphoneEq.text() = "$bass,$mid,$treble"
private fun Set<Int>.text() = sorted().joinToString(",")
