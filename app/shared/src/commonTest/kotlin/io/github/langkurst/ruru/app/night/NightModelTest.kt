package io.github.langkurst.ruru.app.night

import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.sleep.DayMode
import io.github.langkurst.ruru.sleep.NightAction
import io.github.langkurst.ruru.sleep.NightProgress
import io.github.langkurst.ruru.sleep.NightSchedule
import io.github.langkurst.ruru.sleep.NightScheduler
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NightModelTest {
    private val zone = TimeZone.of("Pacific/Auckland")
    private fun ms(d: Int, h: Int, m: Int) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()

    @Test
    fun `hours step round the clock in a 24-hour clock`() {
        assertEquals(LocalTime(23, 30), stepHour(LocalTime(22, 30), 1, twelveHour = false))
        assertEquals(LocalTime(0, 30), stepHour(LocalTime(23, 30), 1, twelveHour = false))
        assertEquals(LocalTime(23, 30), stepHour(LocalTime(0, 30), -1, twelveHour = false))
    }

    @Test
    fun `hours stay in am or pm in a 12-hour clock`() {
        assertEquals(LocalTime(23, 0), stepHour(LocalTime(22, 0), 1, twelveHour = true))
        assertEquals(LocalTime(12, 0), stepHour(LocalTime(23, 0), 1, twelveHour = true), "11 pm then 12 pm: the half does not change")
        assertEquals(LocalTime(11, 0), stepHour(LocalTime(0, 0), -1, twelveHour = true), "12 am back one is 11 am")
        assertEquals(LocalTime(7, 15), stepHour(LocalTime(6, 15), 1, twelveHour = true))
    }

    @Test
    fun `minutes step by five and a time between steps lands on the next one`() {
        assertEquals(LocalTime(22, 35), stepMinute(LocalTime(22, 30), 1))
        assertEquals(LocalTime(22, 25), stepMinute(LocalTime(22, 30), -1))
        assertEquals(LocalTime(22, 35), stepMinute(LocalTime(22, 32), 1))
        assertEquals(LocalTime(22, 30), stepMinute(LocalTime(22, 32), -1))
        assertEquals(LocalTime(22, 0), stepMinute(LocalTime(22, 57), 1), "wraps inside the hour")
        assertEquals(LocalTime(22, 55), stepMinute(LocalTime(22, 0), -1))
    }

    @Test
    fun `am and pm keep the hour on the clock face`() {
        assertEquals(LocalTime(22, 30), withHalf(LocalTime(10, 30), pm = true))
        assertEquals(LocalTime(10, 30), withHalf(LocalTime(22, 30), pm = false))
        assertEquals(LocalTime(12, 5), withHalf(LocalTime(0, 5), pm = true))
        assertEquals(LocalTime(0, 5), withHalf(LocalTime(12, 5), pm = false))
        assertEquals(12, hourOnFace(0))
        assertEquals(12, hourOnFace(12))
        assertEquals(10, hourOnFace(22))
        assertEquals(1, hourOnFace(13))
    }

    private fun headset(connected: Boolean) = HeadphoneState(
        connection = ConnectionState(if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED, "Buds", "00:11:22:33:44:55"),
        modes = listOf(
            ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(2, "Commute", isPreset = false, isEditable = true, isConfigured = true),
            ListeningMode(3, "", isPreset = false, isEditable = true, isConfigured = false),
        ),
    )

    @Test
    fun `a connected headset offers its own modes and no empty slot`() {
        val choices = modeChoices(headset(true), emptyList())
        assertEquals(listOf("Quiet", "Aware", "Commute"), choices.map { it.label })
        assertEquals(listOf("quiet", "aware", "custom:commute"), choices.map { it.key })
    }

    @Test
    fun `with no headset the choices are Quiet, Aware and Off, named by the page`() {
        val choices = modeChoices(headset(false), emptyList())
        assertEquals(listOf("quiet", "aware", "off"), choices.map { it.key })
        assertEquals(listOf(null, null, null), choices.map { it.label })
    }

    @Test
    fun `a mode the schedule holds stays on the list when the headset does not have it`() {
        val choices = modeChoices(headset(true), listOf(ModeRef(ModeMeaning.OFF), ModeRef(ModeMeaning.QUIET)))
        assertEquals(listOf("quiet", "aware", "custom:commute", "off"), choices.map { it.key })
    }

    private val schedule = NightSchedule(on = true, bedtime = LocalTime(22, 30), wake = LocalTime(7, 0), nightMode = ModeRef(ModeMeaning.QUIET), dayMode = DayMode.WhatItWas)

    @Test
    fun `the next change tonight has no day and one on another day names it`() {
        val now = ms(30, 21, 0)
        val tonight = NightScheduler.nextAction(schedule, NightProgress(), now, zone)!!
        val p = previewOf(tonight, schedule, now, zone)
        assertIs<NightPreview.ToNight>(p)
        assertEquals(LocalTime(22, 30), p.time)
        assertNull(p.isoDay)
        // 2026-10-30 is a Friday; on Mondays and Tuesdays only, the next night is Monday's.
        val mondays = schedule.copy(days = setOf(1, 2))
        val later = NightScheduler.nextAction(mondays, NightProgress(), now, zone)!!
        assertEquals(1, previewOf(later, mondays, now, zone).isoDay)
    }

    @Test
    fun `in the morning it goes back to the saved mode or to what it was`() {
        val now = ms(31, 1, 0)
        val night = NightProgress(ms(30, 22, 30), ms(31, 7, 0), dayApplied = false, whatItWas = ModeRef(ModeMeaning.AWARE))
        val action = NightScheduler.nextAction(schedule, night, now, zone)!!
        assertIs<NightAction.ApplyDay>(action)
        val back = previewOf(action, schedule, now, zone)
        assertIs<NightPreview.ToDay>(back)
        assertNull(back.mode, "what it was")
        assertEquals(LocalTime(7, 0), back.time)
        val fixed = schedule.copy(dayMode = DayMode.Mode(ModeRef(ModeMeaning.OFF)))
        assertEquals(ModeRef(ModeMeaning.OFF), (previewOf(action, fixed, now, zone) as NightPreview.ToDay).mode)
    }
}
