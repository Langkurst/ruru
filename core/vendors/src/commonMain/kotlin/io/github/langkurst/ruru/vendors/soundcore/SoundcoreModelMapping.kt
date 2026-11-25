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
 * [SoundcoreState] (every Soundcore model except the Sleep A30, which keeps [SoundcoreStateMapping])
 * -> [HeadphoneState] (Earshot's SoundcoreModelMapping, MIT). Pure, unit-tested. The three ambient
 * modes are the listening modes, slot 0 noise cancelling, 1 transparency, 2 normal, so the tile,
 * the widget and the automations work unchanged (slot 0 is the quiet mode everywhere). The rest of
 * what the model has goes to [VendorControl]s: noise-cancelling type and level, transparency type,
 * wind, and the single-byte settings its table documents.
 *
 * A model whose layout is known shows its controls only while its state matched the layout (the
 * values are blank and a change is refused otherwise). A model with no layout shows battery and
 * firmware and says so.
 */
object SoundcoreModelMapping {
    const val MODE_NC = 0
    const val MODE_TRANSPARENCY = 1
    const val MODE_NORMAL = 2

    // Explicit meanings: saved rules and the tile resolve by them ("Normal" is Off, not a custom mode).
    val MODES = listOf(
        ListeningMode(MODE_NC, "Noise cancelling", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.QUIET),
        ListeningMode(MODE_TRANSPARENCY, "Transparency", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.AWARE),
        ListeningMode(MODE_NORMAL, "Normal", isPreset = true, isEditable = false, isConfigured = true, meaning = ModeMeaning.OFF),
    )

    const val EXPERIMENTAL = SoundcoreStateMapping.SUPPORT_NOTE

    /** The model's layout is known but this state didn't match it, or the model is one Ruru only reads. */
    const val READ_ONLY_NOTE =
        "Experimental, read-only: Ruru can't change these Soundcore settings (or they don't match the layout it knows), so it only reads them."

    /** A model with no layout in the table: battery and firmware only. */
    const val BATTERY_ONLY_NOTE =
        "Experimental, read-only: Ruru reads this Soundcore model's battery and firmware; its settings aren't mapped yet."

    /** The state was a size or shape no layout fits. */
    const val UNREADABLE_NOTE =
        "Experimental, read-only: these Soundcore headphones connected, but their state isn't a layout Ruru recognises, so nothing is shown or changed."

    fun modeIndex(mode: AmbientMode): Int = mode.wire

    fun ambientFor(index: Int): AmbientMode? = AmbientMode.fromWire(index)

    /** The text for an auto power-off option: minutes, or hours from 60. */
    fun durationLabel(minutes: Int): String = when {
        minutes == 60 -> "1 hour"
        minutes > 60 && minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes min"
    }

    fun controls(s: SoundcoreState): List<VendorControl> {
        val layout = s.model.layout ?: return emptyList()
        if (!layout.writable) return emptyList()
        val codec = s.modes?.codec ?: layout.codec
        val modes = s.modes
        val out = ArrayList<VendorControl>()
        if (codec.ncTypes.isNotEmpty()) {
            out += VendorControl(
                SoundcoreWrites.NC_TYPE, "Noise cancelling type", "Changes while noise cancelling is the active mode",
                value = modes?.ncType, options = codec.ncTypes,
            )
        }
        codec.manualLevels?.let { range ->
            out += VendorControl(
                SoundcoreWrites.NC_LEVEL, "Manual noise cancelling level", "Applies to the Manual type; ${range.first} is the lightest",
                value = modes?.manualLevel?.let { it - range.first }, options = range.map { it.toString() },
            )
        }
        if (codec.transparencyTypes.isNotEmpty()) {
            out += VendorControl(
                SoundcoreWrites.TRANSPARENCY_TYPE, "Transparency type", "Changes while transparency is the active mode",
                value = modes?.transparencyType, options = codec.transparencyTypes,
            )
        }
        if (codec.hasWind) {
            out += VendorControl(
                SoundcoreWrites.WIND, "Wind noise reduction", "With noise cancelling or transparency",
                value = modes?.windOn?.let { if (it) 1 else 0 },
            )
        }
        for (setting in layout.settings) out += settingControl(s, setting, layout.autoOffMinutes)
        return out
    }

    private fun settingControl(s: SoundcoreState, setting: SoundcoreSetting, autoOffMinutes: List<Int>): VendorControl =
        VendorControl(
            setting.id, setting.title, setting.subtitle,
            value = s.settings[setting.id],
            options = if (setting.autoOff) listOf("Off") + autoOffMinutes.map(::durationLabel) else null,
        )

    fun capabilities(s: SoundcoreState): HeadphoneCapabilities = HeadphoneCapabilities(
        spatialAudio = false,
        ancToggle = false,
        liveNoiseControl = false,
        // The modes carry it; the level and type are vendor controls, not the Bose noise slider.
        noiseCancelling = false,
        windBlock = false,
        editableModes = false,
        favoriteModes = false,
        listeningModes = s.ambient != null,
        modesOnHome = true,
        verified = false,
        formFactor = if (s.model.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES,
        supportNote = when {
            s.writable -> EXPERIMENTAL
            s.model.layout == null -> BATTERY_ONLY_NOTE
            else -> READ_ONLY_NOTE
        },
    )


    fun apply(state: HeadphoneState, s: SoundcoreState, now: Long = currentTimeMillis()): HeadphoneState =
        withBattery(state, s).copy(
            isCharging = s.charging,
            modes = if (s.ambient != null) MODES else emptyList(),
            currentModeIndex = s.ambient?.let(::modeIndex),
            vendorControls = controls(s),
            info = DeviceInfo(
                productName = s.model.displayName,
                modelName = s.model.displayName,
                firmwareVersion = s.firmware,
                serialNumber = s.serial,
            ),
            capabilities = capabilities(s),
            lastUpdatedMillis = now,
        )

    /** Per-part battery; the headline is the lower bud (headphones: their one battery). */
    fun withBattery(state: HeadphoneState, s: SoundcoreState): HeadphoneState = state.copy(
        batteryPercent = s.headlinePercent,
        battery = BatteryDetail(
            overall = s.overallPercent?.let { BatteryLevel(it) },
            left = s.leftPercent?.let { BatteryLevel(it) },
            right = s.rightPercent?.let { BatteryLevel(it) },
            case = s.casePercent?.let { BatteryLevel(it) },
        ),
    )

    /** The state for a headset that answered `01 01` with something no layout fits: name and a note, nothing else. */
    fun unreadable(state: HeadphoneState, model: SoundcoreModel, now: Long = currentTimeMillis()): HeadphoneState = state.copy(
        modes = emptyList(),
        currentModeIndex = null,
        vendorControls = emptyList(),
        info = DeviceInfo(productName = model.displayName, modelName = model.displayName),
        capabilities = HeadphoneCapabilities(
            spatialAudio = false, ancToggle = false, liveNoiseControl = false, noiseCancelling = false, windBlock = false,
            editableModes = false, favoriteModes = false, listeningModes = false, modesOnHome = true, verified = false,
            formFactor = if (model.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES,
            supportNote = UNREADABLE_NOTE,
        ),
        lastUpdatedMillis = now,
    )
}
