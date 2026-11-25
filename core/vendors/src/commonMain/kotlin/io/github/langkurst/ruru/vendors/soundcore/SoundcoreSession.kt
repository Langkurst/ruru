package io.github.langkurst.ruru.vendors.soundcore

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Soundcore's [HeadsetSession] (Earshot's SoundcoreHeadphoneRepository, MIT, without Android): one
 * connection's worth of Soundcore over a [SoundcoreConnection], mapped to [HeadphoneState] by
 * [SoundcoreStateMapping] (Sleep A30) or [SoundcoreModelMapping] (every other model). Two paths:
 *  - the **Sleep A30** (D1301): noise cancelling on/off (`06 87`), the tap controls (`10 94`), the
 *    listening mode and the sleep options, each a single byte the earbuds report in their 150-byte
 *    state;
 *  - **every other model on the vendor service** ([SoundcoreModel]): the state is read through
 *    [SoundcoreStateReader] for battery, charging, firmware and mode; a model whose layout the
 *    research documents and whose state matches it (size, length byte, serial) can also change its
 *    ambient mode, noise-cancelling type and level, wind and settings ([SoundcoreWrites]).
 *    Anything else is read-only.
 *
 * The session is handed only a byte pipe, so it learns the model from the first state read: the
 * serial inside it names the model ([SoundcoreStateReader.identify]), and until then only the
 * state request leaves the phone. Every write re-reads the state, so the screen shows what the
 * earbuds did, and a model that acknowledges a write and ignores it is reported. Everything Ruru
 * doesn't drive throws a readable [HeadphoneCommandException] and sends nothing, and every frame
 * leaves through [SoundcoreConnection], whose [SoundcoreWriteGuard] refuses unpairing, pairing
 * mode, power off, the find tone, resets and anything that is not on the allow-list.
 */
class SoundcoreSession(
    private val conn: SoundcoreConnection,
    @Suppress("unused") private val scope: kotlinx.coroutines.CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
) : HeadsetSession {
    override val family: DeviceFamily get() = DeviceFamily.SOUNDCORE

    override val isOpen: Boolean get() = conn.isOpen

    /** Everything the earbuds send unasked (state, battery, charging and mode pushes). */
    override val pushes: Flow<Any> = conn.unsolicited

    /** The model of this session, once the first state read has told. */
    @Volatile
    var model: SoundcoreModel? = null
        private set

    /** Sleep A30: the last state that parsed. */
    @Volatile
    private var a30: D1301State? = null

    /** Any other model: the last state that parsed, which the writes are built from. */
    @Volatile
    private var latest: SoundcoreState? = null

    /** The state of a known model was a shape no layout fits. */
    @Volatile
    private var unreadable = false

    private val sleepA30: Boolean get() = model == SoundcoreModel.SLEEP_A30

    private fun view(state: HeadphoneState): HeadphoneState {
        val m = model ?: return state
        if (m == SoundcoreModel.SLEEP_A30) return a30?.let { SoundcoreStateMapping.apply(state, it, clock()) } ?: state
        latest?.let { return SoundcoreModelMapping.apply(state, it, clock()) }
        return if (unreadable) SoundcoreModelMapping.unreadable(state, m, clock()) else state
    }

    // ---- HeadsetSession -----------------------------------------------------------------------

    override suspend fun start(state: HeadphoneState): HeadphoneState {
        readState()
        if (model == null) throw ExplainedConnectException(Words(UNKNOWN_MODEL), retry = false)
        sessionInit()
        return view(state)
    }

    override suspend fun pollBattery(state: HeadphoneState): HeadphoneState {
        readState()
        return view(state)
    }

    override fun onPush(push: Any, state: HeadphoneState): HeadphoneState? {
        val packet = push as? SoundcorePacket ?: return null
        val m = model ?: return null
        val changed = if (m == SoundcoreModel.SLEEP_A30) applyA30Push(packet) else applyModelPush(m, packet)
        return if (changed) view(state) else null
    }

    override suspend fun run(command: HeadsetCommand, state: HeadphoneState): HeadphoneState {
        when (command) {
            is HeadsetCommand.SetMode -> setMode(command.index)
            is HeadsetCommand.SetNoise -> setNoise(command.settings)
            is HeadsetCommand.SetTouchControls -> setTouchControls(command.on)
            is HeadsetCommand.SetVendorControl -> setVendorControl(command.id, command.value)
            // Everything below is not a Soundcore feature Ruru drives (power off has no command here:
            // its ids are on the guard's never-list). Nothing is sent.
            is HeadsetCommand.SaveMode, is HeadsetCommand.RemoveMode, is HeadsetCommand.SetFavorites,
            is HeadsetCommand.SetEq, is HeadsetCommand.SetSidetone, is HeadsetCommand.SetAutoPlayPause,
            is HeadsetCommand.SetAutoAnswer, is HeadsetCommand.SetMultipoint, is HeadsetCommand.SetAutoOff,
            is HeadsetCommand.SetVoicePrompts, is HeadsetCommand.SetShortcut, is HeadsetCommand.SetDeviceName,
            is HeadsetCommand.SetLeAudio, is HeadsetCommand.SetLatency, is HeadsetCommand.SetVolume,
            is HeadsetCommand.Transport, is HeadsetCommand.SwitchDevice, HeadsetCommand.PowerOff,
            -> throw HeadphoneCommandException(UNSUPPORTED)
        }
        return view(state)
    }

    override suspend fun labProfile(): LabProfile? = LabProfile(
        title = SoundcoreLab.STACK,
        reads = SoundcoreLab.reads(model).map { "${SoundcorePacket.commandName(it.command)}: ${it.name}" },
    )

    override suspend fun scanLab(): List<String> = SoundcoreLab.scan(conn, model)

    override suspend fun awaitClosed(): Throwable? = conn.awaitClosed()

    override fun close() = conn.close()

    // ---- Reads --------------------------------------------------------------------------------

    /** `01 01`; some models answer a first request with a bare ack (soundcorebridge saw it on the Space 2): ask again for the body. */
    private suspend fun readState() {
        var reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        if (reply.body.isEmpty() && !sleepA30) reply = conn.request(SoundcoreCommands.REQUEST_STATE)
        if (reply.body.isEmpty()) throw HeadphoneCommandException("The earbuds didn't send their state")
        applyState(reply.body)
    }

    private fun applyState(body: ByteArray) {
        val m = model ?: conn.model ?: SoundcoreStateReader.identify(body)
        if (m == null) {
            SoundcoreLog.w("state of ${body.size} bytes names no Soundcore model Ruru drives")
            return
        }
        if (model != m) {
            model = m
            SoundcoreLog.i("${m.code} ${m.displayName}")
        }
        if (m == SoundcoreModel.SLEEP_A30) applyA30State(body) else applyModelState(m, body)
    }

    private fun applyA30State(body: ByteArray) {
        val parsed = D1301State.parse(body)
        // The Sleep A30's serial starts with the model digits: writes only for a state that says so.
        val ours = parsed != null && parsed.serial?.startsWith(SoundcoreModel.SLEEP_A30.serialPrefix) == true
        conn.identify(SoundcoreModel.SLEEP_A30, writable = ours)
        if (parsed == null) {
            SoundcoreLog.w("state body of ${body.size} bytes is not the Sleep A30 layout")
            return
        }
        // No serial in the log: on the Sleep A30 it embeds the earbuds' address.
        SoundcoreLog.d("state: anc=${parsed.ancOn} battery=${parsed.leftPercent}/${parsed.rightPercent} fw=${parsed.firmware} tapsOff=${parsed.tapControlsDisabled} writable=$ours")
        a30 = parsed
    }

    /**
     * Every other model: read the state through its table. Writes are allowed only if the state
     * matched the layout Ruru knows (size, length byte, serial); otherwise the model stays
     * read-only. The serial and the raw bytes are never logged.
     */
    private fun applyModelState(m: SoundcoreModel, body: ByteArray) {
        val parsed = SoundcoreStateReader.read(m, body)
        if (parsed == null) {
            SoundcoreLog.w("${m.code}: state of ${body.size} bytes matches no layout")
            latest = null
            unreadable = true
            conn.identify(m, writable = false)
            return
        }
        unreadable = false
        conn.identify(m, writable = parsed.writable)
        latest = parsed
        SoundcoreLog.d("${m.code}: ${body.size} bytes, battery=${parsed.leftPercent}/${parsed.rightPercent}/${parsed.overallPercent}, mode=${parsed.ambient}, writable=${parsed.writable}")
    }

    /** `05 01` then `05 81` once, for the models whose table asks for it (the app's own session start). */
    private suspend fun sessionInit() {
        val m = model ?: return
        if (m == SoundcoreModel.SLEEP_A30 || m.layout?.sessionInit != true || !conn.writesUnlocked) return
        try {
            conn.request(SoundcoreCommands.SESSION_INFO, timeoutsMs = listOf(1_500L))
            delay(SESSION_INIT_GAP_MS)
            conn.request(SoundcoreCommands.SESSION_INIT, timeoutsMs = listOf(1_000L))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadphoneCommandException) {
            SoundcoreLog.d("session init not answered: ${e.message}")
        }
    }

    // ---- Pushes (no suspending, no talking to the earbuds) -------------------------------------

    private fun applyA30Push(packet: SoundcorePacket): Boolean {
        when (packet.command) {
            SoundcoreCommands.REQUEST_STATE -> {
                val before = a30
                applyA30State(packet.body)
                return a30 != before
            }
            SoundcoreCommands.PUSH_BATTERY -> {
                val current = a30 ?: return false
                val (left, right) = D1301State.parseBattery(packet.body) ?: return false
                val next = current.copy(leftPercent = left, rightPercent = right)
                a30 = next
                return next != current
            }
        }
        SoundcoreLog.d("push ${SoundcorePacket.commandName(packet.command)} ignored")
        return false
    }

    private fun applyModelPush(m: SoundcoreModel, packet: SoundcorePacket): Boolean {
        val current = latest
        val next: SoundcoreState? = when (packet.command) {
            SoundcoreCommands.REQUEST_STATE -> {
                if (packet.body.isEmpty()) return false
                applyModelState(m, packet.body)
                return true
            }
            SoundcoreCommands.PUSH_BATTERY -> current?.let { c ->
                SoundcoreStateReader.batteryPush(m, packet.body)?.let { c.withBattery(it.left, it.right, it.case, it.overall) }
            }
            SoundcoreCommands.PUSH_CHARGING -> current?.withCharging(SoundcoreStateReader.chargingPush(packet.body))
            SoundcoreCommands.PUSH_SOUND_MODES -> {
                val codec = current?.modes?.codec
                if (current != null && codec != null && packet.body.size == codec.size) current.withModeBlock(SoundModes(codec, packet.body)) else null
            }
            else -> null
        }
        if (next == null) {
            SoundcoreLog.d("push ${SoundcorePacket.commandName(packet.command)} ignored")
            return false
        }
        latest = next
        return next != current
    }

    // ---- Writes -------------------------------------------------------------------------------

    /** The Sleep A30: send one single-setting write, then re-read so the state is what the earbuds did. */
    private suspend fun writeA30(name: String, command: Int, body: ByteArray) {
        SoundcoreLog.i("$name -> ${SoundcorePacket.commandName(command)} (${body.size} bytes)")
        conn.request(command, body)
        readState()
    }

    /**
     * A change to a newer model, built from the last state that parsed ([SoundcoreWrites] refuses
     * with a sentence when the model is read-only or the transition isn't one the app makes). After
     * the write the state is re-read, and a change that did not show up is reported: the earbuds may
     * acknowledge a write and discard it.
     */
    private suspend fun writeModel(name: String, build: (SoundcoreState) -> SoundcorePacket) {
        val before = latest ?: throw HeadphoneCommandException("The earbuds haven't reported their state yet")
        val packet = try {
            build(before)
        } catch (e: SoundcoreWriteRefused) {
            throw HeadphoneCommandException(e.message ?: UNSUPPORTED, e)
        }
        // Command and size only: the body is the earbuds' own settings and nothing needs logging.
        SoundcoreLog.i("$name -> ${SoundcorePacket.commandName(packet.command)} (${packet.body.size} bytes)")
        conn.request(packet.command, packet.body)
        readState()
        if (!took(before, packet)) {
            delay(VERIFY_DELAY_MS)
            readState()
            if (!took(before, packet)) throw HeadphoneCommandException(NOT_APPLIED)
        }
    }

    private fun took(before: SoundcoreState, packet: SoundcorePacket): Boolean =
        latest?.let { SoundcoreWrites.confirms(before, it, packet) } == true

    private suspend fun setMode(index: Int) {
        if (sleepA30) {
            return when (index) {
                SoundcoreStateMapping.MODE_ANC -> writeA30("noise cancelling", SoundcoreCommands.SET_ANC_D1301, byteArrayOf(1))
                SoundcoreStateMapping.MODE_OFF -> writeA30("noise cancelling", SoundcoreCommands.SET_ANC_D1301, byteArrayOf(0))
                else -> throw HeadphoneCommandException("The Sleep A30 has two modes: noise cancelling and off")
            }
        }
        val mode = SoundcoreModelMapping.ambientFor(index)
            ?: throw HeadphoneCommandException("These earbuds have three modes: noise cancelling, transparency and normal")
        writeModel("mode ${mode.name.lowercase()}") { SoundcoreWrites.setAmbient(it, mode) }
    }

    private suspend fun setNoise(settings: NoiseSettings) {
        if (sleepA30) return writeA30("noise cancelling", SoundcoreCommands.SET_ANC_D1301, byteArrayOf(if (settings.ancEnabled) 1 else 0))
        val mode = if (settings.ancEnabled) AmbientMode.NOISE_CANCELLING else AmbientMode.NORMAL
        writeModel("noise cancelling") { SoundcoreWrites.setAmbient(it, mode) }
    }

    private suspend fun setTouchControls(enabled: Boolean) {
        if (!sleepA30) throw HeadphoneCommandException(UNSUPPORTED)
        writeA30("tap controls", SoundcoreCommands.SET_TAP_DISABLED, byteArrayOf(if (enabled) 0 else 1))
    }

    private suspend fun setVendorControl(id: String, value: Int) {
        if (!sleepA30) return writeModel(id) { SoundcoreWrites.control(it, id, value) }
        val (command, body) = SoundcoreStateMapping.write(id, value)
            ?: throw HeadphoneCommandException("The Sleep A30 has no setting \"$id\"")
        writeA30(id, command, body)
    }

    companion object {
        /** The key of the explained refusal for a Soundcore whose state names no model Ruru drives. */
        const val UNKNOWN_MODEL = "soundcore_unknown_model"

        const val UNSUPPORTED = "Ruru's Soundcore support doesn't have that setting on these earbuds yet"
        const val NOT_APPLIED = "The earbuds acknowledged that but didn't change. Their firmware may differ from the layout Ruru knows."
        private const val SESSION_INIT_GAP_MS = 300L
        private const val VERIFY_DELAY_MS = 500L
    }
}
