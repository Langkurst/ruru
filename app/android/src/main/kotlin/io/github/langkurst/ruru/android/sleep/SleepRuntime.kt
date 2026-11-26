package io.github.langkurst.ruru.android.sleep

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.app.sleep.SleepEngine
import io.github.langkurst.ruru.sleep.EndActionRules
import io.github.langkurst.ruru.sleep.RestoreRecord
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepEndAction
import io.github.langkurst.ruru.sleep.SleepHeadsetActions
import io.github.langkurst.ruru.sleep.SleepNote
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepPlanner
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.sleep.SleepStorage
import io.github.langkurst.ruru.sleep.StepNote
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.sleep.HeadsetCommandRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone

/**
 * What the Sleep tab, the service and the receivers share inside Ruru's process: the machine, tonight's log,
 * the recorded sounds, the alarm setter and the headset's night steps. Built once by the application.
 */
class SleepRuntime(
    private val context: Context,
    private val settings: SettingsStore,
    private val model: () -> AppModel,
    val clock: () -> Long = { System.currentTimeMillis() },
) {
    val machine = SleepMachine(clock)
    val recordings = AndroidRecordings().also { it.install() }
    val armer = AlarmArmer(context, settings)

    private val notesFlow = MutableStateFlow<List<SleepNote>>(emptyList())
    val notes: StateFlow<List<SleepNote>> = notesFlow.asStateFlow()

    /** The saved alarms, read from the store each time so a change made anywhere is seen. */
    fun alarms(): List<SleepAlarm> = SleepStorage.decodeAlarms(settings.getString(SleepStorage.SLEEP_ALARMS_KEY))

    fun plan(): SleepPlan = SleepStorage.decodePlan(settings.getString(SleepStorage.SLEEP_PLAN_KEY))

    fun zone(): TimeZone = TimeZone.currentSystemDefault()

    /** Sets the wake-up alarms again from the saved ones (after an edit, a restart, a time change). */
    fun rearmWake() {
        armer.sync(setOf(ArmKind.WAKE), AlarmArming.wake(alarms(), clock(), emptyMap(), zone()))
    }

    fun clearNotes() {
        notesFlow.value = emptyList()
    }

    private fun note(n: StepNote) {
        notesFlow.update { it + SleepNote(clock(), n.code, n.isProblem, n.detail) }
    }

    // ------------------------------------------------------------------ the headset's half of the night

    private val controller get() = model().controller.value

    private fun actions() = SleepHeadsetActions(
        HeadsetCommandRunner { command: HeadsetCommand ->
            // The controller says false when the headphones refused; the night turns that into a note.
            if (!controller.send(command)) throw IllegalStateException("The headphones did not take ${command::class.simpleName}")
        },
        // Android has no way to disconnect a Bluetooth audio device from an app, so there is no link.
        link = null,
    )

    private fun record(): RestoreRecord = SleepStorage.decodeRestore(settings.getString(SleepStorage.SLEEP_RESTORE_KEY))

    private fun save(record: RestoreRecord) {
        if (record.isEmpty) settings.remove(SleepStorage.SLEEP_RESTORE_KEY)
        else settings.putString(SleepStorage.SLEEP_RESTORE_KEY, SleepStorage.encodeRestore(record))
    }

    /** The start of a night: touch lock and prompts off, noise cancelling off when the plan says "at the start". */
    suspend fun beginHeadset(plan: SleepPlan) {
        val a = actions()
        val notes = mutableListOf<StepNote>()
        val s = controller.state.value.headset
        var r = RestoreRecord(address = s.connection.deviceAddress)
        if (plan.nightExtras.isNotEmpty()) r = a.applyNightExtras(plan, s, r, notes)
        if (plan.noiseOffAfterMinutes == 0) r = a.noiseOff(controller.state.value.headset, r, notes)
        save(r)
        notes.forEach(::note)
    }

    suspend fun noiseOffNow() {
        val notes = mutableListOf<StepNote>()
        save(actions().noiseOff(controller.state.value.headset, record(), notes))
        notes.forEach(::note)
    }

    /**
     * The end of a night: the end action (unless the person stopped it themselves), then the settings put back.
     * When the headphones were sent away, putting back waits until they connect again ([restoreWhenReady]).
     */
    suspend fun endHeadset(plan: SleepPlan, byPerson: Boolean) {
        val a = actions()
        val notes = mutableListOf<StepNote>()
        var away = false
        if (!byPerson) {
            val next = SleepPlanner.nextAlarm(alarms(), clock(), zone())?.first
            val end = EndActionRules.resolve(plan, next, canReconnect = false)
            if (end.action != SleepEndAction.STOP_SOUNDS) away = a.endAction(end.action, controller.state.value.headset, notes)
        }
        var r = record()
        if (!away) r = a.restore(r, controller.state.value.headset, notes)
        save(r.copy(due = r.headsetPart))
        notes.forEach(::note)
    }

    /** Called whenever the headphones report: puts back what a night changed, once they are connected and read. */
    suspend fun restoreWhenReady() {
        if (machine.state.value is SleepSessionState.Running) return
        val r = record()
        if (!r.headsetPart || !r.due) return
        val a = actions()
        if (!a.readyToRestore(r, controller.state.value.headset)) return
        val notes = mutableListOf<StepNote>()
        save(a.restore(r, controller.state.value.headset, notes))
        notes.forEach(::note)
    }
}

/** The Sleep tab's [SleepEngine] on Android: every verb is an intent to [SleepService]; the state is the machine's. */
class AndroidSleepEngine(private val context: Context, private val runtime: SleepRuntime) : SleepEngine {
    override val session: StateFlow<SleepSessionState> = runtime.machine.state
    override val notes: StateFlow<List<SleepNote>> = runtime.notes

    // Wake-up alarms are clock alarms, which Android allows without any permission, so they are always allowed.
    override val alarmsAllowed: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override fun start(plan: SleepPlan) = SleepService.send(context, SleepService.ACTION_START)
    override fun stop() = SleepService.send(context, SleepService.ACTION_STOP)
    override fun extend(minutes: Int) = SleepService.send(context, SleepService.ACTION_EXTEND) { putExtra(SleepService.EXTRA_MINUTES, minutes) }
    override fun stopAlarm() = SleepService.send(context, SleepService.ACTION_STOP_ALARM)
    override fun snoozeAlarm() = SleepService.send(context, SleepService.ACTION_SNOOZE)
    override fun alarmsChanged(alarms: List<SleepAlarm>) = runtime.rearmWake()

    override fun openAlarmAccess() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
