package io.github.langkurst.ruru.android.sleep

import io.github.langkurst.ruru.sleep.AlarmOutput
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepEndAction
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepPlanner
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.sleep.SleepSessions
import io.github.langkurst.ruru.sleep.SleepTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the service has to do because the machine moved. Plain data: the service turns each into sound, notifications and headset commands. */
sealed interface SleepEffect {
    /** The night begins: play the plan's sounds (if any) and change the headset for the night. */
    data class BeginNight(val plan: SleepPlan, val timeline: SleepTimeline) : SleepEffect

    /** The timeline moved ("+N min"). */
    data class TimelineMoved(val timeline: SleepTimeline) : SleepEffect

    /** The noise-cancelling moment of the plan has come. */
    data object NoiseOff : SleepEffect

    /** The night is over (timer ran out, or the person stopped it): stop the sounds, do the end action, put settings back. */
    data class EndNight(val action: SleepEndAction, val byPerson: Boolean) : SleepEffect

    /** An alarm rings now, in [output]. [phoneFallback] is set when the earbuds could not be reached. */
    data class Ring(val alarm: SleepAlarm, val output: AlarmOutput, val phoneFallback: Boolean) : SleepEffect

    /** The earbuds alarm was not stopped in time: the phone rings too. */
    data class AlsoRingOnPhone(val alarm: SleepAlarm) : SleepEffect

    /** Silence the alarm (stopped, snoozed or run out) and close its notification. */
    data object StopRinging : SleepEffect

    /** Ring again at [atMillis] (the snooze). */
    data class ArmSnooze(val alarmId: String, val atMillis: Long) : SleepEffect

    /** Nothing is left to do: the service can stop. */
    data object Finished : SleepEffect
}

/**
 * The sleep service's rules without Android: the state of the night, and what each moment asks of
 * the service. The service calls the verbs, runs the effects they return and calls [tick] about
 * twice a second. Pure, so it is tested without a phone.
 */
class SleepMachine(private val clock: () -> Long) {
    private val current = MutableStateFlow<SleepSessionState>(SleepSessionState.Idle)
    val state: StateFlow<SleepSessionState> = current.asStateFlow()

    /** The alarm that is ringing or snoozed, kept for the snooze and the escalation. */
    private var alarm: SleepAlarm? = null

    /** The night's plan while it runs, for the end action. */
    private var plan: SleepPlan? = null

    /** Begins a night. [lengthMillis] overrides the plan's length (the emulator demo runs one minute); null uses the plan. */
    fun start(plan: SleepPlan, headsetName: String? = null, lengthMillis: Long? = null): List<SleepEffect> {
        if (current.value is SleepSessionState.Running) return emptyList()
        val now = clock()
        var running = SleepSessions.start(plan, now, headsetName)
        if (lengthMillis != null) {
            val ends = now + lengthMillis
            val fade = minOf(running.plan.fadeOutMinutes * SleepPlanner.MINUTE, lengthMillis / 2)
            running = running.copy(timeline = SleepTimeline(now, ends, ends - fade, null))
        }
        this.plan = running.plan
        this.alarm = null
        current.value = running
        return listOf(SleepEffect.BeginNight(running.plan, running.timeline))
    }

    fun extend(minutes: Int): List<SleepEffect> {
        val before = current.value
        val after = SleepSessions.extend(before, minutes, clock())
        if (after == before || after !is SleepSessionState.Running) return emptyList()
        current.value = after
        return listOf(SleepEffect.TimelineMoved(after.timeline))
    }

    /** The person pressed Stop on the night. */
    fun stop(): List<SleepEffect> {
        val s = current.value as? SleepSessionState.Running ?: return emptyList()
        current.value = SleepSessionState.Idle
        return listOf(SleepEffect.EndNight(SleepEndAction.STOP_SOUNDS, byPerson = true), SleepEffect.Finished)
    }

    /** Called about twice a second by the service. */
    fun tick(): List<SleepEffect> {
        val now = clock()
        return when (val s = current.value) {
            is SleepSessionState.Running -> {
                val out = ArrayList<SleepEffect>(2)
                if (SleepSessions.noiseOffDue(s, now)) {
                    current.value = SleepSessions.noiseOffHappened(s)
                    out += SleepEffect.NoiseOff
                }
                if (s.isOver(now)) {
                    current.value = SleepSessionState.Idle
                    out += SleepEffect.EndNight(s.plan.endAction, byPerson = false)
                    out += SleepEffect.Finished
                }
                out
            }
            is SleepSessionState.Ringing -> {
                val out = ArrayList<SleepEffect>(2)
                val a = alarm
                val escalated = SleepSessions.escalate(s, now)
                if (escalated != s) {
                    current.value = escalated
                    if (a != null) out += SleepEffect.AlsoRingOnPhone(a)
                }
                if (now - s.startedAtMillis >= RING_LIMIT_MS) {
                    // Nobody answered for a long time: the alarm gives up, as a clock does.
                    current.value = SleepSessionState.Idle
                    alarm = null
                    out += SleepEffect.StopRinging
                    out += SleepEffect.Finished
                }
                out
            }
            is SleepSessionState.Snoozed, SleepSessionState.Idle -> emptyList()
        }
    }

    /** [alarm] is due. A night that is still running ends first (its sounds stop, the settings are put back). */
    fun ring(alarm: SleepAlarm, earbudsReachable: Boolean): List<SleepEffect> {
        val out = ArrayList<SleepEffect>(3)
        val before = current.value
        if (before is SleepSessionState.Running) out += SleepEffect.EndNight(SleepEndAction.STOP_SOUNDS, byPerson = false)
        val ringing = SleepSessions.ring(alarm, clock(), earbudsReachable)
        this.alarm = alarm
        current.value = ringing
        out += SleepEffect.Ring(alarm, ringing.output, ringing.phoneFallback)
        return out
    }

    /** The person pressed Snooze. */
    fun snooze(): List<SleepEffect> {
        val s = current.value as? SleepSessionState.Ringing ?: return emptyList()
        val next = SleepSessions.snooze(s, clock())
        if (next !is SleepSessionState.Snoozed) return emptyList()
        current.value = next
        return listOf(SleepEffect.StopRinging, SleepEffect.ArmSnooze(next.alarmId, next.ringsAtMillis), SleepEffect.Finished)
    }

    /** The person pressed Stop on a ringing or snoozed alarm. */
    fun dismiss(): List<SleepEffect> {
        val s = current.value
        if (s !is SleepSessionState.Ringing && s !is SleepSessionState.Snoozed) return emptyList()
        current.value = SleepSessions.dismiss()
        alarm = null
        return listOf(SleepEffect.StopRinging, SleepEffect.Finished)
    }

    /** The service is going away (killed, or Stop in its notification): whatever was running is over. */
    fun reset() {
        current.value = SleepSessionState.Idle
        alarm = null
        plan = null
    }

    companion object {
        /** An alarm nobody answers stops by itself after this long. */
        const val RING_LIMIT_MS = 10 * 60_000L
    }
}
