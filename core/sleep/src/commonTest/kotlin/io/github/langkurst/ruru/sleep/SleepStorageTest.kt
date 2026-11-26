package io.github.langkurst.ruru.sleep

import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SleepStorageTest {
    @Test
    fun `the keys are the ones the backup carries`() {
        assertEquals("sleep.plan", SleepStorage.SLEEP_PLAN_KEY)
        assertEquals("night.schedule", SleepStorage.NIGHT_SCHEDULE_KEY)
    }

    @Test
    fun `a plan survives a round trip`() {
        val plan = SleepPlan(
            durationMinutes = 120,
            layers = listOf(SleepLayer("rain", 0.5f), SleepLayer("brown", 0.3f)),
            noiseOffAfterMinutes = 30,
            endAction = SleepEndAction.DISCONNECT,
            nightExtras = setOf(NightExtra.TOUCH_LOCK, NightExtra.PROMPTS_OFF),
            soundSide = SoundSide.LEFT_ONLY,
        )
        assertEquals(plan, SleepStorage.decodePlan(SleepStorage.encodePlan(plan)))
    }

    @Test
    fun `a schedule survives a round trip`() {
        val schedule = NightSchedule(
            on = true,
            bedtime = LocalTime(23, 5),
            wake = LocalTime(6, 45),
            days = setOf(1, 2, 3, 4, 5),
            nightMode = ModeRef(ModeMeaning.QUIET, name = "Quiet", address = "00:11:22:33:44:55", slot = 0),
            dayMode = DayMode.Mode(ModeRef(ModeMeaning.AWARE)),
        )
        val text = SleepStorage.encodeSchedule(schedule)
        assertTrue("\"23:05\"" in text && "\"06:45\"" in text, text)
        assertEquals(schedule, SleepStorage.decodeSchedule(text))
        val whatItWas = schedule.copy(dayMode = DayMode.WhatItWas)
        assertEquals(whatItWas, SleepStorage.decodeSchedule(SleepStorage.encodeSchedule(whatItWas)))
    }

    @Test
    fun `progress survives a round trip`() {
        val progress = NightProgress(100, 200, dayApplied = false, whatItWas = ModeRef(ModeMeaning.AWARE))
        assertEquals(progress, SleepStorage.decodeProgress(SleepStorage.encodeProgress(progress)))
    }

    @Test
    fun `missing or damaged text gives the defaults and never throws`() {
        assertEquals(SleepPlan(), SleepStorage.decodePlan(null))
        assertEquals(SleepPlan(), SleepStorage.decodePlan("not json"))
        assertEquals(NightSchedule(), SleepStorage.decodeSchedule(null))
        assertEquals(NightSchedule(), SleepStorage.decodeSchedule("{\"bedtime\":\"25:99\"}"))
        assertEquals(NightProgress(), SleepStorage.decodeProgress("[]"))
    }

    @Test
    fun `names from a newer build are ignored and unknown choices fall back to the default`() {
        val plan = SleepStorage.decodePlan("{\"durationMinutes\":90,\"somethingNew\":1,\"endAction\":\"TELEPORT\"}")
        assertEquals(90, plan.durationMinutes)
        assertEquals(SleepEndAction.STOP_SOUNDS, plan.endAction)
        val schedule = SleepStorage.decodeSchedule("{\"on\":true,\"somethingNew\":[1],\"nightMode\":{\"meaning\":\"future-mode\"}}")
        assertTrue(schedule.on)
        assertEquals(ModeMeaning.CUSTOM, schedule.nightMode.meaning)
        assertEquals("future-mode", schedule.nightMode.unknownMeaning)
    }

    @Test
    fun `an out-of-range plan is clamped on the way in`() {
        val plan = SleepStorage.decodePlan("{\"durationMinutes\":99999,\"volume\":4.0}")
        assertEquals(SleepPlan.MAX_MINUTES, plan.durationMinutes)
        assertEquals(1f, plan.volume)
    }
}
