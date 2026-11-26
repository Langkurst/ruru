package io.github.langkurst.ruru.sleep

/** Where a running session is. */
enum class SleepPhase { FADING_IN, PLAYING, FADING_OUT, TIMER_ONLY }

/** The moments of one night, in wall-clock milliseconds. */
data class SleepTimeline(
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    /** Sounds start fading here; equal to [endsAtMillis] when there is nothing to fade. */
    val fadeOutStartsAtMillis: Long,
    /** Noise cancelling goes off here; null = leave it. */
    val noiseOffAtMillis: Long?,
) {
    val durationMillis: Long get() = endsAtMillis - startedAtMillis
}

/** One wake alarm's next ring as it is armed: the ring itself and, with a wake-up window, its start. */
data class PlannedRing(val alarm: SleepAlarm, val atMillis: Long, val windowStartMillis: Long?)

data class NextAlarm(val alarmId: String, val atMillis: Long, val wakeWindowStartMillis: Long? = null)

/** One line in tonight's log: what the session did, or why it couldn't. The screens turn [code] into words. */
data class SleepNote(val atMillis: Long, val code: SleepNoteCode, val isProblem: Boolean = false, val detail: String? = null)

/**
 * What the Sleep screens show: idle, a night running, an alarm ringing or snoozed. Plain data;
 * [SleepSessions] holds the transitions so they are tested without a phone.
 */
sealed interface SleepSessionState {
    data object Idle : SleepSessionState

    data class Running(
        val plan: SleepPlan,
        val timeline: SleepTimeline,
        val noiseOffDone: Boolean = false,
        val headsetName: String? = null,
    ) : SleepSessionState {
        val hasSounds: Boolean get() = plan.layers.isNotEmpty()

        fun phaseAt(nowMillis: Long): SleepPhase = SleepPlanner.phase(timeline, hasSounds, nowMillis)
        fun remainingMillis(nowMillis: Long): Long = (timeline.endsAtMillis - nowMillis).coerceAtLeast(0L)
        fun isOver(nowMillis: Long): Boolean = nowMillis >= timeline.endsAtMillis
    }

    data class Ringing(
        val alarmId: String,
        val label: String?,
        val startedAtMillis: Long,
        /** Where it rings now; EARBUDS can turn into BOTH or PHONE when the phone takes over. */
        val output: AlarmOutput,
        /** Set when it rings on the phone although the earbuds were chosen. */
        val phoneFallback: Boolean = false,
        val canSnooze: Boolean = true,
        /** The snooze length for the button ("Snooze 10 min"); null when [canSnooze] is false. */
        val snoozeMinutes: Int? = null,
    ) : SleepSessionState

    data class Snoozed(val alarmId: String, val label: String?, val ringsAtMillis: Long) : SleepSessionState
}

/** The session transitions, pure: the runtimes call these and store what comes back. */
object SleepSessions {
    fun start(plan: SleepPlan, nowMillis: Long, headsetName: String? = null): SleepSessionState.Running {
        val p = SleepPlanner.sanitise(plan)
        return SleepSessionState.Running(p, SleepPlanner.timeline(p, nowMillis), headsetName = headsetName)
    }

    /** "+N min": a stopped or finished night can't be extended. */
    fun extend(session: SleepSessionState, minutes: Int, nowMillis: Long): SleepSessionState {
        if (session !is SleepSessionState.Running || session.isOver(nowMillis) || minutes <= 0) return session
        return session.copy(timeline = SleepPlanner.extended(session.timeline, session.plan, minutes))
    }

    fun noiseOffHappened(session: SleepSessionState): SleepSessionState =
        if (session is SleepSessionState.Running) session.copy(noiseOffDone = true) else session

    /** True when the planned noise-off moment has come and not yet been done. */
    fun noiseOffDue(session: SleepSessionState, nowMillis: Long): Boolean {
        if (session !is SleepSessionState.Running || session.noiseOffDone) return false
        val at = session.timeline.noiseOffAtMillis ?: return false
        return nowMillis >= at
    }

    fun ring(alarm: SleepAlarm, nowMillis: Long, earbudsReachable: Boolean): SleepSessionState.Ringing {
        val toPhone = alarm.output == AlarmOutput.EARBUDS && !earbudsReachable
        return SleepSessionState.Ringing(
            alarmId = alarm.id,
            label = alarm.label,
            startedAtMillis = nowMillis,
            output = if (toPhone) AlarmOutput.PHONE else alarm.output,
            phoneFallback = toPhone,
            canSnooze = alarm.snoozeMinutes != null,
            snoozeMinutes = alarm.snoozeMinutes,
        )
    }

    /**
     * An EARBUDS alarm nobody stopped within [SleepAlarm.PHONE_FALLBACK_SECONDS] moves to the phone
     * as well (both, so the earbuds keep ringing); anything else stays as it is.
     */
    fun escalate(ringing: SleepSessionState.Ringing, nowMillis: Long): SleepSessionState.Ringing {
        val waited = nowMillis - ringing.startedAtMillis
        return if (ringing.output == AlarmOutput.EARBUDS && waited >= SleepAlarm.PHONE_FALLBACK_SECONDS * 1000L) {
            ringing.copy(output = AlarmOutput.BOTH, phoneFallback = true)
        } else ringing
    }

    /** Snoozing a ringing alarm that can't snooze changes nothing. */
    fun snooze(ringing: SleepSessionState.Ringing, nowMillis: Long): SleepSessionState {
        val minutes = ringing.snoozeMinutes?.takeIf { ringing.canSnooze } ?: return ringing
        return SleepSessionState.Snoozed(ringing.alarmId, ringing.label, nowMillis + minutes * SleepPlanner.MINUTE)
    }

    fun dismiss(): SleepSessionState = SleepSessionState.Idle
}
