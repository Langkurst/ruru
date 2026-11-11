package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Checks the app's own controller against real headphones: the same calls the buttons make.
 * Switches the listening mode and nudges the bass, confirms each change on a fresh connection,
 * then puts both back. Run with: ./gradlew :app:desktop:hardwareCheck
 */
fun main() {
    var failed = false
    fun check(ok: Boolean, what: String) {
        println((if (ok) "PASS " else "FAIL ") + what)
        if (!ok) failed = true
    }

    val start = fresh() ?: run { println("FAIL no headphones connected"); exitProcess(1) }
    val originalMode = start.currentModeIndex
    val originalEq = start.eq
    println("connected: ${start.info?.modelName ?: "headphones"}, mode ${start.currentMode?.name}, eq $originalEq")
    val target = start.modes.firstOrNull { it.isConfigured && it.index != originalMode && it.name == "Aware" }
        ?: start.modes.firstOrNull { it.isConfigured && it.index != originalMode }

    if (target != null && originalMode != null) {
        command { it.setMode(target.index) }
        check(fresh()?.currentModeIndex == target.index, "mode changed to ${target.name} (read back on a new connection)")
        command { it.setMode(originalMode) }
        check(fresh()?.currentModeIndex == originalMode, "mode put back to ${start.currentMode?.name}")
    } else {
        check(false, "found a second mode to switch to")
    }

    if (originalEq != null) {
        val nudged = originalEq.copy(bass = if (originalEq.bass < 10) originalEq.bass + 1 else originalEq.bass - 1)
        command { it.setEq(nudged) }
        check(fresh()?.eq == nudged, "bass changed to ${nudged.bass} (read back on a new connection)")
        command { it.setEq(originalEq) }
        check(fresh()?.eq == originalEq, "equaliser put back to $originalEq")
    } else {
        check(false, "headphones reported an equaliser")
    }

    println(if (failed) "hardware check FAILED" else "hardware check passed")
    exitProcess(if (failed) 1 else 0)
}

/** Opens a new controller, waits for it to connect and returns what the headphones report. */
private fun fresh(): HeadphoneState? = withController { _, state -> state.headset }

/** Runs [action] on a connected controller and waits until the command has finished. */
private fun command(action: (HeadsetController) -> Unit) {
    withController { controller, _ ->
        action(controller)
        Thread.sleep(2_500)
        controller.state.value.message?.let { println("  headphones said: $it") }
    }
}

private fun <T> withController(block: (HeadsetController, AppState) -> T): T? {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = HeadsetController(WindowsHeadsetPlatform(), scope, demo = false)
    controller.start()
    return try {
        val state = runBlocking {
            withTimeoutOrNull(30_000) { controller.state.first { it.phase == Phase.Connected && it.headset.modes.isNotEmpty() } }
        } ?: return null
        block(controller, state)
    } finally {
        controller.stop()
        scope.cancel()
        Thread.sleep(800)
    }
}
