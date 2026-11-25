package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.currentTimeMillis
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ManagedLink
import io.github.langkurst.ruru.link.UnresponsiveLinkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor

/**
 * One H-AA session with a JBL headset over its Harman GATT service (or the virtual one in demo
 * mode); Earshot's JblConnection, MIT. H-AA has no sequence numbers and no request ids: a reply is
 * just the next frame of the right kind, and a setting is often answered with nothing at all. So
 * every frame the headset sends goes to [attach]'s callback in arrival order (the session folds
 * replies and pushes the same way), and [request] only waits for the first frame its predicate
 * accepts.
 *
 * Nothing is read from the transport until [attach] (or the first [request]), so the callback
 * sees every frame, the device info the headset sends by itself included; the transports buffer
 * what arrives before that.
 *
 * Writes are paced [paceMs] apart (jbl-control: "frames written back to back get dropped by the
 * firmware") and each one passes [JblWriteGuard] first; a refused frame never reaches the
 * transport. The headset's heartbeats (`B1`) are echoed as the app does, once its device info has
 * arrived. Three requests in a row without a single frame back means the link is open but nobody
 * answers (JBL's app holds the control link: one client at a time): the connection closes with
 * [UnresponsiveLinkException], which the controller reports as the busy channel.
 */
class JblConnection(
    private val transport: ByteTransport,
    parentScope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
    private val paceMs: Long = PACE_MS,
) : ManagedLink {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]) +
            (parentScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default),
    )
    private val frames = JblFrameBuffer(onJunk = { n -> JblLog.w("dropped $n bytes outside a frame") })
    private val closed = CompletableDeferred<Throwable?>()
    private val writeLock = Mutex()
    private val requestLock = Mutex()
    private val waiterLock = Mutex()
    private var lastWriteAt: Long? = null

    @Volatile
    private var onFrame: ((JblFrame) -> Unit)? = null

    /** Immutable, replaced under [waiterLock]; read without it. */
    @Volatile
    private var waiters: List<Waiter> = emptyList()

    private class Waiter(val accepts: (JblFrame) -> Boolean) {
        val frame = CompletableDeferred<JblFrame>()
    }

    @Volatile
    override var isOpen: Boolean = true
        private set

    /** The latest `AA 12` device info; the headset sends one by itself right after the link opens. */
    @Volatile
    var deviceInfo: JblDeviceInfo? = null
        private set

    /** Frames received so far; [request] counts a timeout as silent only when none came in meanwhile. */
    @Volatile
    var framesReceived: Long = 0
        private set

    @Volatile
    private var silentRequests = 0

    private val reader: Job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            transport.incoming.collect { chunk -> frames.feed(chunk).forEach { dispatch(it) } }
            frames.flush().forEach { dispatch(it) }
            finish(null)
        } catch (e: Throwable) {
            finish(e)
            if (e is CancellationException) throw e
        }
    }

    /** Hand every frame to [callback], in arrival order, from now on (starts reading the link). */
    fun attach(callback: (JblFrame) -> Unit) {
        onFrame = callback
        reader.start()
    }

    private suspend fun dispatch(frame: JblFrame) {
        framesReceived++
        silentRequests = 0
        JblLog.d("<- ${frame.forLog()}")
        // A cut device info (small MTU) never replaces a whole one.
        if (frame.command == JblCommands.DEVICE_INFO) {
            JblDeviceInfo.parse(frame)?.let { if (!it.isPartial || deviceInfo == null) deviceInfo = it }
        }
        onFrame?.invoke(frame)
        if (frame.command == JblCommands.HEARTBEAT && deviceInfo != null) {
            scope.launch { try { send(JblCommands.heartbeatEcho()) } catch (_: HeadphoneCommandException) { } }
        }
        for (w in waiters) if (w.accepts(frame)) w.frame.complete(frame)
    }

    private suspend fun addWaiter(w: Waiter) = waiterLock.withLock { waiters = waiters + w }

    private suspend fun removeWaiter(w: Waiter) = waiterLock.withLock { waiters = waiters - w }

    /**
     * Send [frame] (paced, guarded). Throws [HeadphoneCommandException] when the guard refuses it or
     * the link is closed or lost.
     */
    suspend fun send(frame: JblFrame) {
        if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
        if (!JblWriteGuard.isAllowed(frame)) {
            JblLog.w("refused -> ${frame.forLog()}")
            throw HeadphoneCommandException("Ruru doesn't send that command to JBL headphones (${JblCommands.name(frame.command)})")
        }
        writeLock.withLock {
            lastWriteAt?.let { last -> (last + paceMs - clock()).takeIf { it > 0 }?.let { delay(it) } }
            if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
            JblLog.d("-> ${frame.forLog()}")
            try {
                transport.write(frame.encode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(e)
                throw HeadphoneCommandException("Connection to the headphones was lost", e)
            }
            lastWriteAt = clock()
        }
    }

    /**
     * Send [frame] and return the first frame [answers] accepts, or null when none came within
     * [timeoutMs] after any of [attempts] sends (many H-AA reads simply go unanswered on models
     * without the feature). One request at a time. Throws like [send].
     *
     * [countsSilence] false for reads a model may lack (the level, EQ, prompts, the battery poll,
     * the Lab): their silence says nothing about the link, so it never counts toward closing it.
     */
    suspend fun request(
        frame: JblFrame,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
        attempts: Int = 1,
        countsSilence: Boolean = true,
        answers: (JblFrame) -> Boolean,
    ): JblFrame? = requestLock.withLock {
        reader.start()
        repeat(attempts) {
            val waiter = Waiter(answers)
            val before = framesReceived
            addWaiter(waiter)
            try {
                send(frame)
                val reply = withTimeoutOrNull(timeoutMs) { waiter.frame.await() }
                if (reply != null) return@withLock reply
            } finally {
                removeWaiter(waiter)
            }
            if (countsSilence && framesReceived == before && ++silentRequests >= SILENT_REQUESTS_BEFORE_DEAD && !confirmAlive()) {
                JblLog.w("$silentRequests requests without a frame back, device info silent too; closing the link")
                finish(UnresponsiveLinkException("the JBL control service is open but nothing answers"))
                throw HeadphoneCommandException("The headphones didn't answer (${JblCommands.name(frame.command)})")
            }
        }
        null
    }

    /**
     * Before calling the link dead: one device-info read (`AA 11 00`), which every H-AA headset
     * answers. Any frame back means the silent reads were features this model lacks, so the counter
     * starts again.
     */
    private suspend fun confirmAlive(): Boolean {
        val before = framesReceived
        val waiter = Waiter { true }
        addWaiter(waiter)
        try {
            send(JblCommands.deviceInfoGet())
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { waiter.frame.await() }
        } finally {
            removeWaiter(waiter)
        }
        val alive = framesReceived != before
        if (alive) {
            JblLog.d("silent reads, but the device info answered: the link is alive")
            silentRequests = 0
        }
        return alive
    }

    /** The device info, waiting up to [timeoutMs] for the one the headset sends by itself. */
    suspend fun awaitDeviceInfo(timeoutMs: Long): JblDeviceInfo? {
        reader.start()
        deviceInfo?.let { return it }
        if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
        val waiter = Waiter { it.command == JblCommands.DEVICE_INFO }
        addWaiter(waiter)
        try {
            deviceInfo?.let { return it }
            if (!isOpen) throw HeadphoneCommandException(NOT_CONNECTED)
            withTimeoutOrNull(timeoutMs) { waiter.frame.await() }
            return deviceInfo
        } finally {
            removeWaiter(waiter)
        }
    }

    override suspend fun awaitClosed(): Throwable? {
        reader.start() // an unread link would never be seen to end
        return closed.await()
    }

    override fun close() = finish(null)

    private fun finish(cause: Throwable?) {
        if (!isOpen) return
        isOpen = false
        try { transport.close() } catch (_: Exception) { }
        // Not cancel(): a waiting caller is still alive and must hear "not connected", not a bare
        // CancellationException (the controller would treat that as its own cancellation and end
        // the connect attempt without a message or a retry).
        waiters.forEach { it.frame.completeExceptionally(HeadphoneCommandException(NOT_CONNECTED)) }
        closed.complete(cause)
        scope.cancel()
    }

    companion object {
        const val NOT_CONNECTED = "Headphones not connected"

        /** jbl-control waits 1.6 s for a probe answer; so does Ruru. */
        const val REPLY_TIMEOUT_MS = 1_600L

        /** jbl-control's pause between writes. */
        const val PACE_MS = 450L

        const val SILENT_REQUESTS_BEFORE_DEAD = 3

        /** How long the device-info confirmation waits before the link is called dead. */
        const val CONFIRM_TIMEOUT_MS = 2_000L
    }
}

/**
 * A frame as [JblLog] prints it: command and length, never the payload. Built from the fields
 * instead of [JblFrame.toString], so a later change there can't put payload bytes into the
 * connection report: the device info (`AA 12`) and the TLV (`AA 06`) carry the headset's name and
 * address, and the report is what users paste into public bug reports.
 */
private fun JblFrame.forLog(): String = "AA ${JblBytes.hex(command).lowercase()} ($size B)${if (truncated) ", truncated" else ""}"
