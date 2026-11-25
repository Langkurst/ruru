package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.link.ByteTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.Volatile

/**
 * The earbud (or headphone) side of a Soundcore device, in-process: demo mode and the end-to-end
 * tests (Earshot's VirtualSleepA30 and VirtualSoundcore, MIT). [model] is the Sleep A30, whose
 * 150-byte state it lays out from the offsets in [D1301State], or any model with a
 * [SoundcoreLayout], which it lays out from the model's own table (header, serial, mode block,
 * length byte, case battery, settings). It answers the state request, applies the writes the model
 * documents, answers the session-init the table asks for, and, like real firmware, stays silent on
 * anything it doesn't know. Written from the offsets in the research notes only; no capture bytes
 * are used (the OpenSCQ30 captures are GPL).
 *
 * The switches exist for the tests: [ignoreWrites] acknowledges a write and discards it (the Space 2
 * before its handshake), [bareAckFirst] answers the first state request with an empty body.
 */
class VirtualSoundcore(
    val model: SoundcoreModel = SoundcoreModel.SLEEP_A30,
    var leftBattery: Int = if (model == SoundcoreModel.SLEEP_A30) 7 else 4,
    var rightBattery: Int = if (model == SoundcoreModel.SLEEP_A30) 6 else 5,
    var caseBattery: Int = 3,
    var ignoreWrites: Boolean = false,
    var bareAckFirst: Boolean = false,
    /** Sleep A30: noise cancelling on. */
    var ancOn: Boolean = true,
    /** Sleep A30: tap controls switched off. */
    var tapControlsDisabled: Boolean = false,
) {
    private val layout: SoundcoreLayout? = model.layout

    init {
        check(model == SoundcoreModel.SLEEP_A30 || layout != null) { "${model.code} has no layout to emulate" }
    }

    private val sleepA30: Boolean get() = model == SoundcoreModel.SLEEP_A30

    /** The sound-mode block as stored: noise cancelling, manual level 3 / adaptive 1, manual type, wind off. */
    var modeBlock: ByteArray = ByteArray(layout?.codec?.size ?: 0).also {
        if (it.size > 1) it[1] = 0x31
    }

    /** Single-byte settings by id (a model's), and for auto-off `enabled` / `index`. */
    val settings = mutableMapOf<String, Int>()
    var autoOffEnabled: Boolean = false
    var autoOffIndex: Int = 1

    /** Sleep A30: single-byte settings by state offset (listening mode 65, prompts, sleep options), see [D1301State]. */
    val a30Settings = mutableMapOf<Int, Int>()

    /** Every command received, for the tests. */
    val received = mutableListOf<SoundcorePacket>()

    private var answeredState = false

    fun stateBody(): ByteArray = if (sleepA30) a30Body() else modelBody(checkNotNull(layout))

    private fun a30Body(): ByteArray {
        val b = ByteArray(150)
        b[0] = 1
        b[1] = 1
        b[2] = leftBattery.toByte()
        b[3] = rightBattery.toByte()
        "01.91".encodeToByteArray().copyInto(b, 4)
        "01.91".encodeToByteArray().copyInto(b, 9)
        (model.serialPrefix + "DEMO00000001").encodeToByteArray().copyInto(b, 14)
        "01.68".encodeToByteArray().copyInto(b, 36)
        b[140] = if (ancOn) 1 else 0
        b[146] = if (tapControlsDisabled) 1 else 0
        for ((offset, value) in a30Settings) b[offset] = value.toByte()
        return b
    }

    private fun modelBody(layout: SoundcoreLayout): ByteArray {
        val b = ByteArray(layout.stateSize ?: (layout.modeBlockAt + layout.codec.size))
        val serial = model.serialPrefix + "DEMO00000001"
        when (model.header) {
            SoundcoreHeader.G2H -> {
                b[0] = leftBattery.toByte()
                "02.45".encodeToByteArray().copyInto(b, 2)
                serial.encodeToByteArray().copyInto(b, 7)
            }
            SoundcoreHeader.SLEEP -> {
                b[1] = 1
                b[2] = leftBattery.toByte()
                b[3] = rightBattery.toByte()
                "01.23".encodeToByteArray().copyInto(b, 4)
                "01.23".encodeToByteArray().copyInto(b, 9)
                serial.encodeToByteArray().copyInto(b, 14)
            }
            else -> {
                b[1] = 1
                b[2] = leftBattery.toByte()
                b[3] = rightBattery.toByte()
                "01.23".encodeToByteArray().copyInto(b, 6)
                "01.23".encodeToByteArray().copyInto(b, 11)
                serial.encodeToByteArray().copyInto(b, 16)
            }
        }
        layout.lengthByteAt?.let { b[it] = (layout.modeBlockAt - it).toByte() }
        modeBlock.copyInto(b, layout.modeBlockAt)
        layout.caseBatteryAt?.let { b[it] = caseBattery.toByte() }
        layout.caseFirmwareAt?.let { "03.04".encodeToByteArray().copyInto(b, it) }
        for (s in layout.settings) {
            if (s.autoOff) {
                b[s.at] = if (autoOffEnabled) 1 else 0
                b[s.at + 1] = autoOffIndex.toByte()
            } else {
                b[s.at] = (settings[s.id] ?: 0).toByte()
            }
        }
        return b
    }

    /** The reply to [packet], or null (unknown command: no answer). */
    fun answer(packet: SoundcorePacket): SoundcorePacket? {
        received += packet
        fun ack(body: ByteArray = ByteArray(0)) = SoundcorePacket(packet.command, body, inbound = true)
        if (packet.command == SoundcoreCommands.REQUEST_STATE) {
            return if (bareAckFirst && !answeredState) {
                answeredState = true
                ack()
            } else {
                answeredState = true
                ack(stateBody())
            }
        }
        return if (sleepA30) answerA30(packet, ::ack) else answerModel(packet, ::ack)
    }

    private fun answerA30(packet: SoundcorePacket, ack: (ByteArray) -> SoundcorePacket): SoundcorePacket? {
        val first = packet.body.firstOrNull()?.toInt()
        return when (packet.command) {
            SoundcoreCommands.SET_ANC_D1301 -> {
                if (!ignoreWrites) ancOn = first == 1
                ack(ByteArray(0))
            }
            SoundcoreCommands.SET_TAP_DISABLED -> {
                if (!ignoreWrites) tapControlsDisabled = first == 1
                ack(ByteArray(0))
            }
            SoundcoreCommands.SET_SLEEP_TIMER -> {
                // [FF FF FF action]: only the post-sleep action (offset 144).
                if (!ignoreWrites) packet.body.getOrNull(3)?.let { a30Settings[144] = it.toInt() and 0xFF }
                ack(ByteArray(0))
            }
            in A30_SETTING_OFFSETS -> {
                if (!ignoreWrites) a30Settings[A30_SETTING_OFFSETS.getValue(packet.command)] = first ?: 0
                ack(ByteArray(0))
            }
            else -> null
        }
    }

    private fun answerModel(packet: SoundcorePacket, ack: (ByteArray) -> SoundcorePacket): SoundcorePacket? {
        val layout = checkNotNull(layout)
        return when (packet.command) {
            SoundcoreCommands.SESSION_INFO -> if (layout.sessionInit) ack(ByteArray(47)) else null
            SoundcoreCommands.SESSION_INIT -> if (layout.sessionInit) ack(byteArrayOf(0)) else null
            SoundcoreCommands.SET_SOUND_MODES -> {
                if (packet.body.size != layout.codec.size) null
                else {
                    if (!ignoreWrites) modeBlock = packet.body.copyOf()
                    ack(ByteArray(0))
                }
            }
            else -> {
                val setting = layout.settings.firstOrNull { it.command == packet.command }
                if (setting == null) null
                else {
                    if (!ignoreWrites) {
                        if (setting.autoOff) {
                            autoOffEnabled = packet.body.getOrNull(0)?.toInt() == 1
                            autoOffIndex = packet.body.getOrNull(1)?.toInt() ?: autoOffIndex
                        } else {
                            settings[setting.id] = packet.body.firstOrNull()?.toInt() ?: 0
                        }
                    }
                    ack(ByteArray(0))
                }
            }
        }
    }

    companion object {
        /** The Sleep A30, which is the one the demo shows. */
        const val A30_NAME = "Soundcore Sleep A30"

        /** Where each single-byte Sleep A30 setting sits in the state body. */
        private val A30_SETTING_OFFSETS = mapOf(
            SoundcoreCommands.SET_LISTENING_MODE to 65,
            SoundcoreCommands.SET_DEFAULT_LISTENING_MODE to 66,
            SoundcoreCommands.SET_LOW_BATTERY_PROMPT to 69,
            SoundcoreCommands.SET_AUTO_OFF_PROMPT to 137,
            SoundcoreCommands.SET_LISTENING_MODE_PROMPT to 138,
            SoundcoreCommands.SET_INCOMING_CALLS to 141,
            SoundcoreCommands.SET_ANC_PROMPT to 147,
            SoundcoreCommands.SET_AUTO_SWITCH_ASLEEP to 148,
        )
    }
}

/** A [ByteTransport] whose far end is a [VirtualSoundcore]; it tells the connection which model it plays. */
class VirtualSoundcoreTransport(private val unit: VirtualSoundcore) : ByteTransport, SoundcoreModelTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = SoundcoreFrameBuffer()

    override val soundcoreModel: SoundcoreModel get() = unit.model

    @Volatile
    override var isConnected: Boolean = true
        private set
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        for (packet in frames.feed(bytes)) unit.answer(packet)?.let { inbound.trySend(it.encode()) }
    }

    /** Push a frame as the earbuds would (battery change, mode tapped on the buds). */
    fun push(packet: SoundcorePacket) {
        inbound.trySend(packet.encode())
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }
}

/**
 * A byte pipe to a [VirtualSoundcore] that does not say which model it plays, so the session has to
 * learn it from the state the way it does on a real headset.
 */
class AnonymousVirtualSoundcoreTransport(private val unit: VirtualSoundcore) : ByteTransport {
    private val inner = VirtualSoundcoreTransport(unit)
    override val isConnected: Boolean get() = inner.isConnected
    override val incoming: Flow<ByteArray> get() = inner.incoming
    override suspend fun write(bytes: ByteArray) = inner.write(bytes)
    fun push(packet: SoundcorePacket) = inner.push(packet)
    override fun close() = inner.close()
}
