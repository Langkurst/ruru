package io.github.langkurst.ruru.sleep

import kotlinx.serialization.builtins.ListSerializer
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

    /** The wake-up alarms (a JSON list of [SleepAlarm]) and the saved mixes (a JSON list of [SavedMix]); both are in the backup. */
    const val SLEEP_ALARMS_KEY = "sleep.alarms"
    const val SLEEP_MIXES_KEY = "sleep.mixes"

    /** What a night changed on the headset and still has to put back ([RestoreRecord]); deliberately not in the backup. */
    const val SLEEP_RESTORE_KEY = "sleep.restore"

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

    private val alarmList = ListSerializer(SleepAlarm.serializer())
    private val mixList = ListSerializer(SavedMix.serializer())

    fun encodeAlarms(alarms: List<SleepAlarm>): String = json.encodeToString(alarmList, alarms)

    /** The saved alarms; an alarm with an out-of-range time or a repeated id is dropped, never the whole list. */
    fun decodeAlarms(text: String?): List<SleepAlarm> =
        text?.let { runCatching { json.decodeFromString(alarmList, it) }.getOrNull() }.orEmpty()
            .filter { it.hour in 0..23 && it.minute in 0..59 && it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { it.copy(days = it.days.filter { d -> d in 1..7 }.toSet()) }

    fun encodeMixes(mixes: List<SavedMix>): String = json.encodeToString(mixList, mixes)

    fun decodeMixes(text: String?): List<SavedMix> =
        text?.let { runCatching { json.decodeFromString(mixList, it) }.getOrNull() }.orEmpty()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { it.copy(layers = it.layers.take(SleepPlan.MAX_LAYERS).map { l -> l.copy(gain = l.gain.coerceIn(0f, 1f)) }) }

    fun encodeRestore(record: RestoreRecord): String = json.encodeToString(RestoreRecord.serializer(), record)

    fun decodeRestore(text: String?): RestoreRecord =
        text?.let { runCatching { json.decodeFromString(RestoreRecord.serializer(), it) }.getOrNull() } ?: RestoreRecord()
}
