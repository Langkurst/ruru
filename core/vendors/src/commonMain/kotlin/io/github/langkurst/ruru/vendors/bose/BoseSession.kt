package io.github.langkurst.ruru.vendors.bose

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.VoicePrompts
import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.session.QcUltra2Protocol
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.HeadsetSession
import kotlinx.coroutines.flow.Flow

/**
 * Bose's [HeadsetSession]: Earshot's [QcUltra2Protocol] over one [BmapConnection], exactly as Ruru
 * 0.2's controller called it. Each method is the call the controller made, with the same
 * arguments, so the frames, their order and their timing are unchanged (BoseFramesGoldenTest in
 * the app checks the frames against a list recorded before this class existed).
 */
class BoseSession(
    private val connection: BmapConnection,
    private val protocol: QcUltra2Protocol = QcUltra2Protocol(),
) : HeadsetSession {
    override val family: DeviceFamily get() = DeviceFamily.BOSE

    override val isOpen: Boolean get() = connection.isOpen

    override val pushes: Flow<Any> get() = connection.unsolicited

    /** `[0.6]` GET only. */
    override suspend fun readIdentityAddress(): String? = protocol.readAddress(connection)

    override suspend fun start(state: HeadphoneState): HeadphoneState =
        protocol.refresh(connection, protocol.onConnected(connection, state))

    override suspend fun pollBattery(state: HeadphoneState): HeadphoneState = protocol.pollBattery(connection, state)

    override fun onPush(push: Any, state: HeadphoneState): HeadphoneState? =
        (push as? BmapPacket)?.let { protocol.onUnsolicited(it, state) }

    override suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState {
        val c = connection
        val s = state
        return when (command) {
            is HeadsetCommand.SetMode -> protocol.setMode(c, s, command.index)
            is HeadsetCommand.SaveMode -> protocol.saveCustomMode(c, s, command.index, command.name, command.settings)
            is HeadsetCommand.RemoveMode -> protocol.resetCustomMode(c, s, command.index)
            is HeadsetCommand.SetFavorites -> protocol.setFavorites(c, s, command.indices)
            is HeadsetCommand.SetNoise -> protocol.setNoise(c, s, command.settings)
            is HeadsetCommand.SetEq -> protocol.setEq(c, s, command.eq)
            is HeadsetCommand.SetSidetone ->
                protocol.setSidetone(c, s, command.level, command.persistent ?: s.sidetonePersistent ?: true)
            is HeadsetCommand.SetAutoPlayPause -> protocol.setAutoPlayPause(c, s, command.on)
            is HeadsetCommand.SetAutoAnswer -> protocol.setAutoAnswer(c, s, command.on)
            is HeadsetCommand.SetMultipoint -> protocol.setMultipoint(c, s, command.on)
            is HeadsetCommand.SetAutoOff -> protocol.setAutoOff(c, s, command.minutes)
            is HeadsetCommand.SetVoicePrompts ->
                protocol.setVoicePrompts(c, s, (s.voicePrompts ?: VoicePrompts(enabled = command.on, languageId = 1)).copy(enabled = command.on))
            is HeadsetCommand.SetShortcut -> protocol.setShortcutButton(c, s, command.action)
            is HeadsetCommand.SetTouchControls -> protocol.setTouchControls(c, s, command.on)
            is HeadsetCommand.SetDeviceName -> protocol.setDeviceName(c, s, command.name)
            is HeadsetCommand.SetLeAudio -> protocol.setLeAudioEnabled(c, s, command.on)
            is HeadsetCommand.SetLatency -> protocol.setLatencyMs(c, s, command.ms)
            is HeadsetCommand.SetVolume -> protocol.setVolume(c, s, command.level)
            is HeadsetCommand.Transport -> protocol.transport(c, s, command.control)
            is HeadsetCommand.SwitchDevice -> protocol.switchToDevice(c, s, command.address)
            is HeadsetCommand.PowerOff -> protocol.powerOff(c, s)
            is HeadsetCommand.SetVendorControl -> throw HeadphoneCommandException(NO_VENDOR_CONTROLS)
        }
    }

    override suspend fun awaitClosed(): Throwable? = connection.awaitClosed()

    override fun close() = connection.close()

    companion object {
        const val NO_VENDOR_CONTROLS = "Not supported by this protocol"
    }
}
