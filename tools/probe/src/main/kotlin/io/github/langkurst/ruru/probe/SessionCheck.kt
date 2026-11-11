package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.platform.windows.WinBluetooth
import io.github.langkurst.ruru.platform.windows.WinRfcommTransport
import io.github.langkurst.ruru.session.QcUltra2Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * The full shared session (Earshot's connection and Bose protocol logic, now in Ruru's common
 * code) on a real headset over the Windows transport: connect, read everything, then listen for
 * a few seconds of pushes. Changes nothing a user would see. No address or serial is printed.
 */
object SessionCheck {
    fun run(device: WinBluetooth.Device, listenMs: Long) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val transport = WinRfcommTransport.open(device)
        val connection = BmapConnection(transport, scope)
        val protocol = QcUltra2Protocol()
        try {
            var s = protocol.onConnected(connection, HeadphoneState())
            println("Connected and greeted [${ms()} ms]")
            s = protocol.refresh(connection, s)
            println("Full refresh done [${ms()} ms]\n")
            show(s)

            println("\nListening for ${listenMs / 1000} s: press a button or change the mode on the headset to see pushes")
            var state = s
            connection.unsolicited.onEach { frame ->
                protocol.onUnsolicited(frame, state)?.let { next ->
                    if (next.currentModeIndex != state.currentModeIndex) println("  push: mode -> ${next.modes.firstOrNull { it.index == next.currentModeIndex }?.name ?: next.currentModeIndex}")
                    if (next.batteryPercent != state.batteryPercent) println("  push: battery -> ${next.batteryPercent} %")
                    if (next.noise != state.noise) println("  push: noise -> ${next.noise}")
                    if (next.lastButtonPress != state.lastButtonPress) println("  push: button -> ${next.lastButtonPress}")
                    state = next
                } ?: println("  push: ${frame.address} ${frame.operator} (not used)")
            }.launchIn(scope)
            delay(listenMs)
            println("\nRESULT: session OK, ${connection.traffic().deviceFrames} frames, link open=${connection.isOpen}")
        } catch (e: Exception) {
            println("RESULT: session failed: ${e.message}")
        } finally {
            connection.close()
            scope.cancel()
        }
    }

    private fun show(s: HeadphoneState) {
        val info = s.info
        val caps = s.capabilities
        println("Model           ${info?.modelName}  (family ${info?.family}, generation ${info?.generation ?: "-"})")
        println("Firmware        ${info?.firmwareVersion}   BMAP ${info?.bmapVersion}")
        println("Battery         ${s.batteryPercent} %   charging=${s.isCharging}")
        println("Current mode    ${s.modes.firstOrNull { it.index == s.currentModeIndex }?.name ?: s.currentModeIndex}")
        println("Modes           ${s.modes.joinToString { (if (it.isFavorite) "*" else "") + it.name + if (it.isPreset) "" else "(${it.index})" }}")
        println("Noise           ${s.noise}")
        println("EQ              ${s.eq}   range ${s.eqRange}")
        println("Self voice      ${s.sidetone}   Multipoint ${s.multipointEnabled}   Auto-off ${s.autoOffMinutes} min")
        println("Auto play/pause ${s.autoPlayPause}   Auto answer ${s.autoAnswer}   Voice prompts ${s.voicePrompts}")
        println("Shortcut button ${s.shortcutButton}")
        println("Paired devices  ${s.pairedDevices.size} (names and addresses not printed)")
        println("Live pushes     ${s.livePushEnabled}")
        println("Capabilities    live noise=${caps.liveNoiseControl} ANC switch=${caps.ancToggle} ANC always on=${caps.ancAlwaysOn} " +
            "wind=${caps.windBlock} spatial=${caps.spatialAudio} editable modes=${caps.editableModes} verified=${caps.verified}")
    }
}
