package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.platform.windows.WinAdverts
import io.github.langkurst.ruru.platform.windows.WinBleHelper
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import io.github.langkurst.ruru.ui.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * `--le` (`./gradlew :app:desktop:hardwareCheck --args=--le`) runs over Bluetooth LE through
 * ruru-ble-win.exe instead of RFCOMM, and changes the bass only. The note is the same file, so a
 * plain run can undo an LE run that was cut short.
 */
fun main(args: Array<String>) {
    overLe = "--le" in args
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
        if (mode == null && eq == null) {
            println("an earlier check was cut short, but nothing it changed is still in place")
        } else {
            println("an earlier check was cut short; undoing what it left changed")
            if (mode != null) {
                check(putBack({ it.setMode(mode) }) { it.currentModeIndex == mode }, "mode put back to what it was before that check")
            }
            if (eq != null) {
                check(putBack({ it.setEq(eq) }) { it.eq == eq }, "equaliser put back to $eq, as it was before that check")
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
    val target = if (overLe) null else start.modes.firstOrNull { it.isConfigured && it.index != originalMode && it.name == "Aware" }
        ?: start.modes.firstOrNull { it.isConfigured && it.index != originalMode }
    val nudged = originalEq?.let { it.copy(bass = if (it.bass < 10) it.bass + 1 else it.bass - 1) }
    PlannedChanges(originalMode, target?.index, originalEq, nudged).write(note)

    var allBack = true
    if (overLe) {
        println("SKIP mode: over Bluetooth LE this check changes the bass only")
    } else if (target != null && originalMode != null) {
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

    if (allBack) {
        note.delete()
    } else {
        println("NOT CONFIRMED BACK. Keep the headphones on and run this check again: it starts by undoing what this run changed.")
    }
    finish()
}

/**
 * What a run is about to change, as "original>changed" pairs. Kept in a file so that a later run
 * can undo exactly this if the run is cut short.
 */
private class PlannedChanges(
    private val mode: Int?,
    private val modeChangedTo: Int?,
    private val eq: HeadphoneEq?,
    private val eqChangedTo: HeadphoneEq?,
) {
    /** The original mode, if [now] still shows the one that run switched to. Anything else was chosen since. */
    fun modeToPutBack(now: HeadphoneState): Int? =
        mode?.takeIf { modeChangedTo != null && now.currentModeIndex == modeChangedTo }

    /** The original equaliser, if [now] still shows the one that run set. Anything else was chosen since. */
    fun eqToPutBack(now: HeadphoneState): HeadphoneEq? =
        eq?.takeIf { eqChangedTo != null && now.eq == eqChangedTo }

    fun write(file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(
            listOfNotNull(
                if (mode != null && modeChangedTo != null) "mode=$mode>$modeChangedTo" else null,
                if (eq != null && eqChangedTo != null) "eq=${eq.text()}>${eqChangedTo.text()}" else null,
            ).joinToString("") { it + "\n" },
        )
    }

    companion object {
        fun read(file: File): PlannedChanges? {
            if (!file.isFile) return null
            val values = file.readLines()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }
            val mode = values["mode"]?.split('>')?.map { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
            val eq = values["eq"]?.split('>')?.map { eqOf(it) }?.takeIf { it.size == 2 }
            return PlannedChanges(mode?.get(0), mode?.get(1), eq?.get(0), eq?.get(1))
        }

        private fun eqOf(text: String): HeadphoneEq? =
            text.split(',').map { it.trim().toIntOrNull() }
                .takeIf { it.size == 3 && null !in it }
                ?.let { HeadphoneEq(it[0]!!, it[1]!!, it[2]!!) }
    }
}

private fun HeadphoneEq.text() = "$bass,$mid,$treble"

/** Sends a change and reads it back on a new connection. Null when it took, otherwise why it didn't. */
private fun confirm(change: (HeadsetController) -> Unit, took: (HeadphoneState) -> Boolean): String? {
    if (!command(change)) return "could not reach the headphones to send it"
    val now = fresh() ?: return "could not reach the headphones to read it back"
    return if (took(now)) null else "the headphones report something else"
}

/** Puts a value back, trying twice. Null when it is confirmed back, otherwise why it isn't. */
private fun putBack(change: (HeadsetController) -> Unit, isBack: (HeadphoneState) -> Boolean): String? =
    confirm(change, isBack)?.let { confirm(change, isBack) }

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
