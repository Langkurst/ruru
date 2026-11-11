package io.github.langkurst.ruru.platform.macos

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.protocol.bose.BoseModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * macOS: the JVM cannot call IOBluetooth, so a small Swift helper (`helper/main.swift`, bundled
 * as `ruru-bt-mac`) lists paired devices and bridges one RFCOMM channel to its stdin/stdout.
 * Not yet tested on real hardware.
 */
class MacHeadsetPlatform(private val helper: File? = findHelper()) : HeadsetPlatform {
    override val name: String = "macOS"
    override val supported: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac") && helper?.canExecute() == true

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        val exe = helper ?: return@withContext emptyList()
        val p = ProcessBuilder(exe.path, "list").start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@withContext emptyList()
        }
        p.inputStream.bufferedReader().readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[2].contains("Bose", ignoreCase = true)) null
            else HeadsetCandidate(key = parts[0], name = parts[2], connected = parts[1] == "1")
        }.sortedByDescending { it.connected }
    }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport = withContext(Dispatchers.IO) {
        val exe = helper ?: throw IOException("The Bluetooth helper is missing from this build")
        val channel = BoseModel.fromName(candidate.name)?.rfcommChannel ?: 2
        val p = ProcessBuilder(exe.path, "connect", candidate.key, channel.toString()).start()
        val status = p.errorStream.bufferedReader()
        val first = status.readLine() ?: throw IOException("The Bluetooth helper stopped")
        if (!first.startsWith("OPEN")) {
            p.destroy()
            throw IOException("Could not open the control channel: $first")
        }
        ProcessTransport(p)
    }

    companion object {
        /** In the app bundle (Compose resources dir), or RURU_BT_MAC while developing. */
        fun findHelper(): File? {
            System.getenv("RURU_BT_MAC")?.let { return File(it) }
            val dir = System.getProperty("compose.application.resources.dir") ?: return null
            return File(dir, "ruru-bt-mac").takeIf { it.exists() }
        }
    }
}

/** Bytes to and from a helper process: its stdout is the headset, its stdin goes to the headset. */
internal class ProcessTransport(private val process: Process) : ByteTransport {
    @Volatile private var open = true
    override val isConnected: Boolean get() = open && process.isAlive

    override val incoming: Flow<ByteArray> = flow {
        val input = process.inputStream
        val buf = ByteArray(1024)
        while (open) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (!open) break
                open = false
                throw e
            }
            if (n < 0) {
                open = false
                throw IOException("the headset closed the control channel")
            }
            if (n > 0) emit(buf.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!isConnected) throw IOException("control channel closed")
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override fun close() {
        open = false
        runCatching { process.outputStream.close() }
        process.destroy()
    }
}
