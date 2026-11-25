package io.github.langkurst.ruru.vendors.bose

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.VoicePrompts
import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.link.Logging
import io.github.langkurst.ruru.session.QcUltra2Protocol
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same scripted session twice against the simulated 1st Gen: once with the calls Ruru 0.2's
 * controller made on QcUltra2Protocol, once through BoseSession. The frames on the wire must match
 * one for one. (The app's BoseFramesGoldenTest checks the whole controller against a recorded list.)
 */
class BoseSessionFramesTest {
    /** What a script step does with the state, given the old calls or the session. */
    private interface Driver {
        suspend fun readAddress(): String?
        suspend fun connect(s: HeadphoneState): HeadphoneState
        suspend fun battery(s: HeadphoneState): HeadphoneState
        suspend fun run(command: HeadsetCommand, s: HeadphoneState): HeadphoneState
    }

    private suspend fun script(d: Driver) {
        d.readAddress()
        var s = d.connect(HeadphoneState())
        val original = s.currentModeIndex!!
        val other = s.modes.first { it.isConfigured && it.index != original }.index
        val eq = s.eq!!
        val steps = listOf(
            HeadsetCommand.SetMode(other),
            HeadsetCommand.SetEq(eq.copy(bass = if (eq.bass < 10) eq.bass + 1 else eq.bass - 1)),
            HeadsetCommand.SetNoise(NoiseSettings(cancellingLevel = 6)),
            HeadsetCommand.SetSidetone(Sidetone.LOW),
            HeadsetCommand.SetMultipoint(s.multipointEnabled != true),
            HeadsetCommand.SetAutoPlayPause(s.autoPlayPause != true),
            HeadsetCommand.SetAutoAnswer(s.autoAnswer != true),
            HeadsetCommand.SetAutoOff(20),
            HeadsetCommand.SetShortcut(ButtonAction.BATTERY_LEVEL),
            HeadsetCommand.SetVoicePrompts(s.voicePrompts?.enabled != true),
            HeadsetCommand.SaveMode(5, "Gym", NoiseSettings(cancellingLevel = 4)),
            HeadsetCommand.RemoveMode(5),
            HeadsetCommand.SetMode(original),
            HeadsetCommand.SetEq(eq),
        )
        for (step in steps) {
            s = try {
                d.run(step, s)
            } catch (e: Exception) {
                s // the same refusal happens both ways; the frames sent before it still count
            }
        }
        d.battery(s)
    }

    private fun record(drive: (BmapConnection) -> Driver): List<String> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val device = VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1)
            device.powerOn()
            val transport = VirtualTransport(device, random = Random(3))
            val conn = BmapConnection(transport, scope)
            script(drive(conn))
            conn.close()
            transport.sentRequests().map { p -> p.encode().joinToString("") { "%02x".format(it) } }
        } finally {
            scope.cancel()
        }
    }

    /** Ruru 0.2's controller, call for call (HeadsetController before 0.3). */
    private fun oldCalls(c: BmapConnection) = object : Driver {
        val protocol = QcUltra2Protocol()
        override suspend fun readAddress() = protocol.readAddress(c)
        override suspend fun connect(s: HeadphoneState) = protocol.refresh(c, protocol.onConnected(c, s))
        override suspend fun battery(s: HeadphoneState) = protocol.pollBattery(c, s)
        override suspend fun run(command: HeadsetCommand, s: HeadphoneState): HeadphoneState = when (command) {
            is HeadsetCommand.SetMode -> protocol.setMode(c, s, command.index)
            is HeadsetCommand.SetEq -> protocol.setEq(c, s, command.eq)
            is HeadsetCommand.SetNoise -> protocol.setNoise(c, s, command.settings)
            is HeadsetCommand.SetSidetone -> protocol.setSidetone(c, s, command.level, s.sidetonePersistent ?: true)
            is HeadsetCommand.SetMultipoint -> protocol.setMultipoint(c, s, command.on)
            is HeadsetCommand.SetAutoPlayPause -> protocol.setAutoPlayPause(c, s, command.on)
            is HeadsetCommand.SetAutoAnswer -> protocol.setAutoAnswer(c, s, command.on)
            is HeadsetCommand.SetAutoOff -> protocol.setAutoOff(c, s, command.minutes)
            is HeadsetCommand.SetShortcut -> protocol.setShortcutButton(c, s, command.action)
            is HeadsetCommand.SetVoicePrompts ->
                protocol.setVoicePrompts(c, s, (s.voicePrompts ?: VoicePrompts(enabled = command.on, languageId = 1)).copy(enabled = command.on))
            is HeadsetCommand.SaveMode -> protocol.saveCustomMode(c, s, command.index, command.name, command.settings)
            is HeadsetCommand.RemoveMode -> protocol.resetCustomMode(c, s, command.index)
            else -> error("not in the script: $command")
        }
    }

    private fun session(c: BmapConnection) = object : Driver {
        val session = BoseSession(c)
        override suspend fun readAddress() = session.readIdentityAddress()
        override suspend fun connect(s: HeadphoneState) = session.start(s)
        override suspend fun battery(s: HeadphoneState) = session.pollBattery(s)
        override suspend fun run(command: HeadsetCommand, s: HeadphoneState) = session.run(command, s)
    }

    @Test
    fun `BoseSession sends exactly the frames Ruru 0_2's calls sent`() {
        Logging.sink = { _, _, _, _ -> }
        val before = record(::oldCalls)
        val after = record(::session)
        assertTrue(before.size > 40, "the script sent only ${before.size} frames")
        assertEquals(before.joinToString("\n"), after.joinToString("\n"))
    }
}
