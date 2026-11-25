package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.TransportControl

/**
 * One change the person asked for, in words every maker's session understands. A session runs the
 * ones its headsets have and throws [io.github.langkurst.ruru.core.HeadphoneCommandException] for
 * the rest. Nothing here can reset, unpair or update firmware: those commands don't exist.
 */
sealed interface HeadsetCommand {
    data class SetMode(val index: Int) : HeadsetCommand
    data class SaveMode(val index: Int, val name: String, val settings: NoiseSettings) : HeadsetCommand
    data class RemoveMode(val index: Int) : HeadsetCommand
    data class SetFavorites(val indices: Set<Int>) : HeadsetCommand
    data class SetNoise(val settings: NoiseSettings) : HeadsetCommand
    data class SetEq(val eq: HeadphoneEq) : HeadsetCommand

    /** [persistent] null keeps what the headphones report (on when they report nothing). */
    data class SetSidetone(val level: Sidetone, val persistent: Boolean? = null) : HeadsetCommand
    data class SetAutoPlayPause(val on: Boolean) : HeadsetCommand
    data class SetAutoAnswer(val on: Boolean) : HeadsetCommand
    data class SetMultipoint(val on: Boolean) : HeadsetCommand

    /** Minutes, 0 = never. */
    data class SetAutoOff(val minutes: Int) : HeadsetCommand

    /** On or off; the language stays what the headphones report. */
    data class SetVoicePrompts(val on: Boolean) : HeadsetCommand
    data class SetShortcut(val action: ButtonAction) : HeadsetCommand
    data class SetTouchControls(val on: Boolean) : HeadsetCommand
    data class SetDeviceName(val name: String) : HeadsetCommand
    data class SetLeAudio(val on: Boolean) : HeadsetCommand
    data class SetLatency(val ms: Int) : HeadsetCommand
    data class SetVolume(val level: Int) : HeadsetCommand
    data class Transport(val control: TransportControl) : HeadsetCommand

    /** Connect the headphones to one of their paired devices, by the address the headphones reported. */
    data class SwitchDevice(val address: String) : HeadsetCommand

    /** One of [io.github.langkurst.ruru.core.HeadphoneState.vendorControls]: switch 0/1, or the option index. */
    data class SetVendorControl(val id: String, val value: Int) : HeadsetCommand
    data object PowerOff : HeadsetCommand
}
