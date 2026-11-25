package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.platform.windows.WinAdverts
import io.github.langkurst.ruru.platform.windows.WinBleHelper
import io.github.langkurst.ruru.platform.windows.WinBluetooth
import io.github.langkurst.ruru.platform.windows.WindowsHeadsetPlatform
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The probe's control channel: the RFCOMM socket, or Bose over Bluetooth LE through the helper (`--le`). */
interface ProbeLink : Closeable {
    fun write(bytes: ByteArray)

    /** The next chunk of bytes, an empty array when nothing came for a short while, or null at end of stream. */
    fun read(): ByteArray?
}

class RfcommLink(private val socket: WinBluetooth.RfcommSocket) : ProbeLink {
    override fun write(bytes: ByteArray) = socket.write(bytes)
    override fun read(): ByteArray? = socket.read()
    override fun close() = socket.close()
}

/**
 * Bose over Bluetooth LE (service FEBE), opened the way the app opens it with `--le`: the helper's
 * list of Bose devices paired over LE, the first one, [LinkRoute.BoseLe]. Whole BMAP packets come
 * out of [read]. Prints no address and no other device's name.
 */
class LeLink private constructor(private val transport: ByteTransport) : ProbeLink {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var ended = false

    init {
        thread(isDaemon = true, name = "probe-le") {
            runCatching { runBlocking { transport.incoming.collect { queue.put(it) } } }
            ended = true
        }
    }

    override fun write(bytes: ByteArray) = runBlocking { transport.write(bytes) }

    override fun read(): ByteArray? {
        queue.poll(250, TimeUnit.MILLISECONDS)?.let { return it }
        return if (ended && queue.isEmpty()) null else ByteArray(0)
    }

    override fun close() = transport.close()

    companion object {
        /** Opens the link, or prints a RESULT line and returns null. */
        fun open(): LeLink? {
            val helper = WinBleHelper.find() ?: run {
                println("RESULT: the Bluetooth LE helper ${WinBleHelper.EXE} was not found. Build it with " +
                    "pwsh platform/windows/helper/build.ps1, or point RURU_BLE_WIN at it.")
                return null
            }
            val platform = WindowsHeadsetPlatform(boseOverLe = true, ble = helper)
            return runBlocking {
                // Active listening, so a name in a scan response counts too. Headphones already linked
                // to this PC over LE usually stop advertising, so "none named Bose" is not a failure.
                val heard = runCatching { WinAdverts.summary(helper.scan(4, active = true)) }.getOrElse { "listening failed: ${it.message}" }
                println("Bluetooth LE adverts (4 s): $heard")
                val candidate = runCatching { platform.headsets() }.getOrElse {
                    println("RESULT: could not read Windows' Bluetooth LE list: ${it.message}")
                    return@runBlocking null
                }.firstOrNull() ?: run {
                    println("RESULT: no Bose headphones paired with this computer over Bluetooth LE")
                    return@runBlocking null
                }
                println("Headset: ${candidate.name} (Bluetooth LE, ${if (candidate.connected) "connected" else "paired, not connected"})")
                try {
                    LeLink(platform.open(candidate, LinkRoute.BoseLe))
                } catch (e: Exception) {
                    println("RESULT: could not open Bose's LE service: ${e.message}")
                    null
                }
            }
        }
    }
}
