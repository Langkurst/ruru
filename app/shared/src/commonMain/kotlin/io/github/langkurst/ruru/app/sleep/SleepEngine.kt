package io.github.langkurst.ruru.app.sleep

import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepNote
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.sleep.SleepSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What plays the night and rings the alarms on this phone: Android's SleepService today, the iPhone's
 * engine later. The Sleep tab only talks to this; it holds no timers of its own, so the night carries
 * on when the screen is off and Ruru is not on screen.
 */
interface SleepEngine {
    /** Idle, a night running, an alarm ringing or snoozed. */
    val session: StateFlow<SleepSessionState>

    /** What tonight's session did, oldest first, for the lines under the timer. */
    val notes: StateFlow<List<SleepNote>>

    /**
     * Whether the wake-up alarms can be armed. False when the phone has not allowed exact alarms (Android 12
     * and older, or after the person took it away): an alarm that is switched on then waits, and the tab says so.
     */
    val alarmsAllowed: StateFlow<Boolean>

    /** Starts a night with [plan]. Does nothing while one is running. */
    fun start(plan: SleepPlan)

    /** Ends the night now: sounds fade out quickly and the headset settings are put back. */
    fun stop()

    /** "+N min": the timer, its fade and the end action move later. */
    fun extend(minutes: Int)

    /** Stops a ringing alarm. */
    fun stopAlarm()

    /** Rings again after the alarm's snooze time. */
    fun snoozeAlarm()

    /** The saved alarms changed (added, edited, switched, deleted): arm them again. */
    fun alarmsChanged(alarms: List<SleepAlarm>)

    /** Opens the phone's own page where exact alarms are allowed; does nothing where there is none. */
    fun openAlarmAccess()
}

/**
 * An engine with nothing behind it: it keeps a session in memory and counts nothing. Demo mode, the
 * snapshots and the tests use it; [set] puts the tab in any state.
 */
class FakeSleepEngine(
    initial: SleepSessionState = SleepSessionState.Idle,
    alarmsAllowed: Boolean = true,
    private val clock: () -> Long = { 0L },
) : SleepEngine {
    private val sessionFlow = MutableStateFlow(initial)
    private val notesFlow = MutableStateFlow<List<SleepNote>>(emptyList())
    private val allowedFlow = MutableStateFlow(alarmsAllowed)
    override val session: StateFlow<SleepSessionState> = sessionFlow.asStateFlow()
    override val notes: StateFlow<List<SleepNote>> = notesFlow.asStateFlow()
    override val alarmsAllowed: StateFlow<Boolean> = allowedFlow.asStateFlow()

    /** What the tab last told the engine to arm. */
    var armed: List<SleepAlarm> = emptyList()
        private set
    var accessOpened = 0
        private set

    fun set(state: SleepSessionState, notes: List<SleepNote> = notesFlow.value) {
        sessionFlow.value = state
        notesFlow.value = notes
    }

    fun setAlarmsAllowed(allowed: Boolean) {
        allowedFlow.value = allowed
    }

    override fun start(plan: SleepPlan) {
        if (sessionFlow.value is SleepSessionState.Idle) sessionFlow.value = SleepSessions.start(plan, clock())
    }

    override fun stop() {
        sessionFlow.value = SleepSessionState.Idle
    }

    override fun extend(minutes: Int) {
        sessionFlow.value = SleepSessions.extend(sessionFlow.value, minutes, clock())
    }

    override fun stopAlarm() {
        sessionFlow.value = SleepSessionState.Idle
    }

    override fun snoozeAlarm() {
        val s = sessionFlow.value
        if (s is SleepSessionState.Ringing) sessionFlow.value = SleepSessions.snooze(s, clock())
    }

    override fun alarmsChanged(alarms: List<SleepAlarm>) {
        armed = alarms
    }

    override fun openAlarmAccess() {
        accessOpened++
    }
}
