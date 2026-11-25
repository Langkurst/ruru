package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.currentTimeMillis
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.LabProfile
import io.github.langkurst.ruru.vendors.jbl.JblCommands as C
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * JBL's [HeadsetSession] (Earshot's JblSession, MIT): one connection's worth of H-AA over a
 * [JblConnection], mapped to [HeadphoneState] by [JblStateMapping]. H-AA has no request ids, no
 * feature list and no error replies. A reply is just the next frame of the right kind, a pushed
 * change looks the same, and a headset without a feature stays silent. So:
 *  - every frame the headset sends is folded into [status] in arrival order ([JblConnection.attach]),
 *    replies and pushes alike, and each one tells the controller through [pushes];
 *  - [start] checks the product the headset reports, sends the push registration, then probes each
 *    feature once; a feature that never answered is neither shown nor written ([answered]);
 *  - a command writes, then reads back what it touched, so the screen shows what the headset did.
 *
 * What it runs: the listening modes and every vendor control it drew. Every other [HeadsetCommand]
 * throws a readable [HeadphoneCommandException] and sends nothing. Every frame leaves through
 * [JblConnection], whose [JblWriteGuard] refuses factory reset, power off, firmware transfer and
 * anything else that is not on its allow-list. Commands are serialised by the controller; the
 * connection allows one request in flight anyway.
 */
class JblSession(
    val conn: JblConnection,
    /** The Bluetooth name: names the model until the device info gives its product id. Null when the vendor doesn't know it. */
    private val deviceName: String? = null,
    private val clock: () -> Long = ::currentTimeMillis,
    private val settleMs: Long = NOTIFY_SETTLE_MS,
) : HeadsetSession {
    override val family: DeviceFamily get() = DeviceFamily.JBL

    override val isOpen: Boolean get() = conn.isOpen

    private val _status = MutableStateFlow(JblStatus())
    val status: StateFlow<JblStatus> = _status.asStateFlow()

    private val _pushes = MutableSharedFlow<Any>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One token for every frame the headset sent that changed what is known; [onPush] turns it into a new state. */
    override val pushes: Flow<Any> = _pushes.asSharedFlow()

    @Volatile
    private var probed: Set<JblFeature> = emptySet()

    /** The status the last [view] was built from, so [onPush] can tell a change from a repeat. */
    @Volatile
    private var viewed: JblStatus = JblStatus()

    /** The product id the headset reports wins; its name must match a model exactly (JBL names collide across stacks). */
    val model: JblModel? get() = JblModel.fromPid(_status.value.info?.productId) ?: JblModel.fromName(deviceName)

    /** The features that answered a probe, plus anything the headset pushed by itself. */
    val answered: Set<JblFeature> get() = probed + JblStateMapping.reported(_status.value)

    init {
        // Reading starts here: every frame the headset sends, the device info it sends by itself included, is folded in.
        conn.attach { frame ->
            _status.update { it.fold(frame) }
            _pushes.tryEmit(frame)
        }
    }

    private fun view(state: HeadphoneState): HeadphoneState {
        val s = _status.value
        viewed = s
        return JblStateMapping.apply(state, s, model, answered, clock())
    }

    // ---- HeadsetSession -------------------------------------------------------------------

    override suspend fun start(state: HeadphoneState): HeadphoneState {
        val info = probe() ?: throw HeadphoneCommandException("The headphones didn't answer")
        checkProduct(info, deviceName)
        conn.send(C.notifyOn())
        // jbl-control waits a moment before its first read: the headset is still sending what the registration set off.
        delay(settleMs)
        readAll()
        return view(state)
    }

    override suspend fun pollBattery(state: HeadphoneState): HeadphoneState {
        readBattery()
        return view(state)
    }

    override fun onPush(push: Any, state: HeadphoneState): HeadphoneState? =
        if (push !is JblFrame || _status.value == viewed) null else view(state)

    override suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState {
        when (command) {
            is HeadsetCommand.SetMode -> setMode(command.index)
            is HeadsetCommand.SetVendorControl -> setVendorControl(command.id, command.value)
            // JBL's voice prompts, EQ preset and noise-cancelling level are vendor controls above; the
            // headset's 10-band EQ is not the app's three-band one. Power off and anything else that
            // isn't a setting Ruru knows is on the guard's never list or not on its allow-list.
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
            is HeadsetCommand.SetNoise, is HeadsetCommand.SetEq, is HeadsetCommand.SetSidetone,
            is HeadsetCommand.SetAutoPlayPause, is HeadsetCommand.SetAutoAnswer, is HeadsetCommand.SetMultipoint,
            is HeadsetCommand.SetAutoOff, is HeadsetCommand.SetVoicePrompts, is HeadsetCommand.SetShortcut,
            is HeadsetCommand.SetTouchControls, is HeadsetCommand.SetDeviceName, is HeadsetCommand.SetLeAudio,
            is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume, is HeadsetCommand.Transport,
            is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> throw HeadphoneCommandException(UNSUPPORTED)
        }
        return view(state)
    }

    override suspend fun labProfile(): LabProfile = JblLab.profile()

    override suspend fun scanLab(): List<String> = JblLab.scan(conn)

    override suspend fun awaitClosed(): Throwable? = conn.awaitClosed()

    override fun close() = conn.close()

    // ---- Connect --------------------------------------------------------------------------

    /**
     * The probe is the device info: the headset sends `AA 12` by itself when the link opens (seen in
     * jbl-control's Wave Buds 2 capture), else Ruru asks with `AA 11 00`, once more when the first
     * one came cut. A link that opens and stays silent is JBL's app holding the control connection.
     */
    private suspend fun probe(): JblDeviceInfo? {
        val first = conn.awaitDeviceInfo(UNSOLICITED_INFO_MS)
        if (first != null && !first.isPartial) return first
        conn.request(C.deviceInfoGet(), timeoutMs = 2_000L, attempts = 2) { it.command == C.DEVICE_INFO && !it.truncated }
        return conn.deviceInfo
    }

    /**
     * Every probe once, in the order jbl-control and the app read them. A feature that stays silent
     * costs one timeout. Only the modes and the TLV, which every H-AA model answers, count toward
     * the connection's dead-link rule (JBL's app holding the link answers nothing at all).
     */
    suspend fun readAll() {
        probe(JblFeature.MODES, C.modeGet(), attempts = 2, optional = false, answers = ::isModeStatus)
        probe(JblFeature.LEVEL, C.levelGet(), answers = ::isManualLevel)
        readTlv(C.INFO_READ_FIDS, attempts = 2, optional = false)
        probe(JblFeature.EQ, C.eqGet(), answers = ::isEq)
        probe(JblFeature.VOICE_PROMPTS, C.voicePromptsGet(), answers = ::isVoicePrompts)
        // No bud reported: headphones may report their headband battery under its own id (notes
        // only), asked for separately so the captured read above never carries an id a headset
        // might reject. Older earbuds have no battery in the TLV at all; their read is `AA 25`.
        if (!reportsBudBattery()) {
            if (model?.earbuds != true) readTlv(C.HEADBAND_BATTERY_FIDS)
            val s = _status.value
            if (s.headband == null && model?.earbuds != false) readTwsBattery()
        }
    }

    /**
     * The battery from whichever read this headset answers: the TLV ids, else the older TWS read,
     * else the device info (headphones whose only figure is its battery byte).
     */
    suspend fun readBattery() {
        val s = _status.value
        when {
            reportsBudBattery() -> readTlv(C.BUD_BATTERY_FIDS)
            C.FID_BATTERY_HEADBAND in s.infoIds -> readTlv(C.HEADBAND_BATTERY_FIDS)
            JblFeature.TWS_BATTERY in answered -> readTwsBattery()
            s.info?.battery != null -> probe(null, C.deviceInfoGet()) { it.command == C.DEVICE_INFO }
            else -> readTlv(C.BUD_BATTERY_FIDS)
        }
    }

    private fun reportsBudBattery() = _status.value.infoIds.any { it in C.BUD_BATTERY_FIDS }

    // ---- Commands -------------------------------------------------------------------------

    /**
     * Switch to a slot ([JblStateMapping.SLOTS]). The headset answers with its new mode (`91 12`,
     * the same frame a push is). A pair of earbuds takes a mode only with both buds in, and says so
     * by reporting the old one.
     */
    suspend fun setMode(slotIndex: Int) {
        val slot = JblStateMapping.slot(slotIndex)
            ?: throw HeadphoneCommandException("JBL headphones have three listening modes and off, not a mode in slot $slotIndex")
        val listed = _status.value.modes ?: throw HeadphoneCommandException("These headphones didn't report their listening modes")
        if (slot.wire != null && slot.wire !in listed) throw HeadphoneCommandException("These headphones don't have ${slot.name}")
        JblLog.i("mode -> ${slot.name}")
        // Only a status that shows the new mode is the answer: without request ids, an older push
        // (or a status sent just before the switch) would otherwise pass for a decline.
        val wanted = slot.wire ?: 0
        val reply = conn.request(C.modeSet(listed, slot.wire), attempts = 1) { isModeStatus(it) && JblStatus().fold(it).activeMode == wanted }
        // No such status: ask once, so a change made without a report still counts and a decline shows the mode kept.
        if (reply == null && conn.request(C.modeGet(), attempts = 2, answers = ::isModeStatus) == null) {
            throw HeadphoneCommandException("The headphones didn't confirm the mode change")
        }
        val now = JblStateMapping.slotOfActive(_status.value.activeMode)
        if (now != slot) {
            // jbl-control's finding on a Wave Buds 2: the buds decline a mode while one is in its case.
            val hint = if (JblStateMapping.isEarbuds(_status.value, model)) " (JBL earbuds may need both buds in for that)" else ""
            throw HeadphoneCommandException("The headphones stayed in ${now?.name ?: "their current mode"}$hint")
        }
    }

    suspend fun setVendorControl(id: String, value: Int) {
        if (JblStateMapping.controls(_status.value, answered).none { it.id == id }) {
            throw HeadphoneCommandException("These JBL headphones have no setting \"$id\"")
        }
        val frame = JblStateMapping.write(id, value)
            ?: throw HeadphoneCommandException("That isn't a value these JBL headphones take for \"$id\"")
        JblLog.i("$id = $value")
        conn.send(frame)
        // Read back what the write touched. A read that disagrees is logged, not thrown: the headset may simply
        // apply the change a moment later, and the state published afterwards is what it reports either way.
        val agreed = when (id) {
            JblStateMapping.ANC_LEVEL -> {
                probe(JblFeature.LEVEL, C.levelGet(), answers = ::isManualLevel)
                _status.value.ancLevel == value
            }
            JblStateMapping.EQ_PRESET -> {
                probe(JblFeature.EQ, C.eqGet(), answers = ::isEq)
                _status.value.eqCategory == C.EQ_PRESETS[value].category
            }
            else -> {
                probe(JblFeature.VOICE_PROMPTS, C.voicePromptsGet(), answers = ::isVoicePrompts)
                _status.value.voicePrompts == (value == 1)
            }
        }
        if (!agreed) JblLog.w("$id: the headset reports another value after the write")
    }

    // ---- Helpers --------------------------------------------------------------------------

    private suspend fun readTlv(fids: List<Int>, attempts: Int = 1, optional: Boolean = true) {
        // The call-status push is a TLV return too: only an answer carrying an id we asked for is ours.
        probe(JblFeature.TLV, C.infoGet(fids), attempts, optional) { f -> f.command == C.INFO && f.sub == TLV_RETURN && tlvIds(f).any { it in fids } }
    }

    private suspend fun readTwsBattery() {
        probe(JblFeature.TWS_BATTERY, C.batteryGet()) { it.command == C.BATTERY }
    }

    /**
     * One read folded into [status] by [JblConnection.attach]; a silent headset leaves the feature
     * unknown. A dead link throws. [optional] reads (the default) never count toward closing the link.
     */
    private suspend fun probe(
        feature: JblFeature?,
        frame: JblFrame,
        attempts: Int = 1,
        optional: Boolean = true,
        answers: (JblFrame) -> Boolean,
    ): JblFrame? {
        val reply = conn.request(frame, attempts = attempts, countsSilence = !optional, answers = answers)
        if (reply == null) JblLog.d("no answer to ${C.name(frame.command)}") else if (feature != null) probed += feature
        return reply
    }

    private fun isModeStatus(f: JblFrame) = f.command == C.LISTENING && f.sub == C.MODE_STATUS

    private fun isManualLevel(f: JblFrame) =
        f.command == C.LISTENING && f.sub == C.LEVEL_STATUS && (1 until f.size - 1 step 2).any { f[it] == C.LEVEL_MANUAL_ANC }

    /** The EQ return, not an echo of the read (its sub-command is the read's own, 01). */
    private fun isEq(f: JblFrame) = f.command == C.EQ && f.size >= C.EQ_HEADER_SIZE && f.sub != C.EQ_GET

    private fun isVoicePrompts(f: JblFrame) = f.command == C.VOICE_PROMPTS && f.sub == VOICE_PROMPTS_RETURN

    /** The ids in a TLV return: `[marker][fid u16 BE][len][value]…`. */
    private fun tlvIds(f: JblFrame): Set<Int> {
        val ids = mutableSetOf<Int>()
        var i = 1
        while (i + 3 <= f.size) {
            ids += (f[i] shl 8) or f[i + 1]
            i += 3 + f[i + 2]
        }
        return ids
    }

    companion object {
        const val UNSUPPORTED = "Ruru's JBL support doesn't have that setting"

        /** jbl-control pauses this long after the push registration before its first read. */
        const val NOTIFY_SETTLE_MS = 300L

        /** How long to wait for the device info the headset sends by itself. */
        const val UNSOLICITED_INFO_MS = 1_500L

        /** The marker byte of a TLV return and the sub-command of a voice-prompt state. */
        private const val TLV_RETURN = 0x02
        private const val VOICE_PROMPTS_RETURN = 0x05

        /**
         * Throws for a product id of another JBL stack (a newer protocol or Airoha's), and for a
         * headset that didn't give its product id at all unless its Bluetooth name ([name], when the
         * vendor knows it) is an H-AA model exactly. Nothing but the device-info read has been sent by then. Pure, for the tests.
         */
        fun checkProduct(info: JblDeviceInfo, name: String?) {
            JblModel.refusal(info.productId)?.let { throw ExplainedConnectException(Words(it), retry = false) }
            if (info.productId == null && JblModel.fromName(name) == null) {
                throw ExplainedConnectException(Words(JblModel.UNKNOWN), retry = false)
            }
        }
    }
}
