package io.github.langkurst.ruru.app.night

import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.ModeRef
import io.github.langkurst.ruru.sleep.DayMode
import io.github.langkurst.ruru.sleep.NightNote
import io.github.langkurst.ruru.sleep.NightSchedule
import io.github.langkurst.ruru.sleep.SleepStorage
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The night runner against a fake headset and a clock that is the test scheduler's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class NightRunnerTest {
    private val auckland = TimeZone.of("Pacific/Auckland")
    private val london = TimeZone.of("Europe/London")
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun at(zone: TimeZone, d: Int, h: Int, m: Int) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()

    private val schedule = NightSchedule(
        on = true, bedtime = LocalTime(22, 30), wake = LocalTime(7, 0),
        nightMode = ModeRef(ModeMeaning.QUIET), dayMode = DayMode.WhatItWas,
    )

    private fun storeWith(s: NightSchedule = schedule): SettingsStore =
        MemorySettingsStore().also { it.putString(SleepStorage.NIGHT_SCHEDULE_KEY, SleepStorage.encodeSchedule(s)) }

    private class FakeHeadset(current: Int, connected: Boolean) : NightHeadset {
        val state = MutableStateFlow(headset(current, connected))
        val sent = mutableListOf<HeadsetCommand>()
        var accept = true
        override val headset: Flow<HeadphoneState> = state

        override suspend fun send(command: HeadsetCommand): Boolean {
            sent += command
            if (!accept) return false
            if (command is HeadsetCommand.SetMode) state.update { it.copy(currentModeIndex = command.index) }
            return true
        }

        fun connect(on: Boolean) = state.update { it.copy(connection = connection(on)) }
        fun userPicks(slot: Int) = state.update { it.copy(currentModeIndex = slot) }

        companion object {
            fun connection(on: Boolean) = ConnectionState(if (on) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED, "Buds", "00:11:22:33:44:55")
            fun headset(current: Int, connected: Boolean) = HeadphoneState(
                connection = connection(connected),
                modes = listOf(
                    ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
                    ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
                    ListeningMode(2, "Off", isPreset = true, isEditable = false, isConfigured = true),
                ),
                currentModeIndex = current,
            )
        }
    }

    private fun set(vararg slots: Int): List<HeadsetCommand> = slots.map { HeadsetCommand.SetMode(it) }

    /** A runner whose clock starts at [start] and moves with the test scheduler. */
    private fun TestScope.runner(
        start: Long,
        fake: FakeHeadset,
        store: SettingsStore = storeWith(),
        scope: CoroutineScope = backgroundScope,
        zone: () -> TimeZone = { auckland },
    ) = NightRunner(store, scope, fake, now = { start + testScheduler.currentTime }, zone = zone).also {
        it.start()
        testScheduler.runCurrent()
    }

    @Test
    fun `bedtime switches to the night mode and the morning puts back what it was`() = runTest {
        val fake = FakeHeadset(current = 1, connected = true)
        runner(at(auckland, 30, 21, 0), fake)
        advanceTimeBy(1 * hour + 20 * minute)
        assertEquals(emptyList(), fake.sent, "before bedtime nothing happens")
        advanceTimeBy(20 * minute)
        runCurrent()
        assertEquals(set(0), fake.sent, "at 22:30 Quiet goes on")
        advanceTimeBy(8 * hour + 30 * minute)
        runCurrent()
        assertEquals(set(0, 1), fake.sent, "at 07:00 Aware, what it was, comes back")
        assertEquals(1, fake.state.value.currentModeIndex)
    }

    @Test
    fun `the progress is kept in the settings store`() = runTest {
        val store = storeWith()
        val fake = FakeHeadset(current = 1, connected = true)
        val r = runner(at(auckland, 30, 22, 29), fake, store)
        advanceTimeBy(2 * minute)
        runCurrent()
        val saved = SleepStorage.decodeProgress(store.getString(SleepStorage.NIGHT_PROGRESS_KEY))
        assertEquals(at(auckland, 30, 22, 30), saved.nightStartMillis)
        assertEquals(ModeRef(ModeMeaning.AWARE, "Aware", "00:11:22:33:44:55", 1), saved.whatItWas)
        assertEquals(NightNote.NIGHT_APPLIED, r.lastTry.value?.note)
    }

    @Test
    fun `connecting in the middle of the night applies the night mode once`() = runTest {
        val fake = FakeHeadset(current = 1, connected = false)
        runner(at(auckland, 31, 2, 0), fake)
        advanceTimeBy(30 * minute)
        assertEquals(emptyList(), fake.sent, "nothing to send to headphones that are not there")
        fake.connect(true)
        runCurrent()
        assertEquals(set(0), fake.sent)
        // The person then chooses Off by hand; a reconnect must not undo that.
        fake.userPicks(2)
        fake.connect(false)
        runCurrent()
        fake.connect(true)
        advanceTimeBy(5 * minute)
        assertEquals(1, fake.sent.size, "the night was already applied, so a reconnect leaves the mode alone")
        assertEquals(2, fake.state.value.currentModeIndex)
    }

    @Test
    fun `headphones off at bedtime get the night mode when they connect later`() = runTest {
        val fake = FakeHeadset(current = 1, connected = false)
        val r = runner(at(auckland, 30, 22, 0), fake)
        advanceTimeBy(2 * hour)
        assertEquals(emptyList(), fake.sent)
        assertEquals(NightNote.NOT_READY, r.lastTry.value?.note, "the page can say it is waiting")
        fake.connect(true)
        runCurrent()
        assertEquals(set(0), fake.sent)
        assertEquals(NightNote.NIGHT_APPLIED, r.lastTry.value?.note)
    }

    @Test
    fun `headphones that connect after the wake time get their day mode once`() = runTest {
        val fake = FakeHeadset(current = 1, connected = true)
        runner(at(auckland, 30, 22, 0), fake)
        advanceTimeBy(31 * minute)
        runCurrent()
        assertEquals(1, fake.sent.size)
        // They drop at 03:00 and are not back until the afternoon.
        advanceTimeBy(4 * hour)
        fake.connect(false)
        advanceTimeBy(12 * hour)
        runCurrent()
        assertEquals(1, fake.sent.size, "nothing can be sent while they are away")
        fake.connect(true)
        runCurrent()
        assertEquals(set(0, 1), fake.sent)
    }

    @Test
    fun `editing the wake time in the middle of the night moves the morning`() = runTest {
        val fake = FakeHeadset(current = 1, connected = true)
        val r = runner(at(auckland, 30, 22, 0), fake)
        advanceTimeBy(1 * hour)
        assertEquals(1, fake.sent.size)
        // It is 23:00 now.
        r.update { it.copy(wake = LocalTime(23, 45)) }
        runCurrent()
        advanceTimeBy(30 * minute)
        assertEquals(1, fake.sent.size, "23:45 has not come yet")
        advanceTimeBy(20 * minute)
        runCurrent()
        assertEquals(set(0, 1), fake.sent)
    }

    @Test
    fun `an edit before bedtime is picked up at once and saved`() = runTest {
        val store = storeWith()
        val fake = FakeHeadset(current = 1, connected = true)
        val r = runner(at(auckland, 30, 21, 0), fake, store)
        r.update { it.copy(bedtime = LocalTime(21, 10), nightMode = ModeRef(ModeMeaning.OFF)) }
        runCurrent()
        advanceTimeBy(11 * minute)
        runCurrent()
        assertEquals(set(2), fake.sent)
        assertEquals(LocalTime(21, 10), SleepStorage.decodeSchedule(store.getString(SleepStorage.NIGHT_SCHEDULE_KEY)).bedtime)
    }

    @Test
    fun `switching the schedule off stops it and switching it on starts it`() = runTest {
        val fake = FakeHeadset(current = 1, connected = true)
        val r = runner(at(auckland, 30, 21, 0), fake)
        r.update { it.copy(on = false) }
        runCurrent()
        advanceTimeBy(3 * hour)
        assertEquals(emptyList(), fake.sent, "off means nothing is sent, even inside the window")
        r.update { it.copy(on = true) }
        runCurrent()
        assertEquals(set(0), fake.sent, "turned on during the night, it is the catch-up")
    }

    @Test
    fun `a change the headphones refuse is tried again`() = runTest {
        val fake = FakeHeadset(current = 1, connected = true).also { it.accept = false }
        val r = runner(at(auckland, 30, 22, 29), fake)
        advanceTimeBy(2 * minute)
        runCurrent()
        assertTrue(fake.sent.isNotEmpty(), "it tried")
        assertNull(r.lastTry.value, "a refusal is not recorded as done")
        assertEquals(1, fake.state.value.currentModeIndex)
        fake.accept = true
        advanceTimeBy(NightRunner.RETRY_MS + 1)
        runCurrent()
        assertEquals(set(0), listOf(fake.sent.last()))
        assertEquals(0, fake.state.value.currentModeIndex)
        assertEquals(NightNote.NIGHT_APPLIED, r.lastTry.value?.note)
        val tries = fake.sent.size
        advanceTimeBy(10 * minute)
        assertEquals(tries, fake.sent.size, "once it took, it stops trying")
    }

    @Test
    fun `a restart in the middle of the night does not repeat the night`() = runTest {
        val store = storeWith()
        val first = FakeHeadset(current = 1, connected = true)
        val one = runner(at(auckland, 30, 22, 0), first, store, scope = CoroutineScope(coroutineContext + Job()))
        advanceTimeBy(40 * minute)
        runCurrent()
        assertEquals(1, first.sent.size)
        one.stop()
        // Ruru starts again at 23:00 and the headphones are in a mode the person chose by hand.
        val second = FakeHeadset(current = 1, connected = true)
        runner(at(auckland, 30, 22, 0) + hour, second, store)
        advanceTimeBy(10 * minute)
        assertEquals(emptyList(), second.sent)
    }

    @Test
    fun `a change of time zone moves bedtime with the clock`() = runTest {
        var zone = auckland
        val fake = FakeHeadset(current = 1, connected = true)
        // 21:10 in Auckland is 08:10 in London that morning: London's bedtime is still 14 hours away.
        runner(at(auckland, 30, 21, 10), fake, zone = { zone })
        zone = london
        advanceTimeBy(2 * hour)
        runCurrent()
        assertEquals(emptyList(), fake.sent, "Auckland's bedtime passed but the clock now says London time")
        advanceTimeBy(12 * hour + 21 * minute)
        runCurrent()
        assertEquals(set(0), fake.sent)
    }

    @Test
    fun `an unreadable saved schedule is a schedule that is off`() = runTest {
        val store = MemorySettingsStore().also { it.putString(SleepStorage.NIGHT_SCHEDULE_KEY, "{not json") }
        val fake = FakeHeadset(current = 1, connected = true)
        val r = runner(at(auckland, 30, 22, 0), fake, store)
        advanceTimeBy(3 * hour)
        assertEquals(emptyList(), fake.sent)
        assertTrue(!r.currentSchedule.value.on)
    }
}
