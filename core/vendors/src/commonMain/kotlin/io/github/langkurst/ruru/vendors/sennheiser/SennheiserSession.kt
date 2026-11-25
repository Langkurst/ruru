package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.currentTimeMillis
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.LabProfile
import io.github.langkurst.ruru.vendors.gaia.GaiaApiVersion
import io.github.langkurst.ruru.vendors.gaia.GaiaCommand
import io.github.langkurst.ruru.vendors.gaia.GaiaDiscovery
import io.github.langkurst.ruru.vendors.gaia.GaiaError
import io.github.langkurst.ruru.vendors.gaia.GaiaFrame
import io.github.langkurst.ruru.vendors.gaia.GaiaReply
import io.github.langkurst.ruru.vendors.gaia.GaiaVendor
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserCommands as C
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sennheiser's [HeadsetSession] (Earshot's SennheiserSession and the repository around it, MIT):
 * one connection's worth of GAIA v3 over a [SennheiserConnection], mapped to [HeadphoneState] by
 * [SennheiserStateMapping]:
 *  - [start]: the Qualcomm API version (the probe: a headset that stays silent is refused), the
 *    Sennheiser feature list (the write guard opens up only for what it reports), Qualcomm's list
 *    (logged), model string and firmware, push registration for battery, ANC, transparency, wear,
 *    audio and EQ, then one read of everything the model has;
 *  - [onPush]: notifications (and the stray responses a registration triggers) fold into [status];
 *  - commands write, then re-read what they touched. Noise writes have side effects on the
 *    Momentum 4 (ANC on switches transparency off and the level to 0; transparency on sets the
 *    level to 100 and off restores it; any mode-table write switches transparency off), so after
 *    every noise write the whole noise group is read back rather than predicted.
 *
 * What it runs: the three stand-in modes, the noise settings, the three-band EQ and every vendor
 * control. Every other [HeadsetCommand] throws a readable [HeadphoneCommandException] and sends
 * nothing. Every frame leaves through [SennheiserConnection], whose [SennheiserWriteGuard] refuses
 * firmware update, factory reset, reboot, role switch, pairing deletes, rename and anything else
 * that is not on the allow-list. Commands are serialised by the controller; the connection allows
 * one request in flight anyway.
 */
class SennheiserSession(
    val conn: SennheiserConnection,
    /** The Bluetooth name: the model until `0x1206` names it exactly. */
    private val name: String?,
    private val clock: () -> Long = ::currentTimeMillis,
) : HeadsetSession {
    override val family: DeviceFamily get() = DeviceFamily.SENNHEISER

    override val isOpen: Boolean get() = conn.isOpen

    private val _status = MutableStateFlow(SennheiserStatus())
    val status: StateFlow<SennheiserStatus> = _status.asStateFlow()

    val model: SennheiserModel? get() = _status.value.model ?: SennheiserModel.fromName(name)

    private val noiseFamily: SennheiserNoiseFamily get() = SennheiserStateMapping.family(model)

    /** Everything the headset sends unasked (notifications, stray responses). */
    override val pushes: Flow<Any> = conn.pushes.map { it }

    private fun view(state: HeadphoneState): HeadphoneState = SennheiserStateMapping.apply(state, _status.value, model, clock())

    // ---- HeadsetSession ---------------------------------------------------------------------

    override suspend fun start(state: HeadphoneState): HeadphoneState {
        initialize()
        return view(state)
    }

    override suspend fun pollBattery(state: HeadphoneState): HeadphoneState {
        readBattery()
        return view(state)
    }

    override fun onPush(push: Any, state: HeadphoneState): HeadphoneState? {
        val frame = push as? GaiaFrame ?: return null
        if (frame.vendor != C.VENDOR) return null
        val before = _status.value
        _status.update { it.apply(frame.command, frame.payload) }
        return if (_status.value == before) null else view(state)
    }

    override suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState {
        when (command) {
            is HeadsetCommand.SetMode -> setMode(command.index)
            is HeadsetCommand.SetNoise -> setNoise(command.settings)
            is HeadsetCommand.SetEq -> setEq(state.eq, command.eq)
            is HeadsetCommand.SetVendorControl -> setVendorControl(command.id, command.value)
            // Everything below is not a Sennheiser feature Ruru drives: sidetone, auto-off, auto-answer
            // and smart pause are vendor controls above (their scales differ from Bose's). Rename
            // (0x2801) is on the write guard's block-list: Smart Control keeps it disabled too.
            // Connecting a paired device (0x1402) is safe but not wired; disconnect and delete are
            // never sent. Nothing is sent for these.
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
            is HeadsetCommand.SetSidetone, is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer,
            is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts,
            is HeadsetCommand.SetShortcut, is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName,
            is HeadsetCommand.SetLeAudio, is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume,
            is HeadsetCommand.Transport, is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> throw HeadphoneCommandException(UNSUPPORTED)
        }
        return view(state)
    }

    override suspend fun labProfile(): LabProfile? = LabProfile(
        title = SennheiserLab.STACK,
        reads = SennheiserLab.reads(conn.features).map { "${it.address}: ${it.name}" },
    )

    override suspend fun scanLab(): List<String> = SennheiserLab.scan(conn)

    override suspend fun awaitClosed(): Throwable? = conn.awaitClosed()

    override fun close() = conn.close()

    // ---- Connect --------------------------------------------------------------------------

    /**
     * The probe is Qualcomm Core's API version read (`0x001D/0x0000`, `03 01` on a Momentum 4): no
     * handshake or authentication exists on this link. Tried twice with a longer wait; an error
     * frame still counts as the headset answering. A socket that opens but stays silent is Smart
     * Control holding the connection: the failure is a [HeadphoneCommandException], which the
     * controller reports as the busy case.
     */
    private suspend fun probe(): GaiaApiVersion? {
        var last: HeadphoneCommandException? = null
        for (timeout in PROBE_TIMEOUTS_MS) {
            try {
                return GaiaDiscovery.apiVersion(conn, timeout)
            } catch (e: HeadphoneCommandException) {
                last = e
                if (!conn.isOpen) break
            }
        }
        throw last ?: HeadphoneCommandException("The headphones didn't answer")
    }

    private suspend fun initialize() {
        val api = probe()
        val features = try {
            GaiaDiscovery.features(conn, C.VENDOR, SennheiserConnection.SLOW_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadphoneCommandException) {
            SennheiserLog.w("feature list failed: ${e.message}")
            null
        }
        if (features == null) {
            // Without the list the guard lets nothing else out: the headset stays read-nothing.
            SennheiserLog.w("no Sennheiser feature list (GAIA API $api): nothing else will be sent")
            return
        }
        conn.features = features
        _status.update { it.copy(features = features) }
        val qualcomm = try {
            GaiaDiscovery.features(conn, GaiaVendor.QUALCOMM, SennheiserConnection.DEFAULT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadphoneCommandException) {
            null
        }
        _status.update { it.copy(qualcommFeatures = qualcomm) }
        SennheiserLog.i("GAIA API $api, features ${features.describe()}, Qualcomm ${qualcomm?.describe() ?: "none"}")

        if (SennheiserFeature.VERSIONS in features) {
            read(C.MODEL_ID_GET, timeoutMs = SennheiserConnection.SLOW_TIMEOUT_MS)
            read(C.FIRMWARE_GET, timeoutMs = SennheiserConnection.SLOW_TIMEOUT_MS)
        }
        SennheiserLog.i("model ${_status.value.modelId ?: "unknown"} (${model?.name ?: "not driven"}), firmware ${_status.value.firmware ?: "unknown"}")

        for (feature in SennheiserFeature.NOTIFY) {
            if (feature !in features) continue
            if (feature == SennheiserFeature.USER_EQ && !_status.value.eqGen2) continue
            try {
                conn.call(C.REGISTER_NOTIFICATION, byteArrayOf(feature.toByte()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                SennheiserLog.w("push registration for feature $feature failed: ${e.message}")
            }
        }
        readAll()
    }

    /** Everything the model has, one GET each; a refused one stays unknown. */
    suspend fun readAll() {
        readBattery()
        if (has(SennheiserFeature.BATTERY)) read(C.CHARGING_GET)
        readNoise()
        val settings = model?.settings.orEmpty()
        if (SennheiserSetting.PAUSE_IN_TRANSPARENCY in settings || model == null) readIf(SennheiserFeature.TRANSPARENT_HEARING, C.TH_BEHAVIOUR_GET)
        if (SennheiserSetting.ON_HEAD in settings) readIf(SennheiserFeature.DEVICE, C.ON_HEAD_GET)
        if (SennheiserSetting.SMART_PAUSE in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.SMART_PAUSE_GET)
        if (SennheiserSetting.AUTO_ANSWER in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.AUTO_ANSWER_GET)
        if (SennheiserSetting.COMFORT_CALL in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.COMFORT_CALL_GET)
        if (SennheiserSetting.SIDETONE in settings) readIf(SennheiserFeature.GENERIC_AUDIO, C.SIDETONE_GET)
        readIf(SennheiserFeature.BATTERY, C.AUTO_OFF_GET, SennheiserWire.AUTO_OFF_TIMER)
        if (_status.value.eqGen2) {
            read(C.EQ_CONFIG_GET)
            read(C.EQ_ALL_GET, byteArrayOf(0))
            read(C.EQ_SUBMODE_GET)
        }
    }

    suspend fun readBattery() {
        readIf(SennheiserFeature.BATTERY, C.BATTERY_GET, timeoutMs = SennheiserConnection.SLOW_TIMEOUT_MS)
    }

    /** The whole noise group: what every noise write can have changed. */
    suspend fun readNoise() {
        readIf(SennheiserFeature.ANC, C.ANC_GET)
        readIf(SennheiserFeature.TRANSPARENT_HEARING, C.TH_GET)
        when (noiseFamily) {
            SennheiserNoiseFamily.SLIDER -> {
                readIf(SennheiserFeature.ANC, C.ANC_LEVEL_GET)
                readIf(SennheiserFeature.ANC, C.ANC_MODE_GET)
            }
            SennheiserNoiseFamily.EARBUDS -> {
                readIf(SennheiserFeature.ANC, C.ANC_MODE_GET)
                readIf(SennheiserFeature.TRANSPARENT_HEARING, C.TH_LEVEL_GET)
            }
            // The Accentum's mode table reads zeros and ignores writes: not read.
            SennheiserNoiseFamily.TOGGLES -> Unit
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    /**
     * Three stand-in modes ([SennheiserStateMapping.MODES]):
     *  - noise cancelling: transparency off first if on (off restores the level on a Momentum 4), then ANC on;
     *  - transparency: headbands need ANC on for it to do anything (Momentum 4 capture), so ANC on first; then transparency on;
     *  - off: transparency off if on, then ANC off.
     */
    suspend fun setMode(index: Int) {
        requireNoise()
        val s = _status.value
        when (index) {
            SennheiserStateMapping.MODE_ANC -> {
                if (s.transparencyOn == true) write(C.TH_SET, 0)
                write(C.ANC_SET, 1)
            }
            SennheiserStateMapping.MODE_TRANSPARENCY -> {
                if (!has(SennheiserFeature.TRANSPARENT_HEARING)) throw HeadphoneCommandException("These headphones have no transparency mode")
                if (noiseFamily != SennheiserNoiseFamily.EARBUDS && s.ancOn != true) write(C.ANC_SET, 1)
                write(C.TH_SET, 1)
            }
            SennheiserStateMapping.MODE_OFF -> {
                if (s.transparencyOn == true) write(C.TH_SET, 0)
                write(C.ANC_SET, 0)
            }
            else -> throw HeadphoneCommandException("Sennheiser headphones have three modes: noise cancelling, transparency and off")
        }
        readNoise()
    }

    /** The noise card: ANC on/off and, on the slider models, the ANC and transparency level. */
    suspend fun setNoise(settings: NoiseSettings) {
        requireNoise()
        val before = _status.value
        val ancChanged = settings.ancEnabled != before.ancOn
        if (ancChanged) write(C.ANC_SET, if (settings.ancEnabled) 1 else 0)
        if (noiseFamily == SennheiserNoiseFamily.SLIDER && settings.ancEnabled) {
            val level = SennheiserWire.levelFromCancelling(settings.cancellingLevel)
            // ANC on resets the level to 0 on a Momentum 4: write the wanted level after it.
            val current = if (ancChanged) 0 else before.ancLevel
            if (level != current) write(C.ANC_LEVEL_SET, level)
        }
        readNoise()
    }

    suspend fun setVendorControl(id: String, value: Int) {
        val w = SennheiserStateMapping.write(id, value, _status.value, model)
            ?: throw HeadphoneCommandException("These Sennheiser headphones have no setting \"$id\" with that value")
        SennheiserLog.i("$id -> ${C.name(w.command)} = $value")
        conn.call(w.command, w.payload)
        for (cmd in w.reread) read(cmd, if (cmd == C.AUTO_OFF_GET) SennheiserWire.AUTO_OFF_TIMER else ByteArray(0))
        if (w.touchesNoise) readNoise()
    }

    /** The three-band EQ onto the headset's bands (generation 2 only); then all gains are read back. */
    suspend fun setEq(current: HeadphoneEq?, target: HeadphoneEq) {
        val s = _status.value
        if (!s.eqGen2 || s.eqConfig == null) throw HeadphoneCommandException(EQ_UNSUPPORTED)
        for (payload in SennheiserStateMapping.eqWrites(s, current, target)) {
            when (val r = conn.request(C.VENDOR, C.EQ_BAND_SET, payload)) {
                is GaiaReply.Ok -> Unit
                // Smart Control treats "incorrect state" on an EQ write as success (the value was already set).
                is GaiaReply.Error -> if (r.code != GaiaError.INCORRECT_STATE) throw SennheiserConnection.refusedBy(C.EQ_BAND_SET, r.code)
            }
        }
        read(C.EQ_ALL_GET, byteArrayOf(0))
    }

    // ---- Helpers --------------------------------------------------------------------------

    private fun has(feature: Int) = _status.value.has(feature)

    private fun requireNoise() {
        if (!has(SennheiserFeature.ANC) && !has(SennheiserFeature.TRANSPARENT_HEARING)) {
            throw HeadphoneCommandException("These headphones didn't report any noise control")
        }
    }

    private suspend fun write(command: Int, value: Int) {
        SennheiserLog.i("write ${C.name(command)} = $value")
        conn.call(command, byteArrayOf(value.toByte()))
    }

    private suspend fun readIf(feature: Int, command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = SennheiserConnection.DEFAULT_TIMEOUT_MS) {
        if (has(feature)) read(command, payload, timeoutMs)
    }

    /** One GET folded into the status; a refusal leaves the value unknown (logged), a dead link throws. */
    private suspend fun read(command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = SennheiserConnection.DEFAULT_TIMEOUT_MS) {
        try {
            val reply = conn.call(command, payload, timeoutMs)
            _status.update { it.apply(GaiaCommand.response(command), reply) }
        } catch (e: HeadphoneCommandException) {
            if (!conn.isOpen) throw e
            SennheiserLog.d("read ${C.name(command)} failed: ${e.message}")
        }
    }

    private fun Map<Int, Int>.describe(): String = entries.joinToString(" ") { "${it.key}v${it.value}" }

    companion object {
        const val UNSUPPORTED = "Ruru's Sennheiser support doesn't have that setting"
        const val EQ_UNSUPPORTED = "These headphones' EQ needs newer firmware: update them with Sennheiser's app first"

        /** The probe's waits: a normal one, then a longer one (some headsets are slow to wake). */
        val PROBE_TIMEOUTS_MS: List<Long> = listOf(1_500L, 2_500L)
    }
}
