package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.platform.windows.WinBluetooth
import io.github.langkurst.ruru.protocol.BmapFrameBuffer
import io.github.langkurst.ruru.protocol.BmapOperator
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.protocol.FunctionAddress
import io.github.langkurst.ruru.protocol.bose.AudioSettings
import io.github.langkurst.ruru.protocol.bose.Battery
import io.github.langkurst.ruru.protocol.bose.BoseModel
import io.github.langkurst.ruru.protocol.bose.Buttons
import io.github.langkurst.ruru.protocol.bose.Cnc
import io.github.langkurst.ruru.protocol.bose.CurrentMode
import io.github.langkurst.ruru.protocol.bose.Eq
import io.github.langkurst.ruru.protocol.bose.FavoritesCodec
import io.github.langkurst.ruru.protocol.bose.LeAudioControl
import io.github.langkurst.ruru.protocol.bose.ModeConfigs
import io.github.langkurst.ruru.protocol.bose.ModesCapabilities
import io.github.langkurst.ruru.protocol.bose.Multipoint
import io.github.langkurst.ruru.protocol.bose.ProductInfo
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.protocol.bose.Sidetone
import io.github.langkurst.ruru.protocol.bose.StandbyTimer
import io.github.langkurst.ruru.protocol.bose.Toggle
import io.github.langkurst.ruru.protocol.bose.TouchControls
import io.github.langkurst.ruru.protocol.bose.VoicePrompts
import io.github.langkurst.ruru.protocol.toHex
import kotlin.system.exitProcess

/**
 * Read-only check of a Bose headset from a Windows PC, using Ruru's shared protocol code.
 * Sends GET requests only, so nothing on the headset changes. Prints no Bluetooth address and
 * no serial number, so the output is safe to paste into a bug report.
 */
fun main(args: Array<String>) {
    val started = System.nanoTime()
    fun ms() = (System.nanoTime() - started) / 1_000_000

    val bose = WinBluetooth.pairedDevices()
        .filter { it.name.contains("Bose", ignoreCase = true) }
        .sortedByDescending { it.connected }
    val device = bose.firstOrNull() ?: run {
        println("RESULT: no paired Bose headset found in Windows' Bluetooth list")
        exitProcess(2)
    }
    println("Headset: ${device.name} (${if (device.connected) "connected" else "paired, not connected"})")

    if ("--session" in args) {
        SessionCheck.run(device, listenMs = 8_000)
        return
    }

    val socket = try {
        WinBluetooth.openSerialPort(device, readTimeoutMs = 300)
    } catch (e: Exception) {
        println("RESULT: ${e.message}")
        exitProcess(3)
    }
    println("Control channel open [${ms()} ms]")

    if ("--write-test" in args) {
        socket.use { WriteTest(ProbeClient(it)).run() }
        return
    }

    socket.use { s ->
        val frames = BmapFrameBuffer()
        val queue = ArrayDeque<BmapPacket>()

        fun pump(untilMs: Long, stop: (BmapPacket) -> Boolean): BmapPacket? {
            while (true) {
                queue.removeFirstOrNull()?.let { if (stop(it)) return it }
                if (ms() >= untilMs) return null
                val chunk = s.read() ?: return null
                if (chunk.isNotEmpty()) queue.addAll(frames.feed(chunk))
            }
        }

        // Whatever the headset says on its own right after the channel opens.
        var greeting = 0
        pump(ms() + 400) { greeting++; false }
        println("Greeting frames: $greeting")

        fun get(address: FunctionAddress, payload: ByteArray = BmapPacket.EMPTY): BmapPacket? {
            s.write(BmapPacket(address.block, address.function, BmapOperator.GET, payload).encode())
            return pump(ms() + 2_000) {
                it.matches(address) && (it.operator == BmapOperator.STATUS || it.operator == BmapOperator.ERROR)
            }
        }

        var answered = 0
        var asked = 0
        fun read(label: String, address: FunctionAddress, payload: ByteArray = BmapPacket.EMPTY, decode: (ByteArray) -> Any?): ByteArray? {
            asked++
            val t0 = ms()
            val reply = get(address, payload)
            val took = ms() - t0
            val shown = when {
                reply == null -> "no reply"
                reply.isError -> "error ${reply.errorCode} (${reply.error?.description ?: "unknown"})"
                else -> runCatching { decode(reply.payload) }.getOrElse { "parse failed: ${it.message}" }
                    .toString() + "   raw=${reply.payload.toHex()}"
            }
            if (reply != null && !reply.isError) answered++
            println("%-8s %-26s %s  (%d ms)".format(address.toString(), label, shown, took))
            return reply?.takeIf { !it.isError }?.payload
        }

        println()
        println("== Identity")
        read("BMAP version", QcUltra2.BMAP_VERSION) { ProductInfo.parseBmapVersion(it) }
        val idPayload = read("Product id", QcUltra2.PRODUCT_ID_VARIANT) { p ->
            ProductInfo.parseProductIdVariant(p)?.let { "0x%04X variant %d".format(it.productId, it.variant) }
        }
        val model = idPayload?.let { ProductInfo.parseProductIdVariant(it)?.model }
        println("         Ruru's model table: ${model?.let { "${it.name} (${it.displayName}), verified=${it.verified}" } ?: "unknown product"}")
        read("Firmware", QcUltra2.FIRMWARE_VERSION) { ProductInfo.parseFirmwareVersion(it) }
        read("Factory name", QcUltra2.PRODUCT_NAME) { ProductInfo.parseString(it) }
        read("Function blocks", QcUltra2.ALL_FUNCTION_BLOCKS) { ProductInfo.parseAllFunctionBlocks(it)?.sorted() }
        read("LE Audio supported", QcUltra2.LE_AUDIO_SUPPORTED) { ProductInfo.parseLeAudioSupported(it) }

        println()
        println("== Status")
        read("Battery", QcUltra2.BATTERY) { Battery.parseReadings(it) }
        read("On charger", QcUltra2.CHARGER_DETECT) { Toggle.parse(it) }

        println()
        println("== Listening modes")
        val capsPayload = read("Mode capabilities", QcUltra2.MODES_CAPABILITIES) { ModesCapabilities.parse(it) }
        val caps = capsPayload?.let { ModesCapabilities.parse(it) }
        read("Current mode", QcUltra2.CURRENT_MODE) { CurrentMode.parse(it) }
        read("Favourites", QcUltra2.FAVORITES) { FavoritesCodec.parse(it) }
        read("Live audio settings", QcUltra2.AUDIO_SETTINGS) { AudioSettings.parse(it) }
        read("Noise cancelling", QcUltra2.CNC) { p -> Cnc.parse(p)?.let { "$it uiLevel=${it.cancellingLevel}" } }
        val slots = caps?.totalModes ?: model?.slotCount ?: 0
        for (i in 0 until slots) {
            read("Mode slot $i", QcUltra2.MODE_CONFIG, ModeConfigs.getPayload(i)) { p ->
                val c = ModeConfigs.parse(p)
                "${p.size}B ${c?.let { "'${it.name}' prompt=${it.promptBytes} cnc=${it.cncWire} autoCnc=${it.autoCnc} spatial=${it.spatialCode} wind=${it.windBlock} anc=${it.ancEnabled} editable=${it.editable} configured=${it.configured} changeable=0x%02x".format(it.changeableFlags ?: 0) } ?: "unparsed"}"
            }
        }

        println()
        println("== Settings")
        read("Headset EQ", QcUltra2.EQ) { Eq.parse(it) }
        read("Buttons", QcUltra2.BUTTONS) { Buttons.parse(it) }
        read("Multipoint", QcUltra2.MULTIPOINT) { Multipoint.parse(it) }
        read("Self voice", QcUltra2.SIDETONE) { Sidetone.parse(it) }
        read("Voice prompts", QcUltra2.VOICE_PROMPTS) { VoicePrompts.parse(it) }
        read("Auto-off timer", QcUltra2.STANDBY_TIMER) { StandbyTimer.parse(it) }
        read("Auto play/pause", QcUltra2.AUTO_PLAY_PAUSE) { Toggle.parse(it) }
        read("Auto answer", QcUltra2.AUTO_ANSWER) { Toggle.parse(it) }
        read("Touch controls", QcUltra2.DISABLE_CAPTOUCH) { TouchControls.parseEnabled(it) }
        read("LE Audio switch", QcUltra2.LE_AUDIO_CONTROL) { LeAudioControl.parse(it) }

        println()
        println("RESULT: $answered of $asked reads answered, ${frames.desyncCount} framing errors, total ${ms()} ms")
    }
}
