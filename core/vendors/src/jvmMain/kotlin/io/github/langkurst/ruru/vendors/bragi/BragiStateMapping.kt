package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.core.CrusherBass
import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.ListeningMode
import io.github.langkurst.ruru.core.ModeMeaning
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.core.VendorControl
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bragi BOS-RPC values <-> Ruru's [io.github.langkurst.ruru.core.HeadphoneState] (Earshot's
 * CrusherProtocol mappings, MIT). Pure, unit-tested.
 *
 *  - **Listening modes**: three built-in, non-editable modes, `0 Quiet` (ANC), `1 Aware` (audio
 *    transparency), `2 Off`. Slots 0 and 1 line up with the Bose models, so automations, the tile
 *    and the widget mean the same thing on either stack.
 *  - **Noise slider**: the gain of the active mode, scaled to 0..10. There is no separate ANC
 *    switch (Off is a mode) and no Wind Block.
 *  - **Immersive audio**: Bose TrueSpatial Off / Still / Motion, mapped by label: Still is
 *    [SpatialAudio.ROOM] and Motion is [SpatialAudio.HEAD].
 *  - **Sidetone**: on/off plus a volume 0..max, folded into Off / Low / Medium / High thirds.
 *  - **Crusher bass**: three vendor controls (a switch, an intensity slider and the feel).
 */
object BragiStateMapping {
    const val MODE_QUIET = 0
    const val MODE_AWARE = 1
    const val MODE_OFF = 2

    // VendorControl ids.
    const val CRUSHER = "skullcandy_crusher"
    const val CRUSHER_INTENSITY = "skullcandy_crusher_intensity"
    const val CRUSHER_FEEL = "skullcandy_crusher_feel"

    /** The Crusher's feels in the order of the wire value: 0 high, 1 medium, 2 low. */
    val FEELS = listOf("High", "Medium", "Low")

    const val SUPPORT_NOTE =
        "Experimental: built from published research and a virtual headset, not yet tried on real hardware."

    const val METHOD_NOTE =
        "Experimental: these earbuds share the Crusher 1080's controls and have not been tried on real earbuds. " +
            "Noise cancelling, Aware, sidetone where they offer it, and battery."

    fun modeFor(index: Int): HearingMode? = when (index) {
        MODE_QUIET -> HearingMode.ANC
        MODE_AWARE -> HearingMode.AT
        MODE_OFF -> HearingMode.OFF
        else -> null
    }

    fun indexFor(mode: HearingMode): Int = when (mode) {
        HearingMode.ANC -> MODE_QUIET
        HearingMode.AT -> MODE_AWARE
        else -> MODE_OFF
    }

    fun gainToLevel(gain: Int, max: Int): Int = if (max <= 0) 0 else (gain * 10.0 / max).roundToInt().coerceIn(0, 10)
    fun levelToGain(level: Int, max: Int): Int = (level * max / 10.0).roundToInt().coerceIn(0, max)

    /** The three modes, each with its own gain as the slider level. */
    fun modes(ancLevel: Int, atLevel: Int, spatial: SpatialAudio): List<ListeningMode> {
        fun mode(index: Int, name: String, level: Int, enabled: Boolean, meaning: ModeMeaning) = ListeningMode(
            index = index, name = name, isPreset = true, isEditable = false, isConfigured = true,
            isFavorite = false, settings = NoiseSettings(level, enabled, false, spatial), meaning = meaning,
        )
        return listOf(
            mode(MODE_QUIET, "Quiet", ancLevel, true, ModeMeaning.QUIET),
            mode(MODE_AWARE, "Aware", atLevel, true, ModeMeaning.AWARE),
            mode(MODE_OFF, "Off", ancLevel, false, ModeMeaning.OFF),
        )
    }

    /** The level whose volume is nearest, so what [sidetoneVolume] writes always reads back as the same level. */
    fun sidetoneLevel(enabled: Boolean, volume: Int, max: Int): Sidetone {
        if (!enabled) return Sidetone.OFF
        return listOf(Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH).minByOrNull { abs(sidetoneVolume(it, max) - volume) } ?: Sidetone.MEDIUM
    }

    fun sidetoneVolume(level: Sidetone, max: Int): Int = when (level) {
        Sidetone.LOW -> (max / 3.0).roundToInt().coerceAtLeast(1)
        Sidetone.MEDIUM -> (max * 2 / 3.0).roundToInt().coerceAtLeast(1)
        Sidetone.HIGH -> max
        Sidetone.OFF -> 0
    }

    fun SpatialAudio.toWire(): SpatialMode = when (this) {
        SpatialAudio.OFF -> SpatialMode.OFF
        SpatialAudio.ROOM -> SpatialMode.STILL
        SpatialAudio.HEAD -> SpatialMode.MOTION
    }

    fun SpatialMode.toCore(): SpatialAudio = when (this) {
        SpatialMode.STILL -> SpatialAudio.ROOM
        SpatialMode.MOTION -> SpatialAudio.HEAD
        else -> SpatialAudio.OFF
    }

    /**
     * The Crusher bass as the generic controls: a switch, the intensity slider (left out when the
     * headphones ignore intensity writes: the dial on the ear cup sets it) and the feel. Empty when
     * the product has none.
     */
    fun controls(crusher: CrusherBass?): List<VendorControl> {
        crusher ?: return emptyList()
        return buildList {
            add(VendorControl(CRUSHER, "Crusher bass", "Feel the bass through the ear cups", value = if (crusher.active) 1 else 0, onHome = true))
            if (!crusher.intensityReadOnly && crusher.maxIntensity > 0) {
                add(VendorControl(CRUSHER_INTENSITY, "Crusher intensity", value = crusher.intensity.coerceIn(0, crusher.maxIntensity), range = 0..crusher.maxIntensity))
            }
            val feel = crusher.mode
            if (feel != null && crusher.modeCount > 0) {
                add(VendorControl(CRUSHER_FEEL, "Crusher feel", value = feel.coerceIn(0, crusher.modeCount - 1), options = FEELS.take(crusher.modeCount)))
            }
        }
    }

    /**
     * No ANC switch (Off is a mode), no Wind Block, no editable modes; the level slider is the active
     * mode's gain. The Crusher's own capabilities; another product has what [BragiFeatures] leaves it.
     */
    fun capabilitiesFor(model: BragiModel): HeadphoneCapabilities {
        val f = model.features
        return HeadphoneCapabilities(
            spatialAudio = f.spatialAudio, ancToggle = false, liveNoiseControl = true, windExcludesCancelling = false,
            noiseCancelling = true, ancAlwaysOn = false, windBlock = false, editableModes = false, favoriteModes = false,
            verified = false,
            formFactor = if (f.earbuds) FormFactor.EARBUDS else FormFactor.HEADPHONES,
            supportNote = if (f.earbuds) METHOD_NOTE else SUPPORT_NOTE,
        )
    }
}
