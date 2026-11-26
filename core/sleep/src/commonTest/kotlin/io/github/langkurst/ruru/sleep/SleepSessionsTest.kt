package io.github.langkurst.ruru.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SleepSessionsTest {
    private val min = SleepPlanner.MINUTE

    @Test
    fun `start sanitises the plan and sets the timeline`() {
        val s = SleepSessions.start(SleepPlan(durationMinutes = 5), nowMillis = 1_000)
        assertEquals(SleepPlan.MIN_MINUTES, s.plan.durationMinutes)
        assertEquals(1_000 + 15 * min, s.timeline.endsAtMillis)
        assertEquals(SleepPhase.FADING_IN, s.phaseAt(2_000))
    }

    @Test
    fun `extend moves the end of a running night, not a finished one`() {
        val s = SleepSessions.start(SleepPlan(durationMinutes = 60), 0)
        val longer = SleepSessions.extend(s, 15, nowMillis = 10 * min) as SleepSessionState.Running
        assertEquals(75 * min, longer.timeline.endsAtMillis)
        assertEquals(s, SleepSessions.extend(s, 15, nowMillis = 61 * min))
        assertEquals(SleepSessionState.Idle, SleepSessions.extend(SleepSessionState.Idle, 15, 0))
    }

    @Test
    fun `noise off falls due once`() {
        val s = SleepSessions.start(SleepPlan(durationMinutes = 60, noiseOffAfterMinutes = 15), 0)
        assertFalse(SleepSessions.noiseOffDue(s, 10 * min))
        assertTrue(SleepSessions.noiseOffDue(s, 15 * min))
        assertFalse(SleepSessions.noiseOffDue(SleepSessions.noiseOffHappened(s), 20 * min))
    }

    @Test
    fun `an earbud alarm with no earbuds rings on the phone and says so`() {
        val alarm = SleepAlarm("a", 7, 0, output = AlarmOutput.EARBUDS)
        val gone = SleepSessions.ring(alarm, 0, earbudsReachable = false)
        assertEquals(AlarmOutput.PHONE, gone.output)
        assertTrue(gone.phoneFallback)
        val here = SleepSessions.ring(alarm, 0, earbudsReachable = true)
        assertEquals(AlarmOutput.EARBUDS, here.output)
        assertFalse(here.phoneFallback)
    }

    @Test
    fun `nobody stopping an earbud alarm within a minute adds the phone`() {
        val ringing = SleepSessions.ring(SleepAlarm("a", 7, 0), 0, true)
        assertEquals(AlarmOutput.EARBUDS, SleepSessions.escalate(ringing, 59_000).output)
        val later = SleepSessions.escalate(ringing, 60_000)
        assertEquals(AlarmOutput.BOTH, later.output)
        assertTrue(later.phoneFallback)
        val phone = SleepSessions.ring(SleepAlarm("b", 7, 0, output = AlarmOutput.PHONE), 0, true)
        assertEquals(AlarmOutput.PHONE, SleepSessions.escalate(phone, 120_000).output)
    }

    @Test
    fun `snooze rings again later, and an alarm without snooze can't`() {
        val ringing = SleepSessions.ring(SleepAlarm("a", 7, 0, snoozeMinutes = 10), 1_000, true)
        val snoozed = assertIs<SleepSessionState.Snoozed>(SleepSessions.snooze(ringing, 1_000))
        assertEquals(1_000 + 10 * min, snoozed.ringsAtMillis)
        val none = SleepSessions.ring(SleepAlarm("a", 7, 0, snoozeMinutes = null), 0, true)
        assertEquals(none, SleepSessions.snooze(none, 0))
        assertEquals(SleepSessionState.Idle, SleepSessions.dismiss())
    }
}
