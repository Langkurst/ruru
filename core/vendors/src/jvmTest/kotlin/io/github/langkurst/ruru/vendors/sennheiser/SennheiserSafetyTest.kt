package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.TransportControl
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.gaia.GaiaCommand
import io.github.langkurst.ruru.vendors.gaia.GaiaFrame
import io.github.langkurst.ruru.vendors.gaia.GaiaVendor
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserCommands as C
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lists every kind of command a Sennheiser session can put on the wire and checks that none of
 * them is firmware update, factory reset, reboot, role switch, unpair, rename or anything else
 * outside Earshot's research. A "kind" is vendor + command word (the bytes after that are values).
 * The forbidden list below is written out again here on purpose: it does not read the guard's own,
 * so loosening the guard makes this test fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SennheiserSafetyTest {

    companion object {
        /** One of every [HeadsetCommand] that Sennheiser does not run: the `when` has no else, so a new command fails to compile here. */
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
            return all.filter { runsOnSennheiser(it) == false }
        }

        /** True where [SennheiserSession.run] sends something, false where it throws. Exhaustive on purpose. */
        private fun runsOnSennheiser(c: HeadsetCommand): Boolean = when (c) {
            is HeadsetCommand.SetMode, is HeadsetCommand.SetNoise, is HeadsetCommand.SetEq, is HeadsetCommand.SetVendorControl -> true
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
            is HeadsetCommand.SetSidetone, is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer,
            is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts,
            is HeadsetCommand.SetShortcut, is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName,
            is HeadsetCommand.SetLeAudio, is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume,
            is HeadsetCommand.Transport, is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> false
        }

        /** `0495/1a04` style, for readable failures. */
        fun kind(f: GaiaFrame): String = GaiaCommand.name(f.vendor, f.command)
    }

    /** Sennheiser commands that must never leave, by command id. */
    private val forbiddenSennheiser = mapOf(
        0x0040 to "factory reset", 0x060F to "reboot", 0x1C00 to "role switch", 0x1403 to "disconnect a paired device",
        0x1405 to "delete a pairing", 0x1406 to "delete every pairing", 0x080E to "raw sensor data", 0x080F to "sensor calibration",
        0x2009 to "hearing profile reset", 0x1604 to "button layout reset", 0x2801 to "rename", 0x1600 to "button mapping",
    )

    /** Features whose every command is firmware, role switch or MCU upgrade: Sennheiser 1, 14, 18. */
    private val forbiddenSennheiserFeatures = setOf(1, 14, 18)

    /** Qualcomm: only the three discovery reads (0x0000 API version, 0x0001 / 0x0002 feature lists) may go out. */
    private val qualcommReads = setOf(0x0000, 0x0001, 0x0002)

    private fun isForbidden(f: GaiaFrame): String? = when {
        GaiaCommand.type(f.command) != GaiaCommand.TYPE_COMMAND -> "not a request"
        f.vendor == GaiaVendor.QUALCOMM && f.command !in qualcommReads -> "Qualcomm command other than discovery"
        f.vendor == GaiaVendor.QUALCOMM -> null
        f.vendor != C.VENDOR -> "unknown vendor"
        f.command in forbiddenSennheiser -> forbiddenSennheiser.getValue(f.command)
        GaiaCommand.feature(f.command) in forbiddenSennheiserFeatures -> "firmware update, role switch or MCU upgrade"
        else -> null
    }

    /**
     * Drives every command and every control value, and the Lab, on a headset of [unit]'s kind, so
     * the headset sees every kind the session can send.
     */
    private suspend fun TestScope.kindsSent(unit: VirtualSennheiser, name: String): List<GaiaFrame> {
        val conn = SennheiserConnection(VirtualSennheiserTransport(unit), backgroundScope)
        val session = SennheiserSession(conn, name, clock = { 0L })
        var state: HeadphoneState = session.start(HeadphoneState())
        // A headset that announces more than the virtual unit answers makes some commands fail: that is fine, only what is sent matters.
        suspend fun tryRun(c: HeadsetCommand) { try { state = session.run(c, state) } catch (_: HeadphoneCommandException) { } }
        session.labProfile()
        session.scanLab()
        for (i in 0..3) tryRun(HeadsetCommand.SetMode(i))
        for (level in listOf(0, 5, 10)) for (on in listOf(false, true)) tryRun(HeadsetCommand.SetNoise(NoiseSettings(level, ancEnabled = on)))
        for (eq in listOf(HeadphoneEq(3, 0, 0), HeadphoneEq(0, -4, 0), HeadphoneEq(0, 0, 5), HeadphoneEq(0, 0, 0))) tryRun(HeadsetCommand.SetEq(eq))
        // Every control the headset announced, at each value (switches 0 and 1, choices each option).
        for (control in state.vendorControls) {
            val values = when {
                control.options != null -> control.options!!.indices.toList()
                control.range != null -> listOf(control.range!!.first, control.range!!.last)
                else -> listOf(0, 1)
            }
            for (v in values) tryRun(HeadsetCommand.SetVendorControl(control.id, v))
        }
        for (command in unsupportedCommands()) tryRun(command)
        session.pollBattery(state)
        conn.close()
        return unit.received.toList()
    }

    /** One virtual headset per family of controls: a slider headband, its newest sibling, earbuds and the toggles-only Accentum. */
    private val headsets = listOf(
        "MOMENTUM 4" to { VirtualSennheiser() },
        "MOMENTUM 5" to { VirtualSennheiser(modelId = "M5AEBT Black") },
        "ACCENTUM Plus" to { VirtualSennheiser(modelId = "ACPAEBT White") },
        "MOMENTUM TW 4" to { VirtualSennheiser(modelId = "MTW4 Black Copper") },
        "ACCENTUM" to { VirtualSennheiser(modelId = "ACAEBT Black") },
    )

    @Test fun `every outgoing Sennheiser command kind is on the allow-list and none is firmware, reset, role switch or unpair`() = runTest {
        val kinds = sortedSetOf<String>()
        for ((name, make) in headsets) {
            val sent = kindsSent(make(), name)
            for (f in sent) {
                assertEquals(null, isForbidden(f), "$name: ${kind(f)} is ${isForbidden(f)}")
                assertNull(SennheiserWriteGuard.refusal(f, VirtualSennheiser.M4_FEATURES), "$name: ${kind(f)} is not on the allow-list")
                kinds += kind(f)
            }
        }
        println("Sennheiser command kinds sent: $kinds")
        assertEquals(EXPECTED_KINDS, kinds, "the list of command kinds the Sennheiser session sends changed: review each new one against the guard")
    }

    @Test fun `nothing a Sennheiser headset announces can make the session send a forbidden kind`() = runTest {
        // A headset that lists every feature there could be, including firmware, role switch and MCU upgrade.
        val everything = (0..30).associateWith { 6 }
        for ((name, make) in headsets) {
            val unit = make().let { VirtualSennheiser(modelId = it.modelId, features = everything) }
            val sent = kindsSent(unit, name)
            assertTrue(sent.isNotEmpty())
            for (f in sent) assertEquals(null, isForbidden(f), "$name: ${kind(f)}")
        }
    }

    @Test fun `the forbidden list really catches what it names`() {
        // The checker above is only worth something if it fails on the dangerous commands, and the guard must agree.
        val everything = (0..30).associateWith { 6 }
        fun sen(command: Int, vararg p: Int) = GaiaFrame(C.VENDOR, command, ByteArray(p.size) { p[it].toByte() })
        fun qc(command: Int, vararg p: Int) = GaiaFrame(GaiaVendor.QUALCOMM, command, ByteArray(p.size) { p[it].toByte() })
        val dangerous = listOf(
            sen(0x0040), // factory reset
            sen(0x0200), sen(0x0201, 0, 0, 0x10, 0), // firmware update, feature 1
            qc(0x0C00), qc(0x0C01), qc(0x0C02), // firmware update, Qualcomm feature 6
            sen(0x060F), // reboot
            sen(0x1C00), // role switch
            sen(0x1403, 3), sen(0x1405, 0), sen(0x1406), // pairing list
            sen(0x2009), sen(0x1604), sen(0x2801, 0x41), // hearing profile reset, button reset, rename
            sen(0x1600, 0, 1, 8), sen(0x1600, 0, 1, 19), // button mapped to power off / disconnect every device
            qc(0x0803), qc(0x0804), qc(0x0009), qc(0x000A, 0, 0), qc(0x000D, 1, 4), qc(0x0601, 2), // debug, data transfer, assistant
        )
        for (f in dangerous) {
            assertNotNull(isForbidden(f), kind(f))
            assertNotNull(SennheiserWriteGuard.refusal(f, everything), "the guard refuses ${kind(f)}")
        }
    }

    @Test fun `the expected kinds are reads and writes of settings, each one named`() {
        // Reviewed by hand: the discovery reads, the push registration (0x0007), the GETs and 14 SETs.
        // GAIA's even/odd habit has exceptions (wear state 0x0402, EQ layout 0x1000), so both lists are spelled out.
        assertEquals(EXPECTED_KINDS, (READS + WRITES).toSortedSet())
        assertTrue(READS.intersect(WRITES).isEmpty())
        assertEquals(14, WRITES.size)
    }

    private val READS = listOf(
        "001d/0000", "001d/0001", "0495/0001", "0495/0007", "0495/0401", "0495/0402", "0495/0601", "0495/0602", "0495/0603",
        "0495/0806", "0495/080b", "0495/080d", "0495/0815", "0495/1000", "0495/1003", "0495/1009", "0495/1201", "0495/1206",
        "0495/1801", "0495/1803", "0495/1805", "0495/1a01", "0495/1a03", "0495/1a05",
    )

    private val WRITES = listOf(
        "0495/0400", "0495/0600", "0495/0805", "0495/080a", "0495/080c", "0495/0814", "0495/1001", "0495/1008",
        "0495/1800", "0495/1802", "0495/1804", "0495/1a00", "0495/1a02", "0495/1a04",
    )

    private val EXPECTED_KINDS = sortedSetOf(
        "001d/0000", "001d/0001", "0495/0001", "0495/0007", "0495/0400", "0495/0401", "0495/0402", "0495/0600", "0495/0601",
        "0495/0602", "0495/0603", "0495/0805", "0495/0806", "0495/080a", "0495/080b", "0495/080c", "0495/080d", "0495/0814",
        "0495/0815", "0495/1000", "0495/1001", "0495/1003", "0495/1008", "0495/1009", "0495/1201", "0495/1206", "0495/1800",
        "0495/1801", "0495/1802", "0495/1803", "0495/1804", "0495/1805", "0495/1a00", "0495/1a01", "0495/1a02", "0495/1a03",
        "0495/1a04", "0495/1a05",
    )
}
