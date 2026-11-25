package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.LogRing
import io.github.langkurst.ruru.platform.windows.WinBleProtocol.Answer
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Starts `ruru-ble-win.exe` for one job each time: list the paired Bluetooth LE devices, listen for
 * adverts, or hold one GATT link ([openGatt]). Every helper of this object gets the same random
 * salt, so a device's key from [list] still names it when [openGatt] runs; a new Ruru gets new keys.
 */
class WinBleHelper(val exe: File) {
    private val salt: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Bluetooth LE devices paired with Windows. Sends nothing to them. */
    fun list(): List<WinBleProtocol.Device> =
        oneShot("list", timeoutMs = 20_000).mapNotNull { (it as? Answer.Dev)?.device }

    /** Advertisers heard in [seconds]. Passive listening sends nothing; [active] also asks for scan responses. */
    fun scan(seconds: Int, active: Boolean = false): List<WinBleProtocol.Advert> =
        oneShot(WinBleProtocol.scanCommand(seconds, active), timeoutMs = seconds * 1_000L + 15_000)
            .mapNotNull { (it as? Answer.Adv)?.advert }

    /**
     * Opens [spec] on the device with [key] and returns the raw GATT pipe, not yet reading
     * ([WinGattTransport.start]). Throws [IOException] with the helper's reason when it cannot.
     */
    fun openGatt(key: String, spec: GattLinkSpec, timeoutMs: Long = 45_000): WinGattTransport {
        val command = WinBleProtocol.gattCommand(key, spec)
        val process = start()
        try {
            process.outputStream.write((command + "\n").toByteArray())
            process.outputStream.flush()
            val opened = awaitOpen(process, timeoutMs)
            LogRing.add('I', TAG, "${spec.name}: open, max PDU ${opened.maxPdu}, write ${if (opened.withResponse) "with" else "without"} response")
            return WinGattTransport(process, opened, spec.name)
        } catch (e: Throwable) {
            process.destroyForcibly()
            throw e
        }
    }

    private fun awaitOpen(process: Process, timeoutMs: Long): WinBleProtocol.Opened {
        // The first line decides; read it on a thread so a stuck helper is given up on in time.
        var answer: Answer? = null
        var error: Throwable? = null
        val reader = Thread({
            try {
                while (true) {
                    val line = WinBleProtocol.readLine(process.inputStream) ?: break
                    val a = WinBleProtocol.parse(line)
                    if (a is Answer.Open || a is Answer.Err) {
                        answer = a
                        break
                    }
                }
            } catch (t: Throwable) {
                error = t
            }
        }, "ruru-ble-open")
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) throw IOException("The Bluetooth LE helper did not open the link within ${timeoutMs / 1000} s")
        error?.let { throw IOException("The Bluetooth LE helper failed: ${it.message}", it) }
        return when (val a = answer) {
            is Answer.Open -> a.opened
            is Answer.Err -> throw WinBleException(a.failure)
            else -> throw IOException("The Bluetooth LE helper stopped before opening the link")
        }
    }

    private fun oneShot(command: String, timeoutMs: Long): List<Answer> {
        val process = start()
        try {
            process.outputStream.write((command + "\nclose\n").toByteArray())
            process.outputStream.flush()
            val answers = ArrayList<Answer>()
            val reader = Thread({
                while (true) {
                    val line = runCatching { WinBleProtocol.readLine(process.inputStream) }.getOrNull() ?: break
                    synchronized(answers) { answers += WinBleProtocol.parse(line) }
                }
            }, "ruru-ble-read")
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMs)
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            val result = synchronized(answers) { answers.toList() }
            result.firstNotNullOfOrNull { it as? Answer.Err }?.let { throw WinBleException(it.failure) }
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun start(): Process {
        val builder = ProcessBuilder(exe.path)
        builder.environment()["RURU_BLE_SALT"] = salt
        val process = builder.start()
        // The helper's diagnostics (no addresses) go to Ruru's log; draining them keeps it from blocking.
        Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine { LogRing.add('I', TAG, it.take(300)) }
            }
        }, "ruru-ble-log").apply { isDaemon = true }.start()
        return process
    }

    companion object {
        const val TAG = "Win-LE"
        const val EXE = "ruru-ble-win.exe"

        /**
         * In the installed app (Compose resources folder), RURU_BLE_WIN while developing, or the
         * helper's own build folder when run from the source tree (probe, hardware check).
         */
        fun find(): WinBleHelper? = locate()?.let { WinBleHelper(it) }

        fun locate(): File? {
            System.getenv("RURU_BLE_WIN")?.let { path -> return File(path).takeIf { it.isFile } }
            System.getProperty("compose.application.resources.dir")?.let { dir ->
                File(dir, EXE).takeIf { it.isFile }?.let { return it }
            }
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            while (dir != null) {
                val built = File(dir, "platform/windows/helper/build/$EXE")
                if (built.isFile) return built
                dir = dir.parentFile
            }
            return null
        }
    }
}

/** The helper refused or could not open the link; [failure] has its code and words (English, for logs and the probe). */
class WinBleException(val failure: WinBleProtocol.Failure) : IOException("${failure.words} (${failure.code})")
