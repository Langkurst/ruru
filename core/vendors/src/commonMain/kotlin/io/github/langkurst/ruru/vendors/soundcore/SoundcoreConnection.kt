package io.github.langkurst.ruru.vendors.soundcore

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ManagedLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor

/** A byte pipe that knows which Soundcore model is at its far end (the demo's), so no state read is needed to tell. */
interface SoundcoreModelTransport {
    val soundcoreModel: SoundcoreModel
}

/**
 * One session with a Soundcore headset (Earshot's SoundcoreConnection, MIT). Requests go one at a
 * time: each waits for a frame with the same command id (the headset echoes it, usually with an
 * empty body; `01 01` comes back as the full state). Unknown commands get no answer at all, so a
 * request retries three times with growing waits (500 / 1000 / 1500 ms, as OpenSCQ30 does) before
 * failing. Frames nobody asked for (state and battery pushes) go to [unsolicited].
 *
 * Every outgoing frame passes [SoundcoreWriteGuard] first: unpair, pairing mode, power off and any
 * unknown write never leave the phone. [model] selects the allow-list. It starts as the one the
 * transport names, or null, and until it is [identify]-ed only the state request is allowed. A
 * write also needs [writesUnlocked]: the session unlocks it once the state it read matched the
 * layout Ruru knows for that model (size, length byte, serial). The Sleep A30 is unlocked by its
 * state's serial alone.
 */
class SoundcoreConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    private val minWriteGapMs: Long = MIN_WRITE_GAP_MS,
    /** The model the transport says is at the other end (the demo's); null until a state read says. */
    model: SoundcoreModel? = (transport as? SoundcoreModelTransport)?.soundcoreModel,
) : ManagedLink {
    @Volatile
    var model: SoundcoreModel? = model
        private set

    @Volatile
    var writesUnlocked: Boolean = false
        private set

    /** Fix the model the state read identified, and set whether writes may leave. */
    fun identify(model: SoundcoreModel, writable: Boolean) {
        this.model = model
        writesUnlocked = writable
    }

    /** Lock or unlock writes for the model already known (a later state read may not match). */
    fun unlockWrites(unlocked: Boolean) {
        writesUnlocked = unlocked && model != null
    }

    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = SoundcoreFrameBuffer(onJunk = { n -> SoundcoreLog.w("dropped $n undecodable bytes") })
    private val requests = Mutex()

    private class Pending(val command: Int, val answer: CompletableDeferred<SoundcorePacket>)

    /** The one request waiting for its answer: [requests] allows one at a time. */
    @Volatile private var pending: Pending? = null
    private val closed = CompletableDeferred<Throwable?>()

    private val _unsolicited = MutableSharedFlow<SoundcorePacket>(extraBufferCapacity = 32)
    val unsolicited: SharedFlow<SoundcorePacket> = _unsolicited.asSharedFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** True once a valid frame arrived: the far end speaks this protocol. */
    @Volatile
    var hasFramed: Boolean = false
        private set

    init {
        scope.launch {
            try {
                transport.incoming.collect { chunk -> frames.feed(chunk).forEach(::dispatch) }
                finish(null)
            } catch (e: Throwable) {
                finish(e)
                if (e is CancellationException) throw e
            }
        }
    }

    private fun dispatch(packet: SoundcorePacket) {
        if (!packet.inbound) return // an echo of our own frame
        hasFramed = true
        // Command and size only: some frames carry other people's data (the `0b 01` host list has the
        // phone's name and address), and logs hold nothing personal.
        SoundcoreLog.d("<- ${SoundcorePacket.commandName(packet.command)} (${packet.body.size} bytes)")
        val waiting = pending
        if (waiting != null && waiting.command == packet.command && waiting.answer.complete(packet)) return
        _unsolicited.tryEmit(packet)
    }

    /**
     * Send [command] with [body] and return the headset's answer. Throws [HeadphoneCommandException]
     * when the command is not on the allow-list, the link is closed, or nothing answers.
     */
    suspend fun request(
        command: Int,
        body: ByteArray = ByteArray(0),
        timeoutsMs: List<Long> = DEFAULT_TIMEOUTS_MS,
    ): SoundcorePacket {
        val packet = SoundcorePacket(command, body)
        if (!SoundcoreWriteGuard.isAllowed(model, packet)) {
            throw HeadphoneCommandException("Ruru doesn't send that command to Soundcore headsets (${SoundcorePacket.commandName(command)})")
        }
        if (!SoundcoreWriteGuard.isRead(packet) && !writesUnlocked) {
            throw HeadphoneCommandException("Ruru hasn't matched these earbuds to a layout it knows, so it only reads them")
        }
        return requests.withLock {
            for (timeout in timeoutsMs) {
                if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
                val slot = Pending(command, CompletableDeferred())
                pending = slot
                SoundcoreLog.d("-> ${SoundcorePacket.commandName(command)} (${body.size} bytes)")
                try {
                    transport.write(packet.encode())
                } catch (e: CancellationException) {
                    pending = null
                    throw e
                } catch (e: Exception) {
                    pending = null
                    throw HeadphoneCommandException("Connection to the headphones was lost", e)
                }
                val reply = try {
                    withTimeoutOrNull(timeout) { slot.answer.await() }
                } finally {
                    if (pending === slot) pending = null
                }
                if (reply != null) {
                    if (minWriteGapMs > 0) delay(minWriteGapMs)
                    return@withLock reply
                }
            }
            throw HeadphoneCommandException("The headphones didn't answer (${SoundcorePacket.commandName(command)})")
        }
    }

    override suspend fun awaitClosed(): Throwable? = closed.await()

    override fun close() = finish(null)

    private fun finish(cause: Throwable?) {
        if (!isOpen) return
        isOpen = false
        try { transport.close() } catch (_: Exception) { }
        // Not cancel(): the caller waiting on the answer is still alive and must hear "not connected".
        // A bare CancellationException escapes withTimeoutOrNull, and the controller then takes it for
        // its own cancellation (the connect loop ends silently, stuck in CONNECTING).
        pending?.answer?.completeExceptionally(HeadphoneCommandException(NOT_CONNECTED))
        closed.complete(cause)
        scope.cancel()
    }

    companion object {
        const val NOT_CONNECTED = "Headphones not connected"
        val DEFAULT_TIMEOUTS_MS = listOf(500L, 1_000L, 1_500L)
        const val MIN_WRITE_GAP_MS = 60L
    }
}
