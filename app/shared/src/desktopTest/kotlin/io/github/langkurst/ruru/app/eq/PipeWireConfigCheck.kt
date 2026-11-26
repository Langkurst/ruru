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
 * It starts `pipewire -c <the generated file>` in an empty runtime folder (no audio device, no session
 * manager, no root), lets it run for a few seconds and checks that it stays up and logs no error.
 */
class PipeWireConfigCheck {
    @Test
    fun `a real pipewire loads the generated filter chain`() {
        if (System.getenv("RURU_PIPEWIRE_CI") != "1") return
        val dir = Files.createTempDirectory("ruru-pw-ci", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
        try {
            val state = ProEqState(enabled = true).withGraphicGain(2, 4.0).withGraphicGain(6, -3.0)
            val conf = File(dir, "eq-filter-chain.conf").apply { writeText(FilterChainWriter.write(state)) }
            val log = File(dir, "pipewire.log")
            val builder = ProcessBuilder("pipewire", "-c", conf.path)
                .redirectErrorStream(true)
                .redirectOutput(log)
            builder.environment().apply {
                put("XDG_RUNTIME_DIR", dir.path)
                put("PIPEWIRE_RUNTIME_DIR", dir.path)
                put("PIPEWIRE_DEBUG", "2")
            }
            val process = builder.start()
            val endedEarly = process.waitFor(5, TimeUnit.SECONDS)
            val exit = if (endedEarly) process.exitValue() else null
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            val text = log.readText()
            println(text)
            assertFalse(endedEarly, "pipewire stopped by itself (exit $exit):\n$text")
            val errors = text.lines().filter { it.contains("[E]") || it.contains("can't load", ignoreCase = true) || it.contains("syntax", ignoreCase = true) }
            assertTrue(errors.isEmpty(), "pipewire logged errors:\n${errors.joinToString("\n")}")
        } finally {
            dir.deleteRecursively()
        }
    }
}
