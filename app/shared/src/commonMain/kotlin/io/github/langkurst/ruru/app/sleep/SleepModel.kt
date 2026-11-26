package io.github.langkurst.ruru.app.sleep

import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.sleep.EndActionRules
import io.github.langkurst.ruru.sleep.EndOutcome
import io.github.langkurst.ruru.sleep.HeadsetAbilities
import io.github.langkurst.ruru.sleep.SavedMix
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepHeadsetActions
import io.github.langkurst.ruru.sleep.SleepLayer
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepPlanner
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.sleep.SleepStorage
import io.github.langkurst.ruru.sleep.sound.SleepSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * The Sleep tab's state: tonight's plan, the alarms and the saved mixes, kept in the settings store
 * (so a backup carries them), and the [engine] that plays the night. Edits are saved at once and
 * the alarms are handed to the engine to arm. Pure Kotlin, so the tab's rules are tested without a screen.
 */
class SleepModel(
    private val settings: SettingsStore,
    val engine: SleepEngine,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val planFlow = MutableStateFlow(SleepStorage.decodePlan(settings.getString(SleepStorage.SLEEP_PLAN_KEY)))
    private val alarmsFlow = MutableStateFlow(SleepStorage.decodeAlarms(settings.getString(SleepStorage.SLEEP_ALARMS_KEY)))
    private val mixesFlow = MutableStateFlow(SleepStorage.decodeMixes(settings.getString(SleepStorage.SLEEP_MIXES_KEY)))

    val plan: StateFlow<SleepPlan> = planFlow.asStateFlow()
    val alarms: StateFlow<List<SleepAlarm>> = alarmsFlow.asStateFlow()
    val mixes: StateFlow<List<SavedMix>> = mixesFlow.asStateFlow()

    fun nowMillis(): Long = now()
    fun timeZone(): TimeZone = zone()

    /** Changes tonight's plan and keeps it for next time; the plan is clamped to what the tab allows. */
    fun updatePlan(change: (SleepPlan) -> SleepPlan) {
        val next = SleepPlanner.sanitise(change(planFlow.value))
        if (next == planFlow.value) return
        planFlow.value = next
        settings.putString(SleepStorage.SLEEP_PLAN_KEY, SleepStorage.encodePlan(next))
    }

    fun setDuration(minutes: Int) = updatePlan { it.copy(durationMinutes = minutes) }

    /** Adds [soundId] to the mix unless it is there or three are already playing. Returns whether it was added. */
    fun addSound(soundId: String): Boolean {
        val plan = planFlow.value
        if (plan.layers.size >= SleepPlan.MAX_LAYERS || plan.layers.any { it.soundId == soundId }) return false
        updatePlan { it.copy(layers = it.layers + SleepLayer(soundId)) }
        return true
    }

    fun removeSound(soundId: String) = updatePlan { p -> p.copy(layers = p.layers.filterNot { it.soundId == soundId }) }

    /** [gain] is 0..1. */
    fun setGain(soundId: String, gain: Float) =
        updatePlan { p -> p.copy(layers = p.layers.map { if (it.soundId == soundId) it.copy(gain = gain) else it }) }

    fun setVolume(volume: Float) = updatePlan { it.copy(volume = volume) }

    // ------------------------------------------------------------------ saved mixes

    /** Keeps the current mix under [name]; a mix with no sound can't be saved. Returns the id it is kept under. */
    fun saveMix(name: String): String? {
        val layers = planFlow.value.layers
        if (layers.isEmpty()) return null
        mixesFlow.value.firstOrNull { it.layers == layers }?.let { return it.id }
        val id = "m" + now()
        saveMixes(mixesFlow.value + SavedMix(id, name.trim().ifEmpty { "Mix" }, layers))
        return id
    }

    fun applyMix(id: String) {
        val mix = mixesFlow.value.firstOrNull { it.id == id } ?: return
        updatePlan { it.copy(layers = mix.layers) }
    }

    fun deleteMix(id: String) = saveMixes(mixesFlow.value.filterNot { it.id == id })

    private fun saveMixes(next: List<SavedMix>) {
        mixesFlow.value = next
        settings.putString(SleepStorage.SLEEP_MIXES_KEY, SleepStorage.encodeMixes(next))
    }

    // ------------------------------------------------------------------ alarms

    /** A new alarm at [hour]:[minute] to edit; not kept until [saveAlarm]. */
    fun newAlarm(hour: Int = 7, minute: Int = 0): SleepAlarm = SleepAlarm("a" + now() + "-" + alarmsFlow.value.size, hour, minute)

    /** Adds the alarm, or replaces the one with the same id. */
    fun saveAlarm(alarm: SleepAlarm) {
        val clean = alarm.copy(hour = alarm.hour.coerceIn(0, 23), minute = alarm.minute.coerceIn(0, 59), days = alarm.days.filter { it in 1..7 }.toSet())
        val list = alarmsFlow.value
        saveAlarms(if (list.any { it.id == clean.id }) list.map { if (it.id == clean.id) clean else it } else list + clean)
    }

    fun deleteAlarm(id: String) = saveAlarms(alarmsFlow.value.filterNot { it.id == id })

    fun setAlarmOn(id: String, on: Boolean) = saveAlarms(alarmsFlow.value.map { if (it.id == id) it.copy(enabled = on) else it })

    private fun saveAlarms(next: List<SleepAlarm>) {
        alarmsFlow.value = next
        settings.putString(SleepStorage.SLEEP_ALARMS_KEY, SleepStorage.encodeAlarms(next))
        engine.alarmsChanged(next)
    }

    /** Hands the saved alarms to the engine (when the app starts, so they are armed even when nothing was edited). */
    fun armAlarms() = engine.alarmsChanged(alarmsFlow.value)

    // ------------------------------------------------------------------ the night

    /** What the headphones in [h] can do tonight (noise off, switching off, ...). A phone has no link to disconnect with. */
    fun abilities(h: HeadphoneState): HeadsetAbilities = SleepHeadsetActions({ error("reads only") }).abilities(h)

    /** The lines of the "Tonight" panel for a night started now. */
    fun tonight(abilities: HeadsetAbilities): Tonight {
        val plan = planFlow.value
        val t = now()
        val next = SleepPlanner.nextAlarm(alarmsFlow.value, t, zone())
        val end = EndActionRules.resolve(plan, next?.first, abilities.canReconnect)
        return Tonight(
            durationMinutes = plan.durationMinutes,
            soundIds = plan.layers.map { it.soundId },
            fadeMinutes = plan.fadeOutMinutes,
            endsAtMillis = t + plan.durationMinutes * SleepPlanner.MINUTE,
            nextAlarm = next?.first,
            nextAlarmAtMillis = next?.second,
            endOutcome = EndActionRules.outcome(end.action, abilities),
            alarmMovesToPhone = end.alarmMovesToPhone,
        )
    }

    fun start() {
        if (engine.session.value is SleepSessionState.Idle) engine.start(planFlow.value)
    }
}

/** What tonight will do, as the panel at the top of the tab says it. */
data class Tonight(
    val durationMinutes: Int,
    val soundIds: List<String>,
    val fadeMinutes: Int,
    val endsAtMillis: Long,
    val nextAlarm: SleepAlarm?,
    val nextAlarmAtMillis: Long?,
    val endOutcome: EndOutcome,
    val alarmMovesToPhone: Boolean,
)

/** The timer choices on the chips: 15 minutes up to 6 hours. */
val SleepDurationChoices: List<Int> = listOf(SleepPlan.MIN_MINUTES) + SleepPlan.DURATION_CHOICES

/** The sounds on offer, in the order of the picker. */
val SleepSoundChoices: List<SleepSound> = SleepSound.entries

/** Sets of weekdays (ISO numbers, Monday = 1) the alarm editor offers by name. */
object AlarmDays {
    val everyDay: Set<Int> = (1..7).toSet()
    val weekdays: Set<Int> = (1..5).toSet()
    val weekend: Set<Int> = setOf(6, 7)
}
