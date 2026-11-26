package io.github.langkurst.ruru.app.eq

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a finished command printed (output and error together) and how it ended. */
class CommandOutput(val exitCode: Int, val text: String)

/** The `pipewire -c` process Ruru started. */
interface PipeWireChild {
    val pid: Long
    val alive: Boolean

    /** Ask it to stop, and make sure it did. */
    fun stop()
}

/**
 * The only way the Linux backend touches the system: run a short command, or keep one child running.
 * A fake one stands in for it in tests, so nothing there starts a real process.
 */
interface PipeWireSystem {
    /** Run [command] and wait for it. Null when the program is not there or does not finish in time. */
    fun run(vararg command: String): CommandOutput?

    /** Start [command] and leave it running. Null when it cannot be started. */
    fun spawn(command: List<String>): PipeWireChild?

    /** Stop a `pipewire` process an earlier Ruru left behind (checks that [pid] really is one). */
    fun stopLeftover(pid: Long)
}

/** The real system. */
object RealPipeWireSystem : PipeWireSystem {
    private const val TIMEOUT_MS = 5_000L

    override fun run(vararg command: String): CommandOutput? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        // Read on another thread so a command that hangs cannot hold the caller past the timeout.
        val text = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
        try {
            val out = text.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else {
                CommandOutput(process.exitValue(), out)
            }
        } catch (e: TimeoutException) {
            process.destroyForcibly()
            null
        }
    }.getOrNull()

    override fun spawn(command: List<String>): PipeWireChild? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        object : PipeWireChild {
            override val pid: Long = process.pid()
            override val alive: Boolean get() = process.isAlive
            override fun stop() {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
    }.getOrNull()

    override fun stopLeftover(pid: Long) {
        runCatching {
            ProcessHandle.of(pid).ifPresent { handle ->
                val info = handle.info()
                val isOurs = File(info.command().orElse("")).name.startsWith("pipewire") &&
                    info.arguments().orElse(emptyArray()).any { it.endsWith(LinuxPipeWireEqBackend.CONFIG_NAME) }
                if (isOurs) handle.destroy()
            }
        }
    }
}
