package io.github.langkurst.ruru.vendors.sony

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lists every kind of command a Sony session can put on the wire and checks that none of them is
 * firmware update, reset, unpair, test mode, usage logging or any power-off but the user's.
 * A "kind" is table + command byte + inquired type (the bytes after that are values).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SonySafetyTest {

    companion object {
        /** One of every [HeadsetCommand] that Sony does not run: the `when` has no else, so a new command fails to compile here. */
        fun unsupportedCommands(): List<HeadsetCommand> {
            val all = listOf(
                HeadsetCommand.SetMode(0), HeadsetCommand.SaveMode(5, "x", NoiseSettings()), HeadsetCommand.RemoveMode(5),
                HeadsetCommand.SetFavorites(setOf(0)), HeadsetCommand.SetNoise(NoiseSettings()), HeadsetCommand.SetEq(HeadphoneEq(0, 0, 0)),
                HeadsetCommand.SetSidetone(Sidetone.LOW), HeadsetCommand.SetAutoPlayPause(true), HeadsetCommand.SetAutoAnswer(true),
                HeadsetCommand.SetMultipoint(true), HeadsetCommand.SetAutoOff(5), HeadsetCommand.SetVoicePrompts(true),
                HeadsetCommand.SetShortcut(ButtonAction.BATTERY_LEVEL), HeadsetCommand.SetTouchControls(true),
                HeadsetCommand.SetDeviceName("x"), HeadsetCommand.SetLeAudio(true), HeadsetCommand.SetLatency(100),
                HeadsetCommand.SetVolume(5), HeadsetCommand.Transport(TransportControl.PAUSE),
                HeadsetCommand.SwitchDevice("00:00:00:00:00:00"), HeadsetCommand.SetVendorControl("x", 0), HeadsetCommand.PowerOff,
            )
            return all.filter { runsOnSony(it) == false }
        }

        /** True where [SonySession.run] sends something, false where it throws. Exhaustive on purpose. */
        private fun runsOnSony(c: HeadsetCommand): Boolean = when (c) {
            is HeadsetCommand.SetMode, is HeadsetCommand.SetNoise, is HeadsetCommand.SetVendorControl, HeadsetCommand.PowerOff -> true
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites, is HeadsetCommand.SetEq,
            is HeadsetCommand.SetSidetone, is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer,
            is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts,
            is HeadsetCommand.SetShortcut, is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName,
            is HeadsetCommand.SetLeAudio, is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume,
            is HeadsetCommand.Transport, is HeadsetCommand.SwitchDevice,
            -> false
        }

        /** `T1 24 03` style, for readable failures. */
        fun kind(m: SonyMessage): String = "${m.table.name} %02x %02x".format(m.command, m.type)
    }

    /** Table 1 commands that must never leave: firmware update (30-39, 3E, 3F), test (0F), usage logging (C4, C9). */
    private val forbiddenT1Commands = (0x30..0x39).toSet() + setOf(0x3E, 0x3F, 0x0F, 0xC4, 0xC9)

    private fun isForbidden(m: SonyMessage): String? = when {
        m.table == SonyTable.T1 && m.command in forbiddenT1Commands -> "firmware, test or logging"
        // SYSTEM reset settings, face-tap test and head-gesture training.
        m.table == SonyTable.T1 && m.command in setOf(0xF4, 0xF8, 0xFC) && m.type in setOf(0x09, 0x0B, 0x10, 0x04) -> "reset or test"
        // The only power-off is USER_POWER_OFF; 0xFF is the factory power-off.
        m.table == SonyTable.T1 && m.command == 0x24 && !(m.type == 0x03 && m.payload.size == 3 && m.payload[2].toInt() == 0x01) -> "power-off other than the user's"
        // Table 2: pairing mode, source management, connect / disconnect / unpair.
        m.table == SonyTable.T2 && m.command in setOf(0x34, 0x38, 0x3C) -> "pairing, source or unpair"
        // NC test mode.
        m.table == SonyTable.T1 && m.command in setOf(0x60, 0x64, 0x68) && m.type == 0x40 -> "NC test mode"
        // Sound-effect pack uploads and the Classic / LE Audio switch.
        m.table == SonyTable.T1 && m.command in setOf(0x58) && m.type == 0x36 -> "data upload"
        m.table == SonyTable.T1 && m.command == 0xE8 && m.type == 0x05 -> "Classic / LE Audio switch"
        else -> null
    }

    /** Drives every command and every control value, and the Lab, so the headset sees every kind the session can send. */
    private suspend fun TestScope.kindsSent(unit: VirtualSony): List<SonyMessage> {
        val conn = SonyConnection(VirtualSonyTransport(unit), backgroundScope, openDelayMs = 0, ackTimeoutMs = 50, replyTimeoutMs = 500)
        val session = SonySession(conn, backgroundScope)
        var state: HeadphoneState = session.start(HeadphoneState())
        suspend fun run(c: HeadsetCommand) { state = session.run(c, state) }
        // A headset that announces more than the virtual unit answers makes some commands fail: that is fine, only what is sent matters.
        suspend fun tryRun(c: HeadsetCommand) { try { run(c) } catch (_: HeadphoneCommandException) { } }
        session.labProfile()
        session.scanLab()
        for (i in 0..2) tryRun(HeadsetCommand.SetMode(i))
        for (level in listOf(0, 5, 10)) for (on in listOf(false, true)) tryRun(HeadsetCommand.SetNoise(NoiseSettings(level, ancEnabled = on)))
        // Every control the headset announced, at its lowest, highest and each option.
        for (control in state.vendorControls) {
            val values = when {
                control.options != null -> control.options!!.indices.toList()
                control.range != null -> listOf(control.range!!.first, control.range!!.last)
                else -> listOf(0, 1)
            }
            for (v in values) tryRun(HeadsetCommand.SetVendorControl(control.id, v))
        }
        session.pollBattery(state)
        tryRun(HeadsetCommand.PowerOff)
        conn.close()
        return unit.received.toList()
    }

    @Test fun `every outgoing Sony command kind is on the allow-list and none is firmware, reset or unpair`() = runTest {
        val sent = kindsSent(VirtualSony())
        val kinds = sent.map(::kind).toSortedSet()
        println("Sony command kinds sent: $kinds")
        for (m in sent) {
            assertEquals(null, isForbidden(m), "${kind(m)} is ${isForbidden(m)}")
            assertTrue(SonyWriteGuard.isAllowed(m), "${kind(m)} is not on the allow-list")
        }
        assertEquals(EXPECTED_KINDS, kinds, "the list of command kinds the Sony session sends changed: review each new one against the guard")
    }

    @Test fun `the forbidden list really catches what it names`() {
        // The checker above is only worth something if it fails on the dangerous commands.
        val firmware = SonyMessage(SonyTable.T1, byteArrayOf(0x30, 0x00))
        val factoryOff = SonyMessage(SonyTable.T1, byteArrayOf(0x24, 0x03, 0xFF.toByte()))
        val unpair = SonyMessage(SonyTable.T2, byteArrayOf(0x3C, 0x00, 0x02))
        val reset = SonyMessage(SonyTable.T1, byteArrayOf(0xF8.toByte(), 0x09, 0x01))
        for (m in listOf(firmware, factoryOff, unpair, reset)) {
            assertTrue(isForbidden(m) != null, kind(m))
            assertTrue(!SonyWriteGuard.isAllowed(m), "the guard refuses ${kind(m)}")
        }
    }

    @Test fun `nothing a Sony headset announces can make the session send a forbidden kind`() = runTest {
        // A headset that lists every function it could, including the ones that must never be driven.
        val unit = VirtualSony(t1Functions = (0x00..0xFF).toList())
        val sent = kindsSent(unit)
        for (m in sent) assertEquals(null, isForbidden(m), kind(m))
        assertTrue(sent.all { SonyWriteGuard.isAllowed(it) })
    }

    private val EXPECTED_KINDS = sortedSetOf(
        "T1 00 00", "T1 02 00", "T1 04 01", "T1 04 02", "T1 04 03", "T1 06 00", "T1 12 02", "T1 20 05", "T1 22 00", "T1 24 03",
        "T1 26 05", "T1 28 05", "T1 50 00", "T1 52 00", "T1 56 00", "T1 58 00", "T1 60 17", "T1 66 17", "T1 68 17", "T1 94 00",
        "T1 98 00", "T1 d0 d1", "T1 d0 d2", "T1 d6 d1", "T1 d6 d2", "T1 d8 d1", "T1 d8 d2", "T1 e0 01", "T1 e6 00", "T1 e6 01",
        "T1 e8 00", "T1 e8 01", "T1 f6 01", "T1 f6 0c", "T1 f8 01", "T1 f8 0c", "T2 06 00", "T2 46 01", "T2 48 01",
    )
}
