package io.github.langkurst.ruru.vendors.soundcore

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
 * Lists every kind of command a Soundcore session can put on the wire, for every model Ruru has a
 * demo of, and checks that none of them is a firmware update, factory reset, unpair, pairing mode,
 * power off, find tone, reset or any other id outside what the research documents. A "kind" is the
 * command id (category and code); the bytes after it are values, which the guard shape-checks.
 * The kinds are frozen per model below, so a new one is a reviewed change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreSafetyTest {

    companion object {
        /** One of every [HeadsetCommand] that Soundcore does not run: the `when` has no else, so a new command fails to compile here. */
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
            return all.filter { runsOnSoundcore(it) == false }
        }

        /** True where [SoundcoreSession.run] may send something, false where it throws at once. Exhaustive on purpose. */
        private fun runsOnSoundcore(c: HeadsetCommand): Boolean = when (c) {
            is HeadsetCommand.SetMode, is HeadsetCommand.SetNoise, is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetVendorControl -> true
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites, is HeadsetCommand.SetEq,
            is HeadsetCommand.SetSidetone, is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer,
            is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts,
            is HeadsetCommand.SetShortcut, is HeadsetCommand.SetDeviceName, is HeadsetCommand.SetLeAudio,
            is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume, is HeadsetCommand.Transport,
            is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> false
        }

        /** `01 85`, for readable failures. */
        fun kind(p: SoundcorePacket): String = SoundcorePacket.commandName(p.command)
    }

    /**
     * Ids that must never leave, spelled out here and not taken from the guard's own list, so the
     * test does not agree with the guard by construction: unpair, pairing mode, host connect and
     * disconnect, power off (two ids), find tone, button reset, alarm delete, fit test.
     */
    private val forbidden: Map<Int, String> = mapOf(
        0x0B83 to "unpair", 0x0B85 to "pairing mode", 0x0B81 to "host connect", 0x0B82 to "host disconnect",
        0x0185 to "power off", 0x0189 to "power off", 0x1089 to "find tone", 0x0482 to "reset buttons",
        0x1482 to "delete alarm", 0x0901 to "fit test",
    )

    /**
     * Categories that hold firmware update and reset on other makers' stacks and in Soundcore's own
     * app (OTA, restore, factory settings): nothing in them is documented, so nothing in them may be sent.
     */
    private val undocumentedCategories = setOf(0x03, 0x07, 0x08, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x12, 0x13, 0x16, 0x17, 0x18, 0x19, 0xFF)

    private fun isForbidden(p: SoundcorePacket): String? = when {
        p.command in forbidden -> forbidden.getValue(p.command)
        p.isWrite && p.category in undocumentedCategories -> "an undocumented write category"
        // A multipoint write is the one 0B 8x id that is allowed, and only with a switch value.
        p.command == 0x0B84 && !(p.body.size == 1 && p.body[0].toInt() in 0..1) -> "a host command with a body that is not a switch"
        else -> null
    }

    /** Every command and every control value, and the Lab, so the earbuds see every kind the session can send. */
    private suspend fun TestScope.kindsSent(unit: VirtualSoundcore): List<SoundcorePacket> {
        val conn = SoundcoreConnection(AnonymousVirtualSoundcoreTransport(unit), backgroundScope, minWriteGapMs = 0)
        val session = SoundcoreSession(conn, backgroundScope)
        var state: HeadphoneState = session.start(HeadphoneState())
        suspend fun run(c: HeadsetCommand) { state = session.run(c, state) }
        // A command the model refuses or the unit ignores throws: that is fine, only what is sent matters.
        suspend fun tryRun(c: HeadsetCommand) { try { run(c) } catch (_: HeadphoneCommandException) { } }
        session.labProfile()
        session.scanLab()
        for (i in 0..3) tryRun(HeadsetCommand.SetMode(i))
        for (on in listOf(false, true)) tryRun(HeadsetCommand.SetNoise(NoiseSettings(ancEnabled = on)))
        for (on in listOf(false, true)) tryRun(HeadsetCommand.SetTouchControls(on))
        // Every control the earbuds announced, in every mode a control may need, at each option.
        for (mode in listOf(0, 1, 2, 0)) {
            tryRun(HeadsetCommand.SetMode(mode))
            for (control in state.vendorControls.toList()) {
                val values = when {
                    control.options != null -> control.options!!.indices.toList()
                    control.range != null -> listOf(control.range!!.first, control.range!!.last)
                    else -> listOf(0, 1)
                }
                for (v in values) tryRun(HeadsetCommand.SetVendorControl(control.id, v))
            }
        }
        tryRun(HeadsetCommand.SetVendorControl("soundcore_power_off", 1))
        session.pollBattery(state)
        for (c in unsupportedCommands()) tryRun(c)
        conn.close()
        return unit.received.toList()
    }

    private val demoModels: List<SoundcoreModel> = listOf(SoundcoreModel.SLEEP_A30) + SoundcoreModel.entries.filter { it.layout != null }

    @Test fun `every outgoing Soundcore command kind is on the allow-list and none is firmware, reset, unpair or power off`() = runTest {
        for (model in demoModels) {
            val sent = kindsSent(VirtualSoundcore(model))
            val kinds = sent.map(::kind).toSortedSet()
            println("Soundcore ${model.code} command kinds sent: $kinds")
            for (p in sent) {
                assertEquals(null, isForbidden(p), "${model.code}: ${kind(p)} is ${isForbidden(p)}")
                assertTrue(SoundcoreWriteGuard.isAllowed(model, p), "${model.code}: ${kind(p)} is not on the allow-list")
            }
            assertEquals(EXPECTED_KINDS.getValue(model.code), kinds, "${model.code}: the command kinds the session sends changed: review each new one against the guard")
        }
    }

    @Test fun `the forbidden list really catches what it names, and the guard refuses all of it on every model`() {
        // The checker above is only worth something if it fails on the dangerous commands.
        val dangerous = listOf(
            SoundcorePacket(0x0B83, byteArrayOf(1)), SoundcorePacket(0x0B85, byteArrayOf(1)), SoundcorePacket(0x0189, byteArrayOf(1)),
            SoundcorePacket(0x0185, byteArrayOf(1)), SoundcorePacket(0x1089, byteArrayOf(1)), SoundcorePacket(0x0482, byteArrayOf(1)),
            SoundcorePacket(0x1482, byteArrayOf(1)), SoundcorePacket(0x0901, byteArrayOf(1)), SoundcorePacket(0x0B81, byteArrayOf(1)),
            // An OTA / factory-reset shaped write in an undocumented category, and a host command that is not a switch.
            SoundcorePacket(0x0381, byteArrayOf(1)), SoundcorePacket(0x1881, byteArrayOf(1)), SoundcorePacket(0x0B84, byteArrayOf(5)),
        )
        for (p in dangerous) {
            assertTrue(isForbidden(p) != null, kind(p))
            for (model in demoModels + listOf(null)) {
                assertTrue(!SoundcoreWriteGuard.isAllowed(model, p), "the guard refuses ${kind(p)} for ${model?.code}")
            }
        }
        // And the checker lets the documented kinds through.
        assertEquals(null, isForbidden(SoundcorePacket(SoundcoreCommands.SET_SOUND_MODES, ByteArray(7))))
        assertEquals(null, isForbidden(SoundcorePacket(SoundcoreCommands.SET_MULTIPOINT, byteArrayOf(1))))
    }

    @Test fun `a command that bypasses the session is caught by the connection's guard, and by this test if the guard were loosened`() = runTest {
        val unit = VirtualSoundcore(SoundcoreModel.LIBERTY_4_NC)
        val conn = SoundcoreConnection(AnonymousVirtualSoundcoreTransport(unit), backgroundScope, minWriteGapMs = 0)
        val session = SoundcoreSession(conn, backgroundScope)
        session.start(HeadphoneState())
        for ((command, _) in forbidden) {
            try {
                conn.request(command, byteArrayOf(1))
            } catch (_: HeadphoneCommandException) {
            }
        }
        // The guard stopped all of them: the unit saw only the state read. Were it loosened, the unit would have received
        // forbidden kinds and the assertion below (the one the main test makes on every frame) would name them.
        val leaked = unit.received.mapNotNull { p -> isForbidden(p)?.let { "${kind(p)} ($it)" } }
        assertEquals(emptyList(), leaked)
        conn.close()
    }

    @Test fun `nothing a headset can announce makes the session send a forbidden kind`() = runTest {
        // A model with every setting the tables have, plus a state that is read-only: neither can be talked into more.
        for (model in listOf(SoundcoreModel.LIBERTY_5, SoundcoreModel.SPACE_2, SoundcoreModel.SPACE_ONE)) {
            val sent = kindsSent(VirtualSoundcore(model, ignoreWrites = true))
            for (p in sent) assertEquals(null, isForbidden(p), "${model.code}: ${kind(p)}")
            assertTrue(sent.all { SoundcoreWriteGuard.isAllowed(model, it) })
        }
    }

    /** Per model code: every command id the session can put on the wire, Lab reads included (01 01 state, 01 05 info, 05 01 capability table). */
    private val EXPECTED_KINDS: Map<String, Set<String>> = mapOf(
        // Sleep A30: noise cancelling, tap controls, listening mode, the sleep options and prompts, and its reads (alarms, sleep settings).
        "D1301" to sortedSetOf("01 01", "01 05", "01 a9", "05 01", "06 87", "10 75", "10 77", "10 82", "10 94", "10 95", "10 9e", "10 9f", "14 01", "15 03", "15 85", "15 8f"),
        "A3035" to sortedSetOf("01 01", "01 05", "01 86", "06 81", "0b 84"),
        "A3062" to sortedSetOf("01 01", "01 05", "01 86", "06 81", "0b 84"),
        "A3040" to sortedSetOf("01 01", "01 05", "01 86", "06 81", "0b 84"),
        "D1402" to sortedSetOf("01 01", "01 05"),
        "A3947" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "06 81", "0b 84", "10 82", "10 85", "10 86"),
        "A3957" to sortedSetOf("01 01", "01 05", "01 81", "01 83", "01 86", "01 8c", "05 01", "05 81", "06 81", "0b 84", "10 85"),
        "A3955" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "05 01", "05 81", "06 81", "0b 84", "10 82"),
        "A3959" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "01 87", "06 81", "0b 84"),
        "A3958" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "02 86", "06 81"),
        "D1202" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "05 01", "05 81", "06 81", "0b 84", "10 82"),
        "D1202C" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "05 01", "05 81", "06 81", "0b 84", "10 82"),
        "A3936" to sortedSetOf("01 01", "01 05", "01 83", "01 86", "01 87", "06 81", "0b 84"),
        "A3952" to sortedSetOf("01 01", "01 05", "01 81", "01 83", "01 86", "01 8c", "06 81"),
        "A3953" to sortedSetOf("01 01", "01 05"),
        "A3954" to sortedSetOf("01 01", "01 05"),
    )
}
