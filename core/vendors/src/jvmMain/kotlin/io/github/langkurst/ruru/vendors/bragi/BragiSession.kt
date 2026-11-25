package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.core.CrusherBass
import io.github.langkurst.ruru.core.DeviceInfo as CoreDeviceInfo
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.SecureSession
import io.github.langkurst.ruru.core.SecureSessionStatus
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.SpatialAudio
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.LabProfile
import io.github.langkurst.ruru.vendors.bragi.BragiStateMapping.toCore
import io.github.langkurst.ruru.vendors.bragi.BragiStateMapping.toWire
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Skullcandy models "Sound by Bose" as a [HeadsetSession] (Earshot's CrusherProtocol and
 * CrusherHeadphoneRepository, MIT, without Android): the Crusher 1080 ANC and the Method 360 / 540
 * ANC over Bragi BOS-RPC on a [BragiConnection]. The BMAP counterpart is the Bose session; the two
 * do not share a wire format.
 *
 *  - [start]: the first call is `device.dev_info` and nothing is sent before it (the Skullcandy
 *    app does the same). A socket that opens but stays silent is another app holding the
 *    connection: the failure is a [HeadphoneCommandException], which the controller reports as
 *    the busy case. When `dev_info` says the headphones want a secure session, [BragiSecureSession]
 *    sets it up first (a failure is recorded in [HeadphoneState.secureSession] and the reads that
 *    follow go as far as they can). Then the static ranges, and one read of everything the product
 *    has, which also subscribes to live changes.
 *  - Reads are tolerant: a call the firmware refuses leaves that field as it was.
 *  - What it runs: the three modes, the noise gain, immersive audio, sidetone and the Crusher
 *    bass controls. Every other [HeadsetCommand] throws a readable [HeadphoneCommandException]
 *    and sends nothing. Every frame leaves through [BragiConnection], whose [BragiWriteGuard]
 *    refuses the firmware-update service, the equaliser and clock writes and anything not on its list.
 *
 * The product is named from the Bluetooth name at first and from `dev_info` once it answers
 * (SKU, then codename). Until then it is the Crusher, the superset: a Method earbud refuses the
 * Crusher-only reads and nothing breaks.
 */
class BragiSession(
    private val conn: BragiConnection,
    private val trust: BragiTrustStore = BragiSecurity.trustStore,
    private val prompt: PairingPrompt = BragiSecurity.pairingPrompt,
    private val probeTimeoutsMs: List<Long> = PROBE_TIMEOUTS_MS,
) : HeadsetSession {
    override val family: DeviceFamily get() = DeviceFamily.BRAGI

    override val isOpen: Boolean get() = conn.isOpen

    /** Subscription updates and late frames: what the headphones send unasked. */
    override val pushes: Flow<Any> get() = conn.unsolicited

    @Volatile private var ancGainMax = 10
    @Volatile private var atGainMax = 10
    @Volatile private var sidetoneMax = 5
    @Volatile private var crusherConfig: Crusher.Config? = null
    @Volatile private var lastNonOffMode = HearingMode.ANC

    /** Which product this connection is: the Bluetooth name at first, `dev_info` once it answers. The Crusher until told otherwise. */
    @Volatile var model: BragiModel = BragiModel.SKULLCANDY_CRUSHER_1080
        private set

    /** The `authenticated_access` flag from `dev_info`: the secure session is required for protected calls. */
    @Volatile var authenticatedAccess: Boolean = false
        private set

    // ---- HeadsetSession -------------------------------------------------------------------

    override suspend fun start(state: HeadphoneState): HeadphoneState {
        model = BragiModel.fromBluetoothName(state.connection.deviceName) ?: BragiModel.SKULLCANDY_CRUSHER_1080
        crusherConfig = null
        var s = state.copy(
            capabilities = BragiStateMapping.capabilitiesFor(model), livePushEnabled = false,
            secureSession = SecureSession(), pairing = null, vendorControls = emptyList(),
        )
        probe()?.let { s = withDevInfo(s, it) }
        if (authenticatedAccess) {
            val outcome = BragiSecureSession(trust, prompt).establish(conn) { conn.installCipher(it) }
            s = s.copy(secureSession = outcome.toSession(), pairing = null)
        }
        optional { Hearing.parseModeInfo(conn.call(BragiServices.HEARING_MODE_INFO).payload).let { ancGainMax = it.ancGainMax.coerceAtLeast(1); atGainMax = it.atGainMax.coerceAtLeast(1) } }
        optional { sidetoneMax = Hearing.parseSidetoneInfo(conn.call(BragiServices.SIDETONE_INFO).payload).coerceAtLeast(1) }
        if (model.features.crusherBass) optional { crusherConfig = Crusher.parseConfig(conn.call(BragiServices.CRUSHER_CONFIG_GET).payload) }
        return read(s, subscribe = true)
    }

    /** Battery has no push in the definitions, so the controller polls it. */
    override suspend fun pollBattery(state: HeadphoneState): HeadphoneState {
        var s = state
        optional { s = withBattery(s, conn.call(BragiServices.BATT_INFO).payload) }
        return s
    }

    override fun onPush(push: Any, state: HeadphoneState): HeadphoneState? {
        val packet = push as? BosRpcPacket ?: return null
        if (packet.type != BosMessageType.RESPONSE) return null
        return try {
            when (packet.address) {
                BragiServices.MODE_READ -> withMode(state, packet.payload)
                BragiServices.SIDETONE_READ -> withSidetone(state, packet.payload)
                BragiServices.SPATIAL_INFO_GET -> withSpatial(state, packet.payload)
                BragiServices.CRUSHER_STATE_GET -> withCrusher(state, packet.payload)
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            BragiLog.w("ignoring a malformed push for ${packet.serviceId}.${packet.methodId}: ${e.message}")
            null
        }
    }

    override suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState = when (command) {
        is HeadsetCommand.SetMode -> setMode(state, command.index)
        is HeadsetCommand.SetNoise -> setNoise(state, command.settings)
        is HeadsetCommand.SetSidetone -> setSidetone(state, command.level)
        is HeadsetCommand.SetVendorControl -> setVendorControl(state, command.id, command.value)
        // Everything below is not a Skullcandy feature Ruru drives. Nothing is sent.
        is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
        is HeadsetCommand.SetEq, is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer,
        is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts,
        is HeadsetCommand.SetShortcut, is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName,
        is HeadsetCommand.SetLeAudio, is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume,
        is HeadsetCommand.Transport, is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
        -> throw HeadphoneCommandException(UNSUPPORTED)
    }

    override suspend fun labProfile(): LabProfile = LabProfile(
        title = BragiLab.STACK,
        reads = BragiLab.reads(model).map { "${it.address.service}.${it.address.method}: ${it.name}" },
    )

    override suspend fun scanLab(): List<String> = BragiLab.scan(conn, model)

    override suspend fun awaitClosed(): Throwable? = conn.awaitClosed()

    override fun close() = conn.close()

    // ---- Connect --------------------------------------------------------------------------

    /**
     * The first RPC the Skullcandy app sends, `device.dev_info`, unwatched: this probe decides on
     * its own. Tried twice with a longer wait. A real answer that is an error still proves the
     * framing, so the connect carries on without the info (null); only silence fails.
     */
    private suspend fun probe(): DeviceInfo.DevInfoResponse? {
        var last: HeadphoneCommandException? = null
        for (timeout in probeTimeoutsMs) {
            try {
                return DeviceInfo.parseDevInfo(conn.callUnwatched(BragiServices.DEV_INFO, DeviceInfo.devInfo(), timeout).payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                if (conn.isOpen && conn.hasFramed) {
                    BragiLog.w("dev_info refused (${e.message}); carrying on")
                    return null
                }
                last = e
                if (!conn.isOpen) break
            } catch (e: IllegalArgumentException) {
                BragiLog.w("dev_info did not parse: ${e.message}")
                return null
            }
        }
        BragiLog.w("the control service opened but the headphones did not answer BOS-RPC")
        throw last ?: HeadphoneCommandException(MESSAGE_CHANNEL_BUSY)
    }

    private suspend fun read(current: HeadphoneState, subscribe: Boolean): HeadphoneState {
        var s = current
        optional { s = withBattery(s, conn.call(BragiServices.BATT_INFO).payload) }
        optional { s = withMode(s, conn.call(BragiServices.MODE_READ, Hearing.modeRead(subscribe), streaming = true).payload) }
        optional { s = withSidetone(s, conn.call(BragiServices.SIDETONE_READ, Hearing.sidetoneRead(subscribe), streaming = true).payload) }
        // Only what the product has: a Method earbud is not asked for a Crusher bass or a spatial mode it cannot answer.
        if (model.features.spatialAudio) {
            optional { s = withSpatial(s, conn.call(BragiServices.SPATIAL_INFO_GET, SpatialAudioWire.infoGet(subscribe), streaming = true).payload) }
        }
        if (model.features.crusherBass) {
            optional { s = withCrusher(s, conn.call(BragiServices.CRUSHER_STATE_GET, Crusher.stateGet(subscribe), streaming = true).payload) }
        }
        return s
    }

    /**
     * A read that the firmware may refuse or that times out: log and carry on. A lost link is not
     * skippable: it is rethrown so the controller can close the session instead of reporting Connected.
     */
    private inline fun <T> optional(block: () -> T) {
        try {
            block()
        } catch (e: HeadphoneCommandException) {
            if (!conn.isOpen || e.message == BragiConnection.MESSAGE_STOPPED_ANSWERING) throw e
            BragiLog.d("read skipped: ${e.message}")
        } catch (e: IllegalArgumentException) {
            BragiLog.w("a reply did not parse: ${e.message}")
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    /** [index]: 0 Quiet, 1 Aware, 2 Off (the Bose order for the first two). */
    private suspend fun setMode(current: HeadphoneState, index: Int): HeadphoneState {
        val mode = BragiStateMapping.modeFor(index) ?: throw HeadphoneCommandException("There is no listening mode in slot $index")
        conn.call(BragiServices.MODE_CONFIGURE, Hearing.modeConfigure(mode, activate = true))
        if (mode != HearingMode.OFF) lastNonOffMode = mode
        return current.copy(
            currentModeIndex = index,
            noise = current.modes.firstOrNull { it.index == index }?.settings?.copy(spatialAudio = current.noise?.spatialAudio ?: SpatialAudio.OFF) ?: current.noise,
        )
    }

    /**
     * Apply [settings] against the current state: immersive audio if it changed, the active mode's
     * gain if the level changed, and off/on if `ancEnabled` changed (automations send it). Wind
     * Block has no equivalent and is ignored.
     */
    private suspend fun setNoise(current: HeadphoneState, settings: NoiseSettings): HeadphoneState {
        val before = current.noise ?: NoiseSettings()
        var s = current
        if (settings.spatialAudio != before.spatialAudio) {
            if (!model.features.spatialAudio) throw HeadphoneCommandException("The ${model.displayName.removePrefix("Skullcandy ")} has no immersive audio")
            conn.call(BragiServices.SPATIAL_MODE_SET, SpatialAudioWire.setMode(settings.spatialAudio.toWire()))
            s = s.copy(noise = (s.noise ?: before).copy(spatialAudio = settings.spatialAudio))
        }
        if (settings.ancEnabled != before.ancEnabled) {
            val target = if (settings.ancEnabled) lastNonOffMode else HearingMode.OFF
            conn.call(BragiServices.MODE_CONFIGURE, Hearing.modeConfigure(target, activate = true))
            s = s.copy(currentModeIndex = BragiStateMapping.indexFor(target), noise = (s.noise ?: before).copy(ancEnabled = settings.ancEnabled))
        }
        if (settings.cancellingLevel != before.cancellingLevel) {
            val active = activeMode(s)
            if (active == HearingMode.OFF) throw HeadphoneCommandException("Switch to Quiet or Aware to adjust the level")
            val max = if (active == HearingMode.ANC) ancGainMax else atGainMax
            conn.call(BragiServices.MODE_CONFIGURE, Hearing.modeConfigure(active, activate = false, gain = BragiStateMapping.levelToGain(settings.cancellingLevel, max)))
            s = s.copy(noise = (s.noise ?: before).copy(cancellingLevel = settings.cancellingLevel))
        }
        return s
    }

    /** The headphones always keep the sidetone setting until a factory reset. */
    private suspend fun setSidetone(current: HeadphoneState, level: Sidetone): HeadphoneState {
        val body = if (level == Sidetone.OFF) Hearing.sidetoneConfigure(enabled = false)
        else Hearing.sidetoneConfigure(enabled = true, volume = BragiStateMapping.sidetoneVolume(level, sidetoneMax))
        conn.call(BragiServices.SIDETONE_CONFIGURE, body)
        return current.copy(sidetone = level, sidetonePersistent = true)
    }

    private suspend fun setVendorControl(current: HeadphoneState, id: String, value: Int): HeadphoneState = when (id) {
        BragiStateMapping.CRUSHER -> setCrusherBass(current, active = value != 0, intensity = null, mode = null)
        BragiStateMapping.CRUSHER_INTENSITY -> setCrusherBass(current, active = null, intensity = value, mode = null)
        BragiStateMapping.CRUSHER_FEEL -> setCrusherBass(current, active = null, intensity = null, mode = value)
        else -> throw HeadphoneCommandException("These Skullcandy headphones have no setting \"$id\"")
    }

    /** Any argument left null stays as it is on the headphones. */
    private suspend fun setCrusherBass(current: HeadphoneState, active: Boolean?, intensity: Int?, mode: Int?): HeadphoneState {
        val cfg = crusherConfig
        val before = current.crusher ?: throw HeadphoneCommandException("These headphones have no Crusher bass")
        if (intensity != null && intensity !in 0..before.maxIntensity) throw HeadphoneCommandException("Crusher intensity must be 0..${before.maxIntensity}")
        if (mode != null && before.modeCount > 0 && mode !in 0 until before.modeCount) throw HeadphoneCommandException("Crusher feel must be 0..${before.modeCount - 1}")
        val reply = conn.call(BragiServices.CRUSHER_STATE_SET, Crusher.stateSet(active, intensity, mode))
        // The reply carries the applied state; an intensity the unit ignores (read-only dial) shows here.
        val applied = if (reply.payload.isNotEmpty()) Crusher.parseState(reply.payload) else null
        val after = before.copy(
            active = applied?.isActive ?: active ?: before.active,
            intensity = applied?.intensity ?: intensity ?: before.intensity,
            mode = applied?.mode ?: mode ?: before.mode,
            intensityReadOnly = cfg?.intensityReadOnly ?: before.intensityReadOnly,
        )
        return current.copy(crusher = after, vendorControls = BragiStateMapping.controls(after))
    }

    // ---- State mapping --------------------------------------------------------------------

    private fun BragiSecureSession.Outcome.toSession() = when (this) {
        is BragiSecureSession.Outcome.Secure -> SecureSession(SecureSessionStatus.SECURE)
        BragiSecureSession.Outcome.Declined -> SecureSession(SecureSessionStatus.DECLINED, "Pairing was cancelled")
        is BragiSecureSession.Outcome.Failed -> SecureSession(SecureSessionStatus.FAILED, reason)
    }

    private fun withDevInfo(s: HeadphoneState, d: DeviceInfo.DevInfoResponse): HeadphoneState {
        authenticatedAccess = d.authenticatedAccess
        // The SKU says it best, then the codename, then what the Bluetooth name said; a renamed unit keeps the Crusher default.
        model = BragiModel.fromModelNumber(d.modelNumber) ?: BragiModel.fromCodename(d.model) ?: model
        return s.copy(
            capabilities = BragiStateMapping.capabilitiesFor(model),
            info = (s.info ?: CoreDeviceInfo()).copy(
                productName = d.model, firmwareVersion = d.firmware, serialNumber = d.serial, modelName = model.displayName,
                bmapVersion = d.bosVersion?.let { "BOS $it" },
            ),
        )
    }

    private fun withBattery(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val own = DeviceInfo.parseBattery(body).firstOrNull() ?: return s
        return s.copy(batteryPercent = own.level.coerceIn(0, 100), isCharging = own.status == DeviceInfo.BatteryStatus.CHARGING)
    }

    private fun withMode(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val m = Hearing.parseModeState(body)
        if (m.active == HearingMode.UNSPECIFIED) return s
        if (m.active != HearingMode.OFF) lastNonOffMode = m.active
        val spatial = s.noise?.spatialAudio ?: SpatialAudio.OFF
        val ancLevel = BragiStateMapping.gainToLevel(m.ancGain, ancGainMax)
        val atLevel = BragiStateMapping.gainToLevel(m.atGain, atGainMax)
        val level = if (m.active == HearingMode.AT) atLevel else ancLevel
        return s.copy(
            modes = BragiStateMapping.modes(ancLevel, atLevel, spatial),
            currentModeIndex = BragiStateMapping.indexFor(m.active),
            noise = NoiseSettings(level, m.active != HearingMode.OFF, false, spatial),
        )
    }

    private fun withSidetone(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val st = Hearing.parseSidetoneState(body)
        return s.copy(sidetone = BragiStateMapping.sidetoneLevel(st.enabled, st.volume, sidetoneMax), sidetonePersistent = true)
    }

    private fun withSpatial(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val info = SpatialAudioWire.parseInfo(body)
        return s.copy(noise = (s.noise ?: NoiseSettings()).copy(spatialAudio = info.mode.toCore()))
    }

    private fun withCrusher(s: HeadphoneState, body: ByteArray): HeadphoneState {
        val st = Crusher.parseState(body)
        val cfg = crusherConfig
        val bass = CrusherBass(
            active = st.isActive, intensity = st.intensity, maxIntensity = cfg?.maxIntensity ?: s.crusher?.maxIntensity ?: 10,
            mode = st.mode, modeCount = cfg?.modeCount ?: s.crusher?.modeCount ?: 0,
            intensityReadOnly = cfg?.intensityReadOnly ?: false,
        )
        return s.copy(crusher = bass, vendorControls = BragiStateMapping.controls(bass))
    }

    private fun activeMode(s: HeadphoneState): HearingMode = BragiStateMapping.modeFor(s.currentModeIndex ?: BragiStateMapping.MODE_OFF) ?: HearingMode.OFF

    companion object {
        const val UNSUPPORTED = "Ruru's Skullcandy support doesn't have that setting"
        const val MESSAGE_CHANNEL_BUSY = "Close the Skullcandy app, then try again"

        /** The probe's waits: a normal one, then a longer one (some headsets are slow to wake). */
        val PROBE_TIMEOUTS_MS: List<Long> = listOf(2_000L, 3_000L)
    }
}
