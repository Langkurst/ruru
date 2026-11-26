package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ModeResolver
import io.github.langkurst.ruru.core.PlaybackState
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.app.night.NightHeadset
import io.github.langkurst.ruru.app.night.NightRunner
import io.github.langkurst.ruru.app.settings.MemorySettingsStore
import io.github.langkurst.ruru.platform.windows.WinAdverts
import io.github.langkurst.ruru.platform.windows.WinBleHelper
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import io.github.langkurst.ruru.sleep.SleepStorage
import io.github.langkurst.ruru.ui.resolve
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.system.exitProcess

/**
 * Checks the app's own controller against real headphones: the same calls the buttons make.
 * Switches the listening mode and nudges the bass, confirms each change on a fresh connection,
 * then puts both back. Run with: ./gradlew :app:desktop:hardwareCheck
 *
 * Headphones that are not being worn switch themselves off, which can cut a run short. So before
 * anything changes, the run notes what it is about to change in a file, and removes the note only
 * once everything is confirmed back. A run that finds a note undoes that change first.
 *
 * Default: mode and bass. `--all` also runs the favourites step (un-favourites one non-current favourite, then
 * restores the exact set) and the volume step (+1, or -1 at the top, then back; then pause and play, only when
 * something is already playing and both are supported: playback is never started). `--only=mode|bass|favourites|volume`
 * runs one step alone. The note records every original (mode, bass, favourites, volume, whether playback was paused).
 *
 * `--only=night` (not part of `--all`, it takes about three minutes) checks the night schedule: the app's own
 * NightRunner, on a schedule held in memory (the real schedule is not touched) that begins in about a minute and
 * lasts two, must switch to a second mode (Aware) at bedtime and put the original mode back at the wake time.
 * The mode is then put back by hand if the schedule did not, as in the mode step. Keep the headphones worn all the time.
 *
 * `--le` (`./gradlew :app:desktop:hardwareCheck --args=--le`) runs over Bluetooth LE through
 * ruru-ble-win.exe instead of RFCOMM, and changes the bass only. The note is the same file, so a
 * plain run can undo an LE run that was cut short.
 */
fun main(args: Array<String>) {
    overLe = "--le" in args
    val choice = chooseSteps(args.toList())
    choice.error?.let { println("FAIL $it"); exitProcess(2) }
    val steps = choice.steps
    // Gradle's --args replaces the task's own arguments, so the note's path may be missing.
    val note = File(args.firstOrNull { !it.startsWith("--") } ?: "build/hardware-check-pending.txt")
    if (overLe) {
        val helper = WinBleHelper.find() ?: run {
            println("FAIL the Bluetooth LE helper ${WinBleHelper.EXE} was not found (build it with pwsh platform/windows/helper/build.ps1)")
            exitProcess(1)
        }
        val heard = runCatching { WinAdverts.summary(helper.scan(4, active = true)) }.getOrElse { "listening failed: ${it.message}" }
        println("over Bluetooth LE; adverts (4 s): $heard")
    }
    var failed = false

    /** [why] is null when the step worked, otherwise the reason it didn't. */
    fun check(why: String?, what: String) {
        println(if (why == null) "PASS $what" else "FAIL $what: $why")
        if (why != null) failed = true
    }
    fun finish(): Nothing {
        println(if (failed) "hardware check FAILED" else "hardware check passed")
        exitProcess(if (failed) 1 else 0)
    }

    var start = fresh() ?: run {
        println("FAIL no headphones connected (they switch off when they are not being worn)")
        exitProcess(1)
    }

    // A run that was cut short left a note. Undo what it changed, and nothing else.
    PlannedChanges.read(note)?.let { earlier ->
        val mode = earlier.modeToPutBack(start)
        val eq = earlier.eqToPutBack(start)
        val favourites = earlier.favouritesToPutBack(start)
        val volume = earlier.volumeToPutBack(start)
        val play = earlier.playToPutBack(start)
        if (mode == null && eq == null && favourites == null && volume == null && !play) {
            println("an earlier check was cut short, but nothing it changed is still in place")
        } else {
            println("an earlier check was cut short; undoing what it left changed")
            if (mode != null) {
                check(putBack({ it.setMode(mode) }) { it.currentModeIndex == mode }, "mode put back to what it was before that check")
            }
            if (eq != null) {
                check(putBack({ it.setEq(eq) }) { it.eq == eq }, "equaliser put back to $eq, as it was before that check")
            }
            if (favourites != null) {
                check(putBack({ it.setFavorites(favourites) }) { favouriteSet(it) == favourites }, "favourites put back to ${favourites.sorted()}, as they were before that check")
            }
            if (volume != null) {
                check(putBack({ it.setVolume(volume) }) { it.volume == volume }, "volume put back to $volume, as it was before that check")
            }
            if (play) {
                check(sendAndWatch({ it.transport(TransportControl.PLAY) }, PlaybackState.PLAYING), "playback resumed, as it was before that check")
            }
            if (failed) {
                println("Keep the headphones on and run this check again.")
                finish()
            }
            start = fresh() ?: run {
                check("could not reach the headphones", "headphones read again")
                finish()
            }
        }
        note.delete()
    }

    val originalMode = start.currentModeIndex
    val originalEq = start.eq
    println("connected: ${start.info?.modelName ?: "headphones"}, mode ${start.currentMode?.name}, eq $originalEq")

    // Over LE only the bass changes (one setting per write test).
    for (step in steps) {
        if (overLe && step != Step.BASS) println("SKIP ${step.flag}: over Bluetooth LE this check changes the bass only")
    }
    val run = if (overLe) steps.filter { it == Step.BASS }.toSet() else steps

    val target = if (Step.MODE in run || Step.NIGHT in run) {
        start.modes.firstOrNull { it.isConfigured && it.index != originalMode && it.name == "Aware" }
            ?: start.modes.firstOrNull { it.isConfigured && it.index != originalMode }
    } else null
    val nudged = if (Step.BASS in run) originalEq?.let { it.copy(bass = if (it.bass < 10) it.bass + 1 else it.bass - 1) } else null
    val favPlan = if (Step.FAVOURITES in run) planFavourites(start) else null
    val volPlan = if (Step.VOLUME in run) planVolume(start) else null
    val pausePlay = Step.VOLUME in run && canTestPlayback(start)
    // The note records every original before anything changes, so a cut-short run is undone first next time.
    PlannedChanges(
        originalMode, target?.index, originalEq, nudged,
        favPlan?.original, favPlan?.changed, volPlan?.first, volPlan?.second, paused = pausePlay,
    ).write(note)

    var allBack = true

    if (Step.MODE in run) {
        if (target != null && originalMode != null) {
            check(
                confirm({ it.setMode(target.index) }) { it.currentModeIndex == target.index },
                "mode changed to ${target.name} (read back on a new connection)",
            )
            val back = putBack({ it.setMode(originalMode) }) { it.currentModeIndex == originalMode }
            check(back, "mode put back to ${start.currentMode?.name}")
            allBack = back == null
        } else {
            check("none found", "found a second mode to switch to")
        }
    }

    // H7: the night schedule. Set to begin in about a minute: the mode must switch, and come back at the wake time.
    if (Step.NIGHT in run) {
        if (target != null && originalMode != null) {
            val plan = planNightSchedule(System.currentTimeMillis(), TimeZone.currentSystemDefault(), ModeResolver.refFor(target, start.connection.deviceAddress))
            println("night schedule: bedtime ${plan.schedule.bedtime}, wake ${plan.schedule.wake}; waiting about three minutes (keep the headphones on)")
            val ran = runNightSchedule(plan, target.index, originalMode)
            check(
                when {
                    ran == null -> "could not reach the headphones"
                    ran.night -> null
                    else -> "the mode did not switch to ${target.name} by bedtime"
                },
                "night schedule switched to ${target.name} at bedtime",
            )
            if (ran != null && ran.night) {
                check(if (ran.back) null else "the mode was not put back at the wake time", "night schedule put the mode back to ${start.currentMode?.name} at the wake time")
            }
            // Whatever the schedule did, the headphones end where they started (confirmed on a new connection).
            val back = putBack({ it.setMode(originalMode) }) { it.currentModeIndex == originalMode }
            check(back, "mode confirmed back to ${start.currentMode?.name}")
            allBack = back == null
        } else {
            check("none found", "found a second mode to run the night schedule with")
        }
    }

    if (Step.BASS in run) {
        if (!allBack) {
            println("SKIP equaliser: nothing else is changed while the mode is not confirmed back")
        } else if (originalEq != null && nudged != null) {
            check(
                confirm({ it.setEq(nudged) }) { it.eq == nudged },
                "bass changed to ${nudged.bass} (read back on a new connection)",
            )
            val back = putBack({ it.setEq(originalEq) }) { it.eq == originalEq }
            check(back, "equaliser put back to $originalEq")
            allBack = back == null
        } else {
            check("none reported", "headphones reported an equaliser")
        }
    }

    // H5: favourites. Un-favourite one favourite that is not the current mode, then restore the exact original set.
    if (Step.FAVOURITES in run) {
        if (!allBack) {
            println("SKIP favourites: nothing else is changed while an earlier step is not confirmed back")
        } else if (favPlan == null) {
            println("SKIP favourites: the headphones do not report favourites, or fewer than two modes are favourites")
        } else {
            check(
                confirm({ it.setFavorites(favPlan.changed) }) { favouriteSet(it) == favPlan.changed },
                "mode ${favPlan.dropped} un-favourited (read back on a new connection)",
            )
            val back = putBack({ it.setFavorites(favPlan.original) }) { favouriteSet(it) == favPlan.original }
            check(back, "favourites put back to ${favPlan.original.sorted()}")
            allBack = back == null
        }
    }

    // H6: volume, then pause/play. The volume and the play state are both read at connect time, so a new
    // connection shows them (volume is confirmed that way). The play state is a notification that can arrive
    // late, so pause and play are confirmed on the same connection that sent them (it watches for the status).
    if (Step.VOLUME in run) {
        if (!allBack) {
            println("SKIP volume: nothing else is changed while an earlier step is not confirmed back")
        } else {
            if (volPlan == null) {
                println("SKIP volume: the headphones do not report a volume and its top step")
            } else {
                val (original, changed) = volPlan
                check(
                    confirm({ it.setVolume(changed) }) { it.volume == changed },
                    "volume changed to $changed (read back on a new connection)",
                )
                val back = putBack({ it.setVolume(original) }) { it.volume == original }
                check(back, "volume put back to $original")
                allBack = back == null
            }
            if (allBack) {
                if (pausePlay) {
                    check(sendAndWatch({ it.transport(TransportControl.PAUSE) }, PlaybackState.PAUSED), "paused (status read as PAUSED)")
                    val resumed = sendAndWatch({ it.transport(TransportControl.PLAY) }, PlaybackState.PLAYING)
                        ?.let { sendAndWatch({ it.transport(TransportControl.PLAY) }, PlaybackState.PLAYING) }
                    check(resumed, "playing again (status read as PLAYING)")
                    allBack = resumed == null
                } else {
                    println("SKIP pause/play: nothing is playing, skipped pause/play (or pause and play are not both supported)")
                }
            }
        }
    }

    if (allBack) {
        note.delete()
    } else {
        println("NOT CONFIRMED BACK. Keep the headphones on and run this check again: it starts by undoing what this run changed.")
    }
    finish()
}

/** Sends a change and reads it back on a new connection. Null when it took, otherwise why it didn't. */
private fun confirm(change: (HeadsetController) -> Unit, took: (HeadphoneState) -> Boolean): String? {
    if (!command(change)) return "could not reach the headphones to send it"
    val now = fresh() ?: return "could not reach the headphones to read it back"
    return if (took(now)) null else "the headphones report something else"
}

/** Puts a value back, trying twice. Null when it is confirmed back, otherwise why it isn't. */
private fun putBack(change: (HeadsetController) -> Unit, isBack: (HeadphoneState) -> Boolean): String? =
    confirm(change, isBack)?.let { confirm(change, isBack) }

/**
 * Sends [change] and watches the same connection for the play state to read [want] (it arrives as a notification,
 * so it is not waited for on a new link). Null when it did, otherwise why not.
 */
private fun sendAndWatch(change: (HeadsetController) -> Unit, want: PlaybackState): String? {
    val seen = withController { controller, _ ->
        change(controller)
        runBlocking {
            withTimeoutOrNull(8_000) { controller.state.first { it.headset.playback?.state == want } }
        } != null
    }
    return when (seen) {
        null -> "could not reach the headphones"
        true -> null
        else -> "the headphones report something else"
    }
}

/** Opens a new controller, waits for it to connect and returns what the headphones report. */
private fun fresh(): HeadphoneState? = withController { _, state -> state.headset }

/** Runs [action] on a connected controller and waits until the command has finished. False if it could not connect. */
private fun command(action: (HeadsetController) -> Unit): Boolean =
    withController { controller, _ ->
        action(controller)
        Thread.sleep(2_500)
        controller.state.value.message?.let { println("  headphones said: ${runBlocking { it.resolve() }}") }
        true
    } ?: false

/** What the night step saw: the mode switched at bedtime, and came back at the wake time. */
private class NightRun(val night: Boolean, val back: Boolean)

/**
 * Runs the app's NightRunner against the connected headphones on [plan]'s schedule (kept in memory, so the real settings
 * are not touched). Null if it could not connect.
 */
private fun runNightSchedule(plan: NightPlan, nightSlot: Int, originalSlot: Int): NightRun? =
    withController { controller, _ ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = MemorySettingsStore().also { it.putString(SleepStorage.NIGHT_SCHEDULE_KEY, SleepStorage.encodeSchedule(plan.schedule)) }
        val headset = object : NightHeadset {
            override val headset: Flow<HeadphoneState> = controller.state.map { it.headset }
            override suspend fun send(command: HeadsetCommand) = controller.send(command)
        }
        val runner = NightRunner(settings, scope, headset)
        runner.start()
        try {
            runBlocking {
                val night = withTimeoutOrNull(plan.bedMillis - System.currentTimeMillis() + 90_000) {
                    controller.state.first { it.headset.currentModeIndex == nightSlot }
                } != null
                val back = night && withTimeoutOrNull(plan.wakeMillis - System.currentTimeMillis() + 90_000) {
                    controller.state.first { it.headset.currentModeIndex == originalSlot }
                } != null
                NightRun(night, back)
            }
        } finally {
            runner.stop()
            scope.cancel()
        }
    }

/** Set by `--le`: every connection goes over Bluetooth LE through the helper. */
private var overLe = false

private fun <T> withController(block: (HeadsetController, AppState) -> T): T? {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = HeadsetController(WindowsHeadsetPlatform(boseOverLe = overLe), scope, demo = false)
    controller.start()
    return try {
        val state = runBlocking {
            withTimeoutOrNull(if (overLe) 45_000 else 30_000) {
                controller.state.first { it.phase == Phase.Connected && it.headset.modes.isNotEmpty() }
            }
        } ?: return null
        block(controller, state)
    } finally {
        controller.stop()
        scope.cancel()
        // Over LE the helper switches notifications off and exits first, so the next link starts clean.
        Thread.sleep(if (overLe) 4_000 else 800)
    }
}
