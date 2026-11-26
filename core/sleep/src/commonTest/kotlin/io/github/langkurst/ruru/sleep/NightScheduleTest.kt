package io.github.langkurst.ruru.sleep

import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightScheduleTest {
    private val auckland = TimeZone.of("Pacific/Auckland")
    private val london = TimeZone.of("Europe/London")
    private val min = SleepPlanner.MINUTE

    private val everyNight = NightSchedule(
        on = true,
        bedtime = LocalTime(22, 30),
        wake = LocalTime(7, 0),
        nightMode = ModeRef(ModeMeaning.QUIET),
        dayMode = DayMode.WhatItWas,
    )

    private fun headset(current: Int, connected: Boolean = true) = HeadphoneState(
        connection = ConnectionState(if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED, "Buds", "00:11:22:33:44:55"),
        modes = listOf(
            ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(2, "Off", isPreset = true, isEditable = false, isConfigured = true),
        ),
        currentModeIndex = current,
    )

    // ---- The window ----------------------------------------------------------------------------

    @Test
    fun `a night that crosses midnight starts today and ends tomorrow morning`() {
        // 2026-10-30 is a Friday.
        val w = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 30), london)!!
        assertEquals(at(london, 2026, 10, 30, 22, 30), w.startMillis)
        assertEquals(at(london, 2026, 10, 31, 7, 0), w.endMillis)
    }

    @Test
    fun `a daytime window ends the same day`() {
        val nap = everyNight.copy(bedtime = LocalTime(13, 0), wake = LocalTime(15, 0))
        val w = NightScheduler.windowFor(nap, LocalDate(2026, 10, 30), london)!!
        assertEquals(at(london, 2026, 10, 30, 15, 0), w.endMillis)
    }

    @Test
    fun `days name the day the night begins on`() {
        val fridayOnly = everyNight.copy(days = setOf(5))
        // Saturday 01:00 is inside Friday's night.
        assertNotNull(NightScheduler.windowAt(fridayOnly, at(london, 2026, 10, 31, 1, 0), london))
        // Saturday 23:00 would be Saturday's night, which isn't scheduled.
        assertNull(NightScheduler.windowAt(fridayOnly, at(london, 2026, 10, 31, 23, 0), london))
        // Friday 12:00 is between nights.
        assertNull(NightScheduler.windowAt(fridayOnly, at(london, 2026, 10, 30, 12, 0), london))
        // From Saturday the next night is Friday week.
        val next = NightScheduler.nextWindow(fridayOnly, at(london, 2026, 10, 31, 23, 0), london)!!
        assertEquals(at(london, 2026, 11, 6, 22, 30), next.startMillis)
    }

    @Test
    fun `equal times, no days or off all mean no window`() {
        assertNull(NightScheduler.windowFor(everyNight.copy(wake = everyNight.bedtime), LocalDate(2026, 10, 30), london))
        assertNull(NightScheduler.windowFor(everyNight.copy(days = emptySet()), LocalDate(2026, 10, 30), london))
        assertNull(NightScheduler.windowFor(everyNight.copy(on = false), LocalDate(2026, 10, 30), london))
        assertNull(NightScheduler.nextAction(everyNight.copy(on = false), NightProgress(), 0, london))
    }

    @Test
    fun `clocks going forward make the night an hour shorter in real time`() {
        // Auckland: 02:00 on 2026-09-27 becomes 03:00.
        val w = NightScheduler.windowFor(everyNight, LocalDate(2026, 9, 26), auckland)!!
        assertEquals(at(auckland, 2026, 9, 26, 22, 30), w.startMillis)
        assertEquals(at(auckland, 2026, 9, 27, 7, 0), w.endMillis)
        assertEquals((8 * 60 + 30 - 60) * min, w.endMillis - w.startMillis)
    }

    @Test
    fun `clocks going back make the night an hour longer in real time`() {
        // London: 02:00 on 2026-10-25 becomes 01:00.
        val w = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 24), london)!!
        assertEquals(at(london, 2026, 10, 25, 7, 0), w.endMillis)
        assertEquals((8 * 60 + 30 + 60) * min, w.endMillis - w.startMillis)
    }

    @Test
    fun `a bedtime inside the spring-forward gap still starts that night`() {
        val late = everyNight.copy(bedtime = LocalTime(2, 30), wake = LocalTime(7, 0))
        val w = NightScheduler.windowFor(late, LocalDate(2026, 9, 27), auckland)!!
        assertEquals(at(auckland, 2026, 9, 27, 3, 30), w.startMillis)
        assertTrue(w.endMillis > w.startMillis)
    }

    @Test
    fun `the window follows the zone, not a fixed offset`() {
        val a = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 30), auckland)!!
        val l = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 30), london)!!
        assertTrue(a.startMillis != l.startMillis)
    }

    // ---- What to do next -----------------------------------------------------------------------

    @Test
    fun `before bedtime the next action is the night mode at bedtime`() {
        val now = at(london, 2026, 10, 30, 18, 0)
        val action = assertIs<NightAction.ApplyNight>(NightScheduler.nextAction(everyNight, NightProgress(), now, london))
        assertEquals(at(london, 2026, 10, 30, 22, 30), action.atMillis)
        assertTrue(action.atMillis > now)
    }

    @Test
    fun `connecting during the night applies the night mode once`() {
        val now = at(london, 2026, 10, 31, 3, 0)
        val first = assertIs<NightAction.ApplyNight>(NightScheduler.nextAction(everyNight, NightProgress(), now, london))
        assertTrue(first.atMillis <= now, "bedtime has passed, so it is due at once")
        val done = NightScheduler.apply(first, everyNight, NightProgress(), headset(current = 1))
        assertEquals(NightNote.NIGHT_APPLIED, done.note)
        assertEquals(HeadsetCommand.SetMode(0), done.command)
        assertEquals(ModeMeaning.AWARE, done.progress.whatItWas?.meaning)

        // Connecting again at 04:00, after the person changed the mode by hand: nothing is reapplied.
        val again = NightScheduler.nextAction(everyNight, done.progress, at(london, 2026, 10, 31, 4, 0), london)
        val day = assertIs<NightAction.ApplyDay>(again)
        assertEquals(at(london, 2026, 10, 31, 7, 0), day.atMillis)
    }

    @Test
    fun `the morning puts the headset back to what it was`() {
        val schedule = everyNight
        val night = NightScheduler.apply(
            NightScheduler.nextAction(schedule, NightProgress(), at(london, 2026, 10, 30, 22, 30), london)!!,
            schedule, NightProgress(), headset(current = 1),
        )
        val morning = at(london, 2026, 10, 31, 7, 0)
        val action = assertIs<NightAction.ApplyDay>(NightScheduler.nextAction(schedule, night.progress, morning, london))
        val day = NightScheduler.apply(action, schedule, night.progress, headset(current = 0))
        assertEquals(HeadsetCommand.SetMode(1), day.command)
        assertEquals(NightNote.DAY_APPLIED, day.note)
        assertNull(day.progress.whatItWas)
        // Done for this night: the next thing is tomorrow's bedtime.
        val next = assertIs<NightAction.ApplyNight>(NightScheduler.nextAction(schedule, day.progress, morning, london))
        assertEquals(at(london, 2026, 10, 31, 22, 30), next.atMillis)
    }

    @Test
    fun `connecting after the wake time with the night mode still on applies the day mode once`() {
        val schedule = everyNight
        val night = NightScheduler.apply(
            NightAction.ApplyNight(at(london, 2026, 10, 30, 22, 30), NightScheduler.windowFor(schedule, LocalDate(2026, 10, 30), london)!!),
            schedule, NightProgress(), headset(current = 2),
        )
        val lateMorning = at(london, 2026, 10, 31, 11, 0)
        val overdue = assertIs<NightAction.ApplyDay>(NightScheduler.nextAction(schedule, night.progress, lateMorning, london))
        assertTrue(overdue.atMillis <= lateMorning)
        val day = NightScheduler.apply(overdue, schedule, night.progress, headset(current = 0))
        assertEquals(HeadsetCommand.SetMode(2), day.command)
        assertIs<NightAction.ApplyNight>(NightScheduler.nextAction(schedule, day.progress, lateMorning, london))
    }

    @Test
    fun `moving the wake time earlier in the night ends that night at the new time`() {
        val night = NightScheduler.apply(
            NightScheduler.nextAction(everyNight, NightProgress(), at(london, 2026, 10, 30, 22, 30), london)!!,
            everyNight, NightProgress(), headset(current = 1),
        )
        // At 23:00 the wake time is changed from 07:00 to 23:45.
        val edited = everyNight.copy(wake = LocalTime(23, 45))
        val before = assertIs<NightAction.ApplyDay>(NightScheduler.nextAction(edited, night.progress, at(london, 2026, 10, 30, 23, 0), london))
        assertEquals(at(london, 2026, 10, 30, 23, 45), before.atMillis)
        // Later than the new wake time the day mode is overdue, not left until 07:00 and not another night.
        val after = assertIs<NightAction.ApplyDay>(NightScheduler.nextAction(edited, night.progress, at(london, 2026, 10, 30, 23, 50), london))
        assertTrue(after.atMillis <= at(london, 2026, 10, 30, 23, 50))
        // A later wake time keeps the night going.
        val later = everyNight.copy(wake = LocalTime(9, 0))
        assertEquals(at(london, 2026, 10, 31, 9, 0), assertIs<NightAction.ApplyDay>(NightScheduler.nextAction(later, night.progress, at(london, 2026, 10, 31, 7, 30), london)).atMillis)
    }

    @Test
    fun `an explicit day mode is used instead of what it was`() {
        val schedule = everyNight.copy(dayMode = DayMode.Mode(ModeRef(ModeMeaning.AWARE)))
        val w = NightScheduler.windowFor(schedule, LocalDate(2026, 10, 30), london)!!
        val night = NightScheduler.apply(NightAction.ApplyNight(w.startMillis, w), schedule, NightProgress(), headset(current = 2))
        val day = NightScheduler.apply(NightAction.ApplyDay(w.endMillis, w), schedule, night.progress, headset(current = 0))
        assertEquals(HeadsetCommand.SetMode(1), day.command)
    }

    @Test
    fun `a headset already in the night mode gets no command but still counts as done`() {
        val w = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 30), london)!!
        val night = NightScheduler.apply(NightAction.ApplyNight(w.startMillis, w), everyNight, NightProgress(), headset(current = 0))
        assertNull(night.command)
        assertEquals(NightNote.NIGHT_ALREADY_SET, night.note)
        assertEquals(w.startMillis, night.progress.nightStartMillis)
    }

    @Test
    fun `a headset without the night mode is noted and not asked again that night`() {
        val noQuiet = headset(current = 1).copy(modes = headset(1).modes.filter { it.index != 0 })
        val w = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 30), london)!!
        val night = NightScheduler.apply(NightAction.ApplyNight(w.startMillis, w), everyNight, NightProgress(), noQuiet)
        assertNull(night.command)
        assertEquals(NightNote.NIGHT_MODE_MISSING, night.note)
        val next = NightScheduler.nextAction(everyNight, night.progress, w.startMillis + 60 * min, london)
        assertIs<NightAction.ApplyDay>(next)
    }

    @Test
    fun `nothing changes and the action stays due while the headset isn't ready`() {
        val w = NightScheduler.windowFor(everyNight, LocalDate(2026, 10, 30), london)!!
        val action = NightAction.ApplyNight(w.startMillis, w)
        val off = NightScheduler.apply(action, everyNight, NightProgress(), headset(current = 1, connected = false))
        assertEquals(NightNote.NOT_READY, off.note)
        assertEquals(NightProgress(), off.progress)
        val unread = NightScheduler.apply(action, everyNight, NightProgress(), headset(1).copy(modes = emptyList()))
        assertEquals(NightNote.NOT_READY, unread.note)
    }

    @Test
    fun `a night mode that was never replaced by a day mode keeps its original what-it-was`() {
        // Night 1 applied from Aware; the schedule is edited so night 2 starts before night 1's day mode ran.
        val w1 = NightWindow(1_000, 5_000)
        val w2 = NightWindow(2_000, 9_000)
        val first = NightScheduler.apply(NightAction.ApplyNight(1_000, w1), everyNight, NightProgress(), headset(current = 1))
        val second = NightScheduler.apply(NightAction.ApplyNight(2_000, w2), everyNight, first.progress, headset(current = 0))
        assertEquals(ModeMeaning.AWARE, second.progress.whatItWas?.meaning)
    }

    @Test
    fun `crossing a daylight-saving change the morning action still lands on the wake time`() {
        val schedule = everyNight
        val w = NightScheduler.windowFor(schedule, LocalDate(2026, 9, 26), auckland)!!
        val night = NightScheduler.apply(NightAction.ApplyNight(w.startMillis, w), schedule, NightProgress(), headset(current = 1))
        val day = assertIs<NightAction.ApplyDay>(
            NightScheduler.nextAction(schedule, night.progress, at(auckland, 2026, 9, 27, 4, 0), auckland),
        )
        assertEquals(at(auckland, 2026, 9, 27, 7, 0), day.atMillis)
    }
}
