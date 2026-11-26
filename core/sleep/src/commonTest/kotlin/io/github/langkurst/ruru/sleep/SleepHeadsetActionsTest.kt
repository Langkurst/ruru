package io.github.langkurst.ruru.sleep

import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.VendorControl
import io.github.langkurst.ruru.core.VoicePrompts
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The night's headset steps on three kinds of headset: a Bose-like one, a Sleep A30-like one, and one with no driver. */
class SleepHeadsetActionsTest {

    /** Records the commands and applies them to its own state, as a session would. */
    private class TestHeadset(var state: HeadphoneState, private val refusePowerOff: Boolean = true) : HeadsetCommandRunner {
        val commands = ArrayList<HeadsetCommand>()

        override suspend fun run(command: HeadsetCommand) {
            commands += command
            when (command) {
                is HeadsetCommand.SetMode -> state = state.copy(currentModeIndex = command.index)
                is HeadsetCommand.SetNoise -> state = state.copy(noise = command.settings)
                is HeadsetCommand.SetTouchControls -> state = state.copy(touchControlsEnabled = command.on)
                is HeadsetCommand.SetVoicePrompts -> state = state.copy(voicePrompts = state.voicePrompts?.copy(enabled = command.on))
                is HeadsetCommand.SetVendorControl -> state = state.copy(
                    vendorControls = state.vendorControls.map { if (it.id == command.id) it.copy(value = command.value) else it },
                )
                HeadsetCommand.PowerOff -> if (refusePowerOff) throw HeadphoneCommandException("Power off isn't supported")
                else -> error("unexpected $command")
            }
        }
    }

    private class TestLink(override val canConnect: Boolean = true, private val linked: Boolean = true) : HeadsetLink {
        val calls = ArrayList<String>()
        override fun isLinked(address: String) = linked
        override fun connect(address: String) { calls += "connect" }
        override fun disconnect(address: String) { calls += "disconnect" }
    }

    private val connected = ConnectionState(ConnectionStatus.CONNECTED, "Buds", "00:11:22:33:44:55")

    private val boseLike = HeadphoneState(
        connection = connected,
        modes = listOf(
            ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
        ),
        currentModeIndex = 0,
        noise = NoiseSettings(cancellingLevel = 10, ancEnabled = true),
        touchControlsEnabled = true,
        voicePrompts = VoicePrompts(true, 3),
    )

    private val a30Like = HeadphoneState(
        connection = connected,
        modes = listOf(
            ListeningMode(0, "Noise cancelling", isPreset = true, isEditable = false, isConfigured = true),
            ListeningMode(1, "Off", isPreset = true, isEditable = false, isConfigured = true),
        ),
        currentModeIndex = 0,
        touchControlsEnabled = true,
        vendorControls = listOf(
            VendorControl("prompt_low_battery", "Low battery prompt", value = 1),
            VendorControl("prompt_anc", "Noise cancelling prompt", value = 0),
            VendorControl("listening_mode", "Listening mode", value = 0, options = listOf("Bluetooth", "Local sounds")),
        ),
        capabilities = HeadphoneCapabilities(ancToggle = false, noiseCancelling = false, liveNoiseControl = false, formFactor = FormFactor.EARBUDS),
    )

    private val generic = HeadphoneState(
        connection = connected.copy(audioLinked = true),
        capabilities = HeadphoneCapabilities(ownSettings = false, listeningModes = false, formFactor = FormFactor.EARBUDS),
        batteryPercent = 60,
    )

    @Test
    fun `bose-like headphones switch noise off with the noise switch and get it back`() = runTest {
        val hp = TestHeadset(boseLike)
        val actions = SleepHeadsetActions(hp, TestLink())
        assertIs<SleepHeadsetActions.NoiseOffPlan.AncSwitch>(actions.noiseOffPlan(hp.state))
        val notes = mutableListOf<StepNote>()
        val record = actions.noiseOff(hp.state, RestoreRecord(), notes)
        assertEquals(false, hp.state.noise?.ancEnabled)
        assertEquals(true, record.ancEnabled)
        assertEquals(listOf(StepNote(SleepNoteCode.NOISE_OFF)), notes)
        val left = actions.restore(record.copy(due = true), hp.state, notes)
        assertEquals(true, hp.state.noise?.ancEnabled)
        assertFalse(left.headsetPart)
    }

    @Test
    fun `an Off mode is chosen by meaning and the old mode comes back in the morning`() = runTest {
        val hp = TestHeadset(a30Like)
        val actions = SleepHeadsetActions(hp, TestLink())
        val notes = mutableListOf<StepNote>()
        val record = actions.noiseOff(hp.state, RestoreRecord(), notes)
        assertEquals(1, hp.state.currentModeIndex)
        assertEquals("noise cancelling", record.mode?.name?.lowercase())
        actions.restore(record, hp.state, notes)
        assertEquals(0, hp.state.currentModeIndex)
    }

    @Test
    fun `noise already off is left alone and nothing is restored`() = runTest {
        val hp = TestHeadset(a30Like.copy(currentModeIndex = 1))
        val actions = SleepHeadsetActions(hp, TestLink())
        val notes = mutableListOf<StepNote>()
        val record = actions.noiseOff(hp.state, RestoreRecord(), notes)
        assertNull(record.mode)
        assertTrue(hp.commands.isEmpty())
        assertEquals(SleepNoteCode.NOISE_ALREADY_OFF, notes.single().code)
    }

    @Test
    fun `touch lock and prompts off are remembered and undone, vendor prompts included`() = runTest {
        val hp = TestHeadset(a30Like)
        val actions = SleepHeadsetActions(hp, TestLink())
        val plan = SleepPlan(nightExtras = setOf(NightExtra.TOUCH_LOCK, NightExtra.PROMPTS_OFF))
        val record = actions.applyNightExtras(plan, hp.state, RestoreRecord(), mutableListOf())
        assertEquals(false, hp.state.touchControlsEnabled)
        // Only the prompt that was on is switched off (and so restored); the dropdown isn't a prompt.
        assertEquals(mapOf("prompt_low_battery" to 1), record.vendorPrompts)
        assertEquals(listOf<HeadsetCommand>(HeadsetCommand.SetTouchControls(false), HeadsetCommand.SetVendorControl("prompt_low_battery", 0)), hp.commands)
        actions.restore(record, hp.state, mutableListOf())
        assertEquals(true, hp.state.touchControlsEnabled)
        assertEquals(1, hp.state.vendorControls.first { it.id == "prompt_low_battery" }.value)
    }

    @Test
    fun `voice prompts go off and come back on, by the on-off command`() = runTest {
        val hp = TestHeadset(boseLike)
        val actions = SleepHeadsetActions(hp, TestLink())
        val record = actions.applyNightExtras(SleepPlan(nightExtras = setOf(NightExtra.PROMPTS_OFF)), hp.state, RestoreRecord(), mutableListOf())
        assertEquals(true, record.voicePromptsEnabled)
        assertEquals(false, hp.state.voicePrompts?.enabled)
        actions.restore(record, hp.state, mutableListOf())
        assertEquals(true, hp.state.voicePrompts?.enabled)
        assertEquals(listOf<HeadsetCommand>(HeadsetCommand.SetVoicePrompts(false), HeadsetCommand.SetVoicePrompts(true)), hp.commands)
    }

    @Test
    fun `a refused step is a problem note, never an exception, and is not recorded for restore`() = runTest {
        val refusing = object : HeadsetCommandRunner {
            override suspend fun run(command: HeadsetCommand) { throw HeadphoneCommandException("No") }
        }
        val notes = mutableListOf<StepNote>()
        val record = SleepHeadsetActions(refusing, TestLink())
            .applyNightExtras(SleepPlan(nightExtras = setOf(NightExtra.TOUCH_LOCK)), boseLike, RestoreRecord(), notes)
        assertNull(record.touchControlsEnabled)
        assertEquals(StepNote(SleepNoteCode.TOUCH_FAILED, isProblem = true), notes.single())
    }

    @Test
    fun `restore waits for the right headset`() = runTest {
        val hp = TestHeadset(boseLike.copy(connection = connected.copy(deviceAddress = "AA:AA:AA:AA:AA:AA")))
        val actions = SleepHeadsetActions(hp, TestLink())
        val record = RestoreRecord(address = "00:11:22:33:44:55", touchControlsEnabled = true)
        assertEquals(record, actions.restore(record, hp.state, mutableListOf()))
        assertTrue(hp.commands.isEmpty())
    }

    @Test
    fun `a headset without a driver can't do headset steps but says why`() {
        val abilities = SleepHeadsetActions(TestHeadset(generic), TestLink(linked = false)).abilities(generic)
        assertFalse(abilities.noiseOff.available)
        assertFalse(abilities.touchLock.available)
        assertFalse(abilities.powerOff.available)
        assertFalse(abilities.disconnect.available)
        assertEquals(SleepNoteCode.NO_DRIVER, abilities.noiseOff.why)
        assertEquals(SleepNoteCode.DISCONNECT_NOT_LINKED, abilities.disconnect.why)
        assertFalse(abilities.canReconnect)
    }

    @Test
    fun `a linked headset can disconnect and be paged again`() {
        val abilities = SleepHeadsetActions(TestHeadset(a30Like), TestLink()).abilities(a30Like)
        assertTrue(abilities.disconnect.available)
        assertTrue(abilities.canReconnect)
        assertTrue(abilities.touchLock.available)
        assertTrue(abilities.noiseOff.available)
        val noLink = SleepHeadsetActions(TestHeadset(a30Like), null).abilities(a30Like)
        assertEquals(SleepNoteCode.DISCONNECT_UNSUPPORTED, noLink.disconnect.why)
    }

    @Test
    fun `power off that the session refuses falls back to disconnect`() = runTest {
        val hp = TestHeadset(a30Like)
        val link = TestLink()
        val notes = mutableListOf<StepNote>()
        val sentAway = SleepHeadsetActions(hp, link).endAction(SleepEndAction.POWER_OFF, hp.state, notes)
        assertEquals(listOf<HeadsetCommand>(HeadsetCommand.PowerOff), hp.commands)
        assertEquals(listOf("disconnect"), link.calls)
        assertTrue(sentAway)
        assertEquals(listOf(SleepNoteCode.POWER_OFF_REFUSED, SleepNoteCode.DISCONNECTED), notes.map { it.code })
    }

    @Test
    fun `disconnect without a link is a note, not a crash`() = runTest {
        val hp = TestHeadset(generic)
        val link = TestLink(linked = false)
        val notes = mutableListOf<StepNote>()
        val sentAway = SleepHeadsetActions(hp, link).endAction(SleepEndAction.DISCONNECT, hp.state, notes)
        assertTrue(link.calls.isEmpty())
        assertTrue(notes.single().isProblem)
        // Nothing went away, so the morning restore may still connect it.
        assertFalse(sentAway)
    }

    @Test
    fun `the end action says when it sent the headset away`() = runTest {
        val hp = TestHeadset(boseLike, refusePowerOff = false)
        val actions = SleepHeadsetActions(hp, TestLink())
        assertFalse(actions.endAction(SleepEndAction.STOP_SOUNDS, hp.state, mutableListOf()))
        assertTrue(actions.endAction(SleepEndAction.POWER_OFF, hp.state, mutableListOf()))
        assertEquals(listOf<HeadsetCommand>(HeadsetCommand.PowerOff), hp.commands)
        val link = TestLink()
        assertTrue(SleepHeadsetActions(hp, link).endAction(SleepEndAction.DISCONNECT, hp.state, mutableListOf()))
        assertEquals(listOf("disconnect"), link.calls)
    }

    @Test
    fun `the page before an earbud alarm uses the link`() {
        val link = TestLink()
        assertTrue(SleepHeadsetActions(TestHeadset(boseLike), link).reconnect("00:11:22:33:44:55"))
        assertEquals(listOf("connect"), link.calls)
        assertFalse(SleepHeadsetActions(TestHeadset(boseLike), null).reconnect("00:11:22:33:44:55"))
    }
}
