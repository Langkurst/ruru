package io.github.langkurst.ruru.vendors.sony

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.currentTimeMillis
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.LabProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sony's [HeadsetSession] (Earshot's SonySession and the repository around it, MIT): one
 * connection's worth of Sony MDR v2 over a [SonyConnection], mapped to [HeadphoneState] by
 * [SonyStateMapping].
 *
 * Init (Sound Connect's order): protocol info -> capability info -> device info (model name,
 * firmware, series / colour) -> support functions on table 1 and table 2 -> one capability read
 * per supported function -> the values. Only the protocol info and the table-1 support list are
 * required; any other read that fails is logged and left out. Usage logging (`C4`) is never
 * switched on. A headset on MDR v1 (4-byte protocol info) is refused before anything else is sent.
 *
 * What it runs: the three noise modes, the noise settings automations send, every vendor control
 * and power off, each followed by a read of the setting so the state shows what the headset did.
 * Every other [HeadsetCommand] throws a readable [HeadphoneCommandException] and sends nothing.
 * Every frame leaves through [SonyConnection], whose [SonyWriteGuard] refuses firmware update, test
 * modes, usage logging, resets, unpairing and anything else that is not on the allow-list.
 */
class SonySession(
    private val conn: SonyConnection,
    scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
) : HeadsetSession {
    override val family: DeviceFamily get() = DeviceFamily.SONY

    override val isOpen: Boolean get() = conn.isOpen

    @Volatile
    var snapshot: SonySnapshot = SonySnapshot()
        private set

    /** When the user last changed something: a confirmation prompt soon after is theirs to accept. */
    @Volatile
    private var lastUserWriteAt = Long.MIN_VALUE / 2

    init {
        // Confirmation prompts are answered here, whatever the controller does with the pushes.
        // UNDISPATCHED: subscribed before the first frame can arrive.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            conn.unsolicited.collect { m -> if (m.alert() != null) answerAlert(m.alert()!!) }
        }
    }

    /** Everything the headset sends unasked except confirmation prompts (answered by the session). */
    override val pushes: Flow<Any> = conn.unsolicited.filter { it.alert() == null }

    private fun SonyMessage.alert(): SonyAlert? = if (table == SonyTable.T1) SonyAlert.parse(payload) else null

    private fun fold(m: SonyMessage): SonySnapshot = SonyStateMapping.update(snapshot, m).also { snapshot = it }

    private fun t1(p: ByteArray) = SonyMessage(SonyTable.T1, p)
    private fun t2(p: ByteArray) = SonyMessage(SonyTable.T2, p)

    private fun view(state: HeadphoneState): HeadphoneState = SonyStateMapping.apply(state, snapshot, clock())

    /** Request [m] and fold the reply in; a failure is logged and swallowed (optional reads). */
    private suspend fun read(m: SonyMessage, what: String): Boolean = try {
        fold(conn.request(m))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: HeadphoneCommandException) {
        SonyLog.w("$what: ${e.message}")
        false
    }

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
        val m = push as? SonyMessage ?: return null
        if (m.alert() != null) return null
        val before = snapshot
        fold(m)
        return if (snapshot == before) null else view(state)
    }

    override suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState {
        when (command) {
            is HeadsetCommand.SetMode -> setMode(command.index)
            is HeadsetCommand.SetNoise -> setNoise(command.settings)
            is HeadsetCommand.SetVendorControl -> setVendorControl(command.id, command.value)
            is HeadsetCommand.PowerOff -> powerOff()
            // Everything below is not a Sony feature Ruru drives: Sony's EQ, auto off, sidetone and the
            // rest are vendor controls above. Nothing is sent.
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
            is HeadsetCommand.SetEq, is HeadsetCommand.SetSidetone, is HeadsetCommand.SetAutoPlayPause,
            is HeadsetCommand.SetAutoAnswer, is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff,
            is HeadsetCommand.SetVoicePrompts, is HeadsetCommand.SetShortcut, is HeadsetCommand.SetTouchControls,
            is HeadsetCommand.SetDeviceName, is HeadsetCommand.SetLeAudio, is HeadsetCommand.SetLatency,
            is HeadsetCommand.SetVolume, is HeadsetCommand.Transport, is HeadsetCommand.SwitchDevice,
            -> throw HeadphoneCommandException(UNSUPPORTED)
        }
        return view(state)
    }

    override suspend fun labProfile(): LabProfile? = LabProfile(
        title = SonyLab.STACK,
        reads = SonyLab.reads(snapshot).map { "${it.address}: ${it.name}" },
    )

    override suspend fun scanLab(): List<String> = SonyLab.scan(conn, snapshot)

    override suspend fun awaitClosed(): Throwable? = conn.awaitClosed()

    override fun close() = conn.close()

    // ---- Init and reads ---------------------------------------------------------------------

    /** The probe: the first thing every client sends. An 8-byte reply is MDR v2; 4 bytes is v1, which is refused. */
    private suspend fun readProtocol(): SonyProtocolInfo {
        val reply = conn.request(t1(SonyProtocolInfo.GET), resends = PROBE_RESENDS)
        val protocol = SonyProtocolInfo.parse(reply.payload)
            ?: throw HeadphoneCommandException("These Sony headphones sent something Ruru doesn't recognise")
        if (!protocol.isV2) throw ExplainedConnectException(Words("sony_v1_only"), retry = false)
        return protocol
    }

    private suspend fun initialize(): SonySnapshot {
        val protocol = readProtocol()
        snapshot = snapshot.copy(protocol = protocol)
        if (!protocol.isKnownVersion) SonyLog.w("unknown MDR version ${protocol.versionText}: read-only")

        read(t1(SonyCapabilityInfo.GET), "capability info")
        for (type in listOf(SonyDeviceInfo.MODEL_NAME, SonyDeviceInfo.FW_VERSION, SonyDeviceInfo.SERIES_AND_COLOR)) {
            read(t1(SonyDeviceInfo.get(type)), "device info $type")
        }

        val t1Functions = (if (protocol.table1) SonySupportFunctions.parse(conn.request(t1(SonySupportFunctions.GET)).payload) else null)
            ?: throw HeadphoneCommandException("The headphones didn't list their functions")
        val t2Functions = if (protocol.table2) {
            try {
                SonySupportFunctions.parse(conn.request(t2(SonySupportFunctions.GET)).payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                SonyLog.w("table-2 functions: ${e.message}")
                null
            }
        } else null
        snapshot = snapshot.withSupport(t1Functions, t2Functions.orEmpty())
        val s = snapshot
        SonyLog.i(
            "${s.modelName} fw ${s.firmware}, MDR ${protocol.versionText}: ${t1Functions.size} + ${t2Functions?.size ?: 0} functions, " +
                "NCASM ${s.ncAsmType?.let { "%02x".format(it) }}, EQ ${s.eqType?.let { "%02x".format(it) }}, auto-off ${s.autoOffType?.let { "%02x".format(it) }}",
        )

        // Capabilities.
        s.ncAsmType?.takeIf { SonyNcAsmShape.hasLevel(it) }?.let { read(t1(SonyNcAsm.getCapability(it)), "NCASM capability") }
        // With the display-language byte, as mos9527 asks (proven on WH-1000XM5/XM6, WF-C700N, LinkBuds Clip).
        s.eqType?.let { read(t1(SonyEqCapability.get(it) + byteArrayOf(DISPLAY_LANGUAGE_ENGLISH)), "EQ capability") }
        s.autoOffType?.let { read(t1(SonyAutoPowerOff.getCapability(it)), "auto-off capability") }
        for (gs in GENERAL_SETTING_TYPES) {
            if (gs in t1Functions) read(t1(SonyGeneralSetting.getCapability(gs)), "general setting %02x".format(gs))
        }
        if (SonyFunction.UPSCALING_AUTO_OFF in t1Functions) read(t1(SonyAudio.getCapability(AudioType.UPSCALING)), "DSEE capability")
        // Confirmation prompts on, as the app does: some settings only apply once they are answered.
        if (SonyFunction.FIXED_MESSAGE in t1Functions && snapshot.writable) {
            try {
                conn.send(t1(SonyAlert.ENABLE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                SonyLog.w("alerts: ${e.message}")
            }
        }

        readValues()
        return snapshot
    }

    /** Every value the headset announced (not the capabilities, which don't change within a connection). */
    private suspend fun readValues(): SonySnapshot {
        val s = snapshot
        readBattery()
        s.ncAsmType?.let { read(t1(SonyNcAsm.getParam(it)), "NCASM") }
        s.eqType?.let { read(t1(SonyEqSetting.get(it)), "EQ") }
        s.autoOffType?.let { read(t1(SonyAutoPowerOff.getParam(it)), "auto-off") }
        for (gs in s.generalSettings.keys.sorted()) read(t1(SonyGeneralSetting.getParam(gs)), "general setting %02x".format(gs))
        if (SonyFunction.UPSCALING_AUTO_OFF in s.t1) read(t1(SonyAudio.getParam(AudioType.UPSCALING)), "DSEE")
        s.connectionModeType?.let { read(t1(SonyAudio.getParam(it)), "connection mode") }
        if (SonyFunction.SMART_TALKING_MODE_TYPE2 in s.t1) read(t1(SonySystem.getParam(SystemType.SMART_TALKING_MODE_TYPE2)), "speak-to-chat")
        if (SonyFunction.PLAYBACK_CONTROL_BY_WEARING in s.t1) read(t1(SonySystem.getParam(SystemType.PLAYBACK_CONTROL_BY_WEARING)), "pause when taken off")
        s.voiceGuidanceType?.let { read(t2(SonyVoiceGuidance.getParam(it)), "voice guidance") }
        return checkedSnapshot()
    }

    private suspend fun readBattery(): SonySnapshot {
        for (type in SonyBattery.typesFor(snapshot.t1)) read(t1(SonyBattery.get(type)), "battery %02x".format(type))
        return checkedSnapshot()
    }

    /**
     * Each read above is optional (a function may not answer), so a link that died during them
     * would otherwise hand back the old values as a successful refresh.
     */
    private fun checkedSnapshot(): SonySnapshot {
        if (!conn.isOpen) throw HeadphoneCommandException(SonyConnection.NOT_CONNECTED)
        return snapshot
    }

    /**
     * A confirmation prompt: a change the user just made gets a yes, anything else the headset asks
     * on its own is declined. A prompt that needs no answer is ignored.
     */
    private suspend fun answerAlert(alert: SonyAlert) {
        if (!alert.needsAnswer) return
        val positive = clock() - lastUserWriteAt <= ALERT_WINDOW_MS
        SonyLog.i("confirmation prompt %02x: answering %s".format(alert.messageType, if (positive) "yes" else "no"))
        try {
            conn.send(t1(alert.answer(positive)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadphoneCommandException) {
            SonyLog.w("alert answer: ${e.message}")
        }
    }

    // ---- Writes -----------------------------------------------------------------------------

    private fun requireWritable() {
        if (!snapshot.writable) {
            throw HeadphoneCommandException("Ruru doesn't know this Sony protocol version (${snapshot.protocol?.versionText}), so it only reads settings")
        }
    }

    /** Send [message] (the user's change), then read back with [readBack] so the state is what the headset did. */
    private suspend fun write(message: SonyMessage, readBack: SonyMessage?) {
        requireWritable()
        lastUserWriteAt = clock()
        conn.send(message)
        // The headset usually pushes the new value too; the read-back covers models that don't.
        if (readBack != null) fold(conn.request(readBack))
    }

    private suspend fun setMode(index: Int) {
        val nc = snapshot.ncAsm ?: throw HeadphoneCommandException("These headphones have no noise control")
        val target = SonyStateMapping.modeFor(index) ?: throw HeadphoneCommandException("There is no listening mode in slot $index")
        if (target == SonyNoiseMode.NOISE_CANCELLING && !nc.hasNoiseCancelling) throw HeadphoneCommandException("These headphones have no noise cancelling")
        if (target == SonyNoiseMode.AMBIENT && !nc.hasAmbient) throw HeadphoneCommandException("These headphones have no ambient sound mode")
        write(t1(nc.withMode(target).encodeSet()), t1(SonyNcAsm.getParam(nc.type)))
    }

    /**
     * Automations and the noise panel speak [NoiseSettings]: `ancEnabled` false = Off; true from Off =
     * noise cancelling (or ambient where there is none); the level only means something in Ambient
     * sound, where it is mapped onto the headset's ambient range. Wind block and spatial audio have
     * no Sony equivalent here and are ignored.
     */
    private suspend fun setNoise(settings: NoiseSettings) {
        val s = snapshot
        var nc = s.ncAsm ?: throw HeadphoneCommandException("These headphones have no noise control")
        nc = when {
            !settings.ancEnabled -> nc.withMode(SonyNoiseMode.OFF)
            nc.mode == SonyNoiseMode.OFF -> nc.withMode(if (nc.hasNoiseCancelling) SonyNoiseMode.NOISE_CANCELLING else SonyNoiseMode.AMBIENT)
            else -> nc
        }
        if (nc.mode == SonyNoiseMode.AMBIENT && nc.hasLevel && settings.cancellingLevel != SonyStateMapping.ambientAsCancelling(s)) {
            nc = nc.copy(ambientLevel = SonyStateMapping.cancellingAsAmbient(s, settings.cancellingLevel))
        }
        if (nc == s.ncAsm) return
        write(t1(nc.encodeSet()), t1(SonyNcAsm.getParam(nc.type)))
    }

    private suspend fun setVendorControl(id: String, value: Int) {
        val s = snapshot
        val message = SonyStateMapping.write(s, id, value) ?: throw HeadphoneCommandException("These Sony headphones have no setting \"$id\"")
        write(message, SonyStateMapping.readBack(s, id))
    }

    /** `POWER_SET_STATUS [24, 03, 01]` USER_POWER_OFF; the link drops right after. */
    private suspend fun powerOff() {
        requireWritable()
        if (SonyFunction.POWER_OFF !in snapshot.t1) throw HeadphoneCommandException("These headphones can't be switched off from the app")
        lastUserWriteAt = clock()
        conn.send(t1(SonyBattery.USER_POWER_OFF))
    }

    companion object {
        /** A prompt within this long of the user's own change is answered yes. */
        const val ALERT_WINDOW_MS = 5_000L

        /** The probe gives up sooner than a normal send (3 resends, about 3 s with the 500 ms start delay). */
        const val PROBE_RESENDS = 3

        const val UNSUPPORTED = "Ruru's Sony support doesn't have that setting"

        private val GENERAL_SETTING_TYPES = SonyFunction.GENERAL_SETTING_1..SonyFunction.GENERAL_SETTING_4

        /** `DisplayLanguage.ENGLISH`. */
        private const val DISPLAY_LANGUAGE_ENGLISH: Byte = 0x01
    }
}
