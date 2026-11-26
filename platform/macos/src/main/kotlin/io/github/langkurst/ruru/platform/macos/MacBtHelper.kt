package io.github.langkurst.ruru.platform.macos

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.platform.macos.MacBtProtocol.Answer
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Starts `ruru-bt-mac` for one job each time: list the paired devices, open an RFCOMM channel
 * ([openRfcomm]) or hold one GATT link ([openGatt]). Every helper of this object gets the same
 * random salt, so a device's key from [list] still names it when a later job runs; a new Ruru gets
 * new keys. No Bluetooth address ever comes back over the pipe.
 */
class MacBtHelper(val exe: File) {
    private val salt: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Paired devices. Sends nothing to them. */
    fun list(): List<MacBtProtocol.Device> {
        val process = start(MacBtProtocol.listArgs())
        try {
            val answers = ArrayList<Answer>()
            val reader = Thread({
                while (true) {
                    val line = runCatching { MacBtProtocol.readLine(process.inputStream) }.getOrNull() ?: break
                    synchronized(answers) { answers += MacBtProtocol.parse(line) }
                }
            }, "ruru-bt-read")
            reader.isDaemon = true
            reader.start()
            reader.join(LIST_TIMEOUT_MS)
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            val result = synchronized(answers) { answers.toList() }
            result.firstNotNullOfOrNull { it as? Answer.Err }?.let { throw MacBtException(it.failure) }
            return result.mapNotNull { (it as? Answer.Dev)?.device }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Opens one RFCOMM channel with [args] ([MacBtProtocol.connectArgs] for Bose,
     * [MacBtProtocol.connectUuidArgs] for any other service) and returns the byte pipe. Throws
     * [MacBtException] with the helper's code when it cannot (`service-not-found` before anything
     * was opened), [IOException] when the helper does not answer.
     */
    fun openRfcomm(args: List<String>): ByteTransport {
        val process = start(args)
        try {
            val status = process.errorStream.bufferedReader()
            val first = status.readLine() ?: throw IOException("The Bluetooth helper stopped")
            when (val a = MacBtProtocol.parseRfcommStatus(first)) {
                is Answer.Open -> LogRing.add('I', TAG, "RFCOMM channel ${a.opened.maxPdu} open")
                is Answer.Err -> throw MacBtException(a.failure)
                else -> throw IOException("Could not open the control channel: ${first.take(200)}")
            }
            // Whatever else the helper says on stderr (CLOSED) goes to the log; draining keeps it from blocking.
            Thread({ runCatching { status.forEachLine { LogRing.add('I', TAG, it.take(300)) } } }, "ruru-bt-log")
                .apply { isDaemon = true }.start()
            return ProcessTransport(process)
        } catch (e: Throwable) {
            process.destroy()
            throw e
        }
    }

    /**
     * Opens [spec] on the device with [key] and returns the raw GATT pipe, not yet reading
     * ([MacGattTransport.start]). Throws [MacBtException] with the helper's reason when it cannot.
     */
    fun openGatt(key: String, spec: GattLinkSpec, timeoutMs: Long = 45_000): MacGattTransport {
        val process = start(MacBtProtocol.gattArgs(key, spec))
        try {
            val opened = awaitOpen(process, timeoutMs)
            LogRing.add('I', TAG, "${spec.name}: open, max PDU ${opened.maxPdu}, write ${if (opened.withResponse) "with" else "without"} response")
            return MacGattTransport(process, opened, spec.name)
        } catch (e: Throwable) {
            process.destroyForcibly()
            throw e
        }
    }

    private fun awaitOpen(process: Process, timeoutMs: Long): MacBtProtocol.Opened {
        // The first line decides; read it on a thread so a stuck helper is given up on in time.
        var answer: Answer? = null
        var error: Throwable? = null
        val reader = Thread({
            try {
                while (true) {
                    val line = MacBtProtocol.readLine(process.inputStream) ?: break
                    val a = MacBtProtocol.parse(line)
                    if (a is Answer.Open || a is Answer.Err) {
                        answer = a
                        break
                    }
                }
            } catch (t: Throwable) {
                error = t
            }
        }, "ruru-bt-open")
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) throw IOException("The Bluetooth helper did not open the link within ${timeoutMs / 1000} s")
        error?.let { throw IOException("The Bluetooth helper failed: ${it.message}", it) }
        return when (val a = answer) {
            is Answer.Open -> a.opened
            is Answer.Err -> throw MacBtException(a.failure)
            else -> throw IOException("The Bluetooth helper stopped before opening the link")
        }
    }

    private fun start(args: List<String>): Process {
        val builder = ProcessBuilder(listOf(exe.path) + args)
        builder.environment()["RURU_BT_SALT"] = salt
        return builder.start()
    }

    companion object {
        const val TAG = "Mac-BT"
        const val EXE = "ruru-bt-mac"
        private const val LIST_TIMEOUT_MS = 20_000L

        /** In the app bundle (Compose resources dir), or RURU_BT_MAC while developing. */
        fun locate(): File? {
            System.getenv("RURU_BT_MAC")?.let { return File(it) }
            val dir = System.getProperty("compose.application.resources.dir") ?: return null
            return File(dir, EXE).takeIf { it.exists() }
        }
    }
}
