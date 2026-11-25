package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.core.BatteryDetail
import io.github.langkurst.ruru.core.BatteryLevel
import io.github.langkurst.ruru.core.DeviceInfo
import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.VendorControl
import io.github.langkurst.ruru.core.currentTimeMillis

/**
 * Sleep A30 state -> [HeadphoneState] (Earshot's SoundcoreStateMapping, MIT). Pure, unit-tested.
 * Its noise cancelling is on/off only, so it is shown as two listening modes, slot 0 "Noise
 * cancelling" and slot 1 "Off", like the Crusher's: the tile, the widget and the automations work
 * unchanged (slot 0 is the quiet mode everywhere). Home shows the vendor controls instead of the
 * two stand-in modes ([HeadphoneCapabilities.modesOnHome] false).
 *
 * Earshot ran this model on a real pair; Ruru has not, so here it is Experimental like every
 * other headset that is not the QC Ultra Headphones (1st Gen) on Windows.
 */
object SoundcoreStateMapping {
    const val MODE_ANC = 0
    const val MODE_OFF = 1

    const val SUPPORT_NOTE =
        "Experimental: built from published research and a virtual headset, not yet tried on real hardware."

    // VendorControl ids.
    const val ANC = "soundcore_anc"
    const val LISTENING = "soundcore_listening_mode"
    const val DEFAULT_LISTENING = "soundcore_default_listening_mode"
    const val AUTO_SWITCH_ASLEEP = "soundcore_auto_switch_asleep"
    const val POST_SLEEP = "soundcore_post_sleep_action"
    const val INCOMING_CALLS = "soundcore_incoming_calls"
    const val PROMPT_LOW_BATTERY = "soundcore_prompt_low_battery"
    const val PROMPT_ANC = "soundcore_prompt_anc"
    const val PROMPT_LISTENING = "soundcore_prompt_listening_mode"
    const val PROMPT_AUTO_OFF = "soundcore_prompt_auto_off"

    private val LISTENING_OPTIONS = listOf("Bluetooth", "Local sounds")

    /** Everything the Sleep A30 lets you set, in the order a screen should show it. */
    fun controls(d: D1301State): List<VendorControl> = listOf(
        VendorControl(ANC, "Noise cancelling", value = d.ancOn.bit(), onHome = true),
        VendorControl(
            LISTENING, "Listening mode", "Local sounds: the earbuds play their own sleep sounds, no phone needed",
            value = d.listeningLocal.bit(), options = LISTENING_OPTIONS, onHome = true,
        ),
        VendorControl(DEFAULT_LISTENING, "Start in", "The listening mode the earbuds start in", value = d.defaultListeningLocal.bit(), options = LISTENING_OPTIONS),
        VendorControl(AUTO_SWITCH_ASLEEP, "Switch once asleep", "Do what's set below when the earbuds detect you fell asleep", value = d.autoSwitchOnceAsleep.bit()),
        VendorControl(
            POST_SLEEP, "Once asleep", "Play local sounds turns noise cancelling off",
            value = d.postSleepAction.takeIf { it in 0..1 }, options = listOf("Pause", "Play local sounds"),
        ),
        VendorControl(INCOMING_CALLS, "Incoming calls in Bluetooth mode", value = d.incomingCallsInBluetoothMode.bit()),
        VendorControl(PROMPT_LOW_BATTERY, "Low battery prompt", value = d.lowBatteryPrompt.bit()),
        VendorControl(PROMPT_ANC, "Noise cancelling prompt", value = d.ancPrompt.bit()),
        VendorControl(PROMPT_LISTENING, "Listening mode prompt", value = d.listeningModePrompt.bit()),
        VendorControl(PROMPT_AUTO_OFF, "Auto power-off prompt", value = d.autoOffPrompt.bit()),
    )

    /** The command and body that set [id] to [value], or null for an id this model doesn't have. */
    fun write(id: String, value: Int): Pair<Int, ByteArray>? {
        val b = (if (value != 0) 1 else 0).toByte()
        return when (id) {
            ANC -> SoundcoreCommands.SET_ANC_D1301 to byteArrayOf(b)
            LISTENING -> SoundcoreCommands.SET_LISTENING_MODE to byteArrayOf(b)
            DEFAULT_LISTENING -> SoundcoreCommands.SET_DEFAULT_LISTENING_MODE to byteArrayOf(b)
            AUTO_SWITCH_ASLEEP -> SoundcoreCommands.SET_AUTO_SWITCH_ASLEEP to byteArrayOf(b)
            // FF FF FF: leave the timer as it is, set only the action.
            POST_SLEEP -> SoundcoreCommands.SET_SLEEP_TIMER to byteArrayOf(-1, -1, -1, b)
            INCOMING_CALLS -> SoundcoreCommands.SET_INCOMING_CALLS to byteArrayOf(b)
            PROMPT_LOW_BATTERY -> SoundcoreCommands.SET_LOW_BATTERY_PROMPT to byteArrayOf(b)
            PROMPT_ANC -> SoundcoreCommands.SET_ANC_PROMPT to byteArrayOf(b)
            PROMPT_LISTENING -> SoundcoreCommands.SET_LISTENING_MODE_PROMPT to byteArrayOf(b)
            PROMPT_AUTO_OFF -> SoundcoreCommands.SET_AUTO_OFF_PROMPT to byteArrayOf(b)
            else -> null
        }
    }

    private fun Boolean.bit() = if (this) 1 else 0

    val MODES = listOf(
        ListeningMode(MODE_ANC, "Noise cancelling", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.QUIET),
        ListeningMode(MODE_OFF, "Off", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.OFF),
    )

    fun capabilities() = HeadphoneCapabilities(
        spatialAudio = false,
        ancToggle = false,
        liveNoiseControl = false,
        // The modes carry it; no level slider to show.
        noiseCancelling = false,
        windBlock = false,
        editableModes = false,
        favoriteModes = false,
        listeningModes = true,
        // The two modes stand in for the noise cancelling switch: Home shows the controls instead.
        modesOnHome = false,
        verified = false,
        formFactor = FormFactor.EARBUDS,
        supportNote = SUPPORT_NOTE,
    )

    fun apply(state: HeadphoneState, d: D1301State, now: Long = currentTimeMillis()): HeadphoneState =
        withBattery(state, d.leftPercent, d.rightPercent).copy(
            modes = MODES,
            currentModeIndex = if (d.ancOn) MODE_ANC else MODE_OFF,
            touchControlsEnabled = !d.tapControlsDisabled,
            vendorControls = controls(d),
            info = DeviceInfo(
                productName = SoundcoreModel.SLEEP_A30.displayName,
                modelName = SoundcoreModel.SLEEP_A30.displayName,
                firmwareVersion = d.firmware,
                serialNumber = d.serial,
            ),
            capabilities = capabilities(),
            lastUpdatedMillis = now,
        )

    /** Per-bud battery; the headline is the lower bud (the one that runs out first). Case battery isn't reported. */
    fun withBattery(state: HeadphoneState, left: Int?, right: Int?): HeadphoneState = state.copy(
        batteryPercent = listOfNotNull(left, right).minOrNull(),
        battery = BatteryDetail(left = BatteryLevel(left), right = BatteryLevel(right)),
    )
}
