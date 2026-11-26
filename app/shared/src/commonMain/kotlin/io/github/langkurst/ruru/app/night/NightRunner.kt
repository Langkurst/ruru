package io.github.langkurst.ruru.app.night

import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.session.SessionLog
import io.github.langkurst.ruru.session.SinkSessionLog
import io.github.langkurst.ruru.sleep.NightAction
import io.github.langkurst.ruru.sleep.NightNote
import io.github.langkurst.ruru.sleep.NightProgress
import io.github.langkurst.ruru.sleep.NightSchedule
import io.github.langkurst.ruru.sleep.NightScheduler
import io.github.langkurst.ruru.sleep.SleepStorage
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** The headset the night schedule drives: what it reports, and one command at a time that says whether it took. */
interface NightHeadset {
    val headset: Flow<HeadphoneState>
    suspend fun send(command: HeadsetCommand): Boolean
}

/** The runner's last try at an action, for the Night page: [note] says what happened and [atMillis] when. */
data class NightLast(val note: NightNote, val atMillis: Long)

/**
 * Carries out the night schedule while Ruru runs (the desktop tray process today; a phone's own
 * runner later uses the same rules). It asks [NightScheduler.nextAction], waits until that time,
 * switches the mode through [headset] and keeps the [NightProgress] in the settings store, so a
 * restart does not repeat a night. Connecting is the catch-up: the loop runs again, sees a night
 * that has begun and applies its night mode once.
 *
 * It never waits longer than a minute for a time to arrive, then looks at the clock and the time
 * zone again, so a clock change, a trip, daylight saving or the computer sleeping through bedtime
 * all land on the right moment. An edit to the schedule, a restored backup or the headphones
 * becoming ready wakes it at once. A command that fails is not counted as done and is tried again.
 */
class NightRunner(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val headset: NightHeadset,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val log: SessionLog = SinkSessionLog("Ruru/Night"),
) {
    private val schedule = MutableStateFlow(readSchedule())
    private val progress = MutableStateFlow(readProgress())
    private val last = MutableStateFlow<NightLast?>(null)
    private val poke = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var latest = HeadphoneState()
    private var job: Job? = null

    val currentSchedule: StateFlow<NightSchedule> = schedule.asStateFlow()
    val currentProgress: StateFlow<NightProgress> = progress.asStateFlow()
    val lastTry: StateFlow<NightLast?> = last.asStateFlow()

    fun nowMillis(): Long = now()
    fun timeZone(): TimeZone = zone()

    /** The next thing the schedule will do, as of now. Null when it is off or has nothing to do. */
    fun nextAction(): NightAction? = NightScheduler.nextAction(schedule.value, progress.value, now(), zone())

    fun start() {
        if (job != null) return
        job = scope.launch {
            coroutineScope {
                launch { watchSettings() }
                launch { watchHeadset() }
                loop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Changes the schedule, saves it and makes the runner look again. */
    fun update(change: (NightSchedule) -> NightSchedule) {
        val next = change(schedule.value)
        if (next == schedule.value) return
        settings.putString(SleepStorage.NIGHT_SCHEDULE_KEY, SleepStorage.encodeSchedule(next))
        schedule.value = next
        poke.trySend(Unit)
    }

    private fun readSchedule() = SleepStorage.decodeSchedule(settings.getString(SleepStorage.NIGHT_SCHEDULE_KEY))
    private fun readProgress() = SleepStorage.decodeProgress(settings.getString(SleepStorage.NIGHT_PROGRESS_KEY))

    /** A schedule written from elsewhere (a restored backup) is picked up. */
    private suspend fun watchSettings() {
        settings.changes.collect { key ->
            if (key == SleepStorage.NIGHT_SCHEDULE_KEY) {
                val read = readSchedule()
                if (read != schedule.value) {
                    schedule.value = read
                    poke.trySend(Unit)
                }
            }
        }
    }

    private suspend fun watchHeadset() {
        // Every state is kept for the commands; only a change of readiness wakes the loop.
        headset.headset
            .map { latest = it; Readiness(it.isConnected, it.modes.any { m -> m.isConfigured }, it.connection.deviceAddress) }
            .distinctUntilChanged()
            .collect { poke.trySend(Unit) }
    }

    private data class Readiness(val connected: Boolean, val modesRead: Boolean, val address: String?)

    private suspend fun wait(ms: Long) {
        withTimeoutOrNull(ms) { poke.receive() }
    }

    private suspend fun loop() {
        while (true) {
            val sched = schedule.value
            val done = progress.value
            val t = now()
            val action = NightScheduler.nextAction(sched, done, t, zone())
            if (action == null) {
                poke.receive()
                continue
            }
            val until = action.atMillis - t
            if (until > 0) {
                wait(minOf(until, MAX_WAIT_MS))
                continue
            }
            val result = NightScheduler.apply(action, sched, done, latest)
            if (result.note == NightNote.NOT_READY) {
                if (last.value?.note != NightNote.NOT_READY) {
                    last.value = NightLast(NightNote.NOT_READY, t)
                    log.i("night schedule: due, waiting for the headphones to connect")
                }
                wait(MAX_WAIT_MS)
                continue
            }
            val command = result.command
            if (command != null && !headset.send(command)) {
                log.w("night schedule: the headphones did not take the change, trying again soon")
                wait(RETRY_MS)
                continue
            }
            progress.value = result.progress
            settings.putString(SleepStorage.NIGHT_PROGRESS_KEY, SleepStorage.encodeProgress(result.progress))
            last.value = NightLast(result.note, now())
            log.i("night schedule: ${noteText(result.note)}")
        }
    }

    companion object {
        /** The longest wait before the clock and time zone are looked at again. */
        const val MAX_WAIT_MS = 60_000L

        /** The wait before a change the headphones refused is tried again. */
        const val RETRY_MS = 30_000L

        fun noteText(note: NightNote): String = when (note) {
            NightNote.NIGHT_APPLIED -> "switched to the night mode"
            NightNote.NIGHT_ALREADY_SET -> "the headphones were already in the night mode"
            NightNote.NIGHT_MODE_MISSING -> "these headphones have no mode like the night mode, so nothing changed"
            NightNote.DAY_APPLIED -> "switched to the morning mode"
            NightNote.DAY_ALREADY_SET -> "the headphones were already in the morning mode"
            NightNote.DAY_MODE_MISSING -> "these headphones have no mode like the morning mode, so nothing changed"
            NightNote.NOTHING_TO_RESTORE -> "there was no earlier mode to go back to"
            NightNote.NOT_READY -> "waiting for the headphones to connect"
        }
    }
}
