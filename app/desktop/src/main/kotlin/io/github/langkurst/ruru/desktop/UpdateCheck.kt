package io.github.langkurst.ruru.desktop

import io.github.langkurst.ruru.app.UpdateState
import io.github.langkurst.ruru.app.update.DesktopUpdates
import io.github.langkurst.ruru.app.update.InstallKind
import io.github.langkurst.ruru.app.update.JdkUpdateHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Tries the updater against GitHub for real, pretending to be an older version, and shows each step.
 * It downloads and checks the file, then prints the command it WOULD run to install it and stops:
 * nothing is installed and nothing is closed. Run with:
 * ./gradlew :app:desktop:updateCheck -Pfake=0.1.0 -Pkind=msi
 * kind: setup, msi, mac or other (what the install looks like; the files are not touched).
 */
fun main(args: Array<String>) {
    val fake = args.firstOrNull { it.startsWith("--fake=") }?.substringAfter('=') ?: "0.1.0"
    val kind = when (args.firstOrNull { it.startsWith("--kind=") }?.substringAfter('=') ?: "msi") {
        "setup" -> InstallKind.Setup(Paths.get("C:\\Example\\Ruru\\Ruru.exe"))
        "msi" -> InstallKind.Msi(Paths.get("C:\\Example\\Ruru\\Ruru.exe"))
        "mac" -> InstallKind.MacApp(Paths.get("/Applications/Ruru.app"))
        else -> InstallKind.Other
    }
    val start = System.nanoTime()
    fun say(text: String) = println("[%5.1fs] %s".format((System.nanoTime() - start) / 1e9, text))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val workDir = Files.createTempDirectory("ruru-update-check")
    val updates = DesktopUpdates(
        scope = scope,
        currentVersion = fake,
        kind = kind,
        http = { JdkUpdateHttp("Ruru/check") },
        openWebsite = { url -> say("would open $url"); true },
        launch = { command -> say("WOULD RUN (not run): ${command.joinToString(" ")}") },
        quit = { say("WOULD QUIT Ruru (not quit)") },
        workDir = workDir,
        log = { say(it) },
    )
    say("pretending to be Ruru $fake, install kind ${kind::class.simpleName}, work folder $workDir")
    scope.launch {
        var last: UpdateState? = null
        updates.state.collect { s ->
            // Show each step once; for the download, every 10 per cent.
            val line = if (s is UpdateState.Downloading) "Downloading ${s.version} ${(s.fraction * 100).toInt() / 10 * 10}%" else s.toString()
            if (line != last?.let { if (it is UpdateState.Downloading) "Downloading ${it.version} ${(it.fraction * 100).toInt() / 10 * 10}%" else it.toString() }) say("state: $line")
            last = s
        }
    }
    updates.check()
    val end = runBlocking {
        withTimeoutOrNull(300_000) {
            updates.state.first { it is UpdateState.Ready || it is UpdateState.Failed || it is UpdateState.UpToDate || it is UpdateState.OpenedWebsite }
        }
    }
    if (end is UpdateState.Ready) {
        updates.installAndRestart()
        Thread.sleep(500)
        Files.list(workDir).use { it.forEach { f -> say("in the work folder: ${f.fileName} (${Files.size(f)} bytes)") } }
    }
    say("final state: $end")
    exitProcess(if (end is UpdateState.Ready || end is UpdateState.UpToDate || end is UpdateState.OpenedWebsite) 0 else 1)
}
