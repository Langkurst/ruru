package io.github.langkurst.ruru.sleep

import kotlinx.serialization.json.Json

/**
 * The saved shapes of the Sleep plan and the night schedule: the JSON text the settings store keeps
 * under [SLEEP_PLAN_KEY] and [NIGHT_SCHEDULE_KEY] (both are in the backup's key list). Reads never
 * throw: text that is missing, damaged or from a build too different to read gives the default,
 * and a readable but out-of-range plan is clamped by [SleepPlanner.sanitise]. Names the reader
 * doesn't know (written by a newer build) are ignored, and an enum value it doesn't know falls back
 * to the field's default.
 */
object SleepStorage {
    const val SLEEP_PLAN_KEY = "sleep.plan"
    const val NIGHT_SCHEDULE_KEY = "night.schedule"

    /** Where a runner may keep its [NightProgress]; deliberately not in the backup. */
    const val NIGHT_PROGRESS_KEY = "night.progress"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encodePlan(plan: SleepPlan): String = json.encodeToString(SleepPlan.serializer(), plan)

    fun decodePlan(text: String?): SleepPlan =
        text?.let { runCatching { json.decodeFromString(SleepPlan.serializer(), it) }.getOrNull() }
            ?.let { SleepPlanner.sanitise(it) }
            ?: SleepPlan()

    fun encodeSchedule(schedule: NightSchedule): String = json.encodeToString(NightSchedule.serializer(), schedule)

    fun decodeSchedule(text: String?): NightSchedule =
        text?.let { runCatching { json.decodeFromString(NightSchedule.serializer(), it) }.getOrNull() } ?: NightSchedule()

    fun encodeProgress(progress: NightProgress): String = json.encodeToString(NightProgress.serializer(), progress)

    fun decodeProgress(text: String?): NightProgress =
        text?.let { runCatching { json.decodeFromString(NightProgress.serializer(), it) }.getOrNull() } ?: NightProgress()
}
