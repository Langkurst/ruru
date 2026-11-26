package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.dsp.FilterChainWriter
import io.github.langkurst.ruru.dsp.ProEqState
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proof that the file [FilterChainWriter] writes is one a real PipeWire accepts. It only runs when
 * RURU_PIPEWIRE_CI=1 (the CI job that installs PipeWire sets it); everywhere else it passes without doing anything.
 *
 * It starts a default `pipewire` daemon in an empty runtime folder (no audio device, no session manager, no root),
 * then `pipewire -c <the generated file>` as a client of it, lets that run for a few seconds and checks that it
 * stays up and logs no error.
 */
class PipeWireConfigCheck {
    @Test
    fun `a real pipewire loads the generated filter chain`() {
        if (System.getenv("RURU_PIPEWIRE_CI") != "1") return
        val dir = Files.createTempDirectory("ruru-pw-ci", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
        try {
            val state = ProEqState(enabled = true).withGraphicGain(2, 4.0).withGraphicGain(6, -3.0)
            val conf = File(dir, "eq-filter-chain.conf").apply { writeText(FilterChainWriter.write(state)) }
            val env = mapOf("XDG_RUNTIME_DIR" to dir.path, "PIPEWIRE_RUNTIME_DIR" to dir.path, "PIPEWIRE_DEBUG" to "2")
            fun start(name: String, vararg args: String): Pair<Process, File> {
                val out = File(dir, "$name.log")
                val b = ProcessBuilder("pipewire", *args).redirectErrorStream(true).redirectOutput(out)
                b.environment().putAll(env)
                return b.start() to out
            }
            // The filter chain module is a client: it connects to a running PipeWire, so a daemon with the default
            // config (protocol-native socket, no sound card, no session manager) runs first.
            val (daemon, daemonLog) = start("daemon")
            var client: Process? = null
            try {
                val socket = File(dir, "pipewire-0")
                val deadline = System.currentTimeMillis() + 10_000
                while (!socket.exists() && daemon.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertTrue(daemon.isAlive && socket.exists(), "the pipewire daemon did not start: ${daemonLog.readText()}")
                val (proc, log) = start("chain", "-c", conf.path)
                client = proc
                val endedEarly = proc.waitFor(5, TimeUnit.SECONDS)
                val exit = if (endedEarly) proc.exitValue() else null
                proc.destroy()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
                val text = log.readText()
                println(text)
                assertFalse(endedEarly, "pipewire stopped by itself (exit $exit): $text")
                // RTKit/dbus lines are warnings in a headless runner (no session bus); everything else at [E] is real.
                val errors = text.lines().filter {
                    !it.contains("mod.rt") &&
                        (it.contains("[E]") || it.contains("can't load", ignoreCase = true) || it.contains("syntax", ignoreCase = true))
                }
                assertTrue(errors.isEmpty(), "pipewire logged errors: ${errors.joinToString(" | ")}")
            } finally {
                client?.destroyForcibly()
                daemon.destroyForcibly()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
