package io.github.langkurst.ruru.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.langkurst.ruru.app.AppModel
import io.github.langkurst.ruru.app.Phase
import io.github.langkurst.ruru.app.RURU_VERSION
import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.Updates
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import io.github.langkurst.ruru.ui.RuruScreen
import io.github.langkurst.ruru.ui.RuruTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Renders the real app off-screen to a PNG, for checking the design and for README screenshots.
 * Usage: Snapshot <out.png> [--demo] [--light] [--height=<dp>] [--update=checking|downloading|ready|installing|uptodate|failed]. Without --demo it connects to the
 * headphones like the app does and waits for them.
 */
fun main(args: Array<String>) {
    val out = File(args.first())
    val demo = "--demo" in args
    val dark = "--light" !in args
    val heightDp = args.firstOrNull { it.startsWith("--height=") }?.substringAfter('=')?.toInt() ?: 1600
    val update = when (args.firstOrNull { it.startsWith("--update=") }?.substringAfter('=')) {
        "checking" -> UpdateState.Checking
        "downloading" -> UpdateState.Downloading("0.2.1", 0.42f)
        "ready" -> UpdateState.Ready("0.2.1")
        "installing" -> UpdateState.Installing("0.2.1")
        "uptodate" -> UpdateState.UpToDate
        "failed" -> UpdateState.Failed(Words("update_failed_check"))
        else -> null
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(WindowsHeadsetPlatform(), scope, startInDemo = demo)
    model.start()
    val connected = runBlocking {
        withTimeoutOrNull(30_000) { model.controller.value.state.first { it.phase == Phase.Connected } }
    }
    println(if (connected != null) "connected" else "not connected, rendering the current state")
    val scale = 2f
    val scene = ImageComposeScene(width = (400 * scale).toInt(), height = (heightDp * scale).toInt(), density = Density(scale)) {
        RuruTheme(dark = dark) { RuruScreen(model, RURU_VERSION, updates = update?.let(::StillUpdates)) }
    }
    var image = scene.render(0)
    for (i in 1..12) {
        Thread.sleep(80)
        image = scene.render(i * 80_000_000L)
    }
    out.parentFile?.mkdirs()
    out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("wrote ${out.path}")
    scene.close()
    model.stop()
    exitProcess(0)
}

/** An updater frozen in one state, to draw that state of the card. */
private class StillUpdates(state: UpdateState) : Updates {
    override val state: StateFlow<UpdateState> = MutableStateFlow(state)
    override fun check() = Unit
    override fun installAndRestart() = Unit
    override fun dismiss() = Unit
}
