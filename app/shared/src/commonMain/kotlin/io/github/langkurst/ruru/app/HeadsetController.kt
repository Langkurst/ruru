package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.ButtonAction
import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.core.HeadphoneEq
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.NoiseSettings
import io.github.langkurst.ruru.core.Sidetone
import io.github.langkurst.ruru.core.VoicePrompts
import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.RfcommConnectException
import io.github.langkurst.ruru.link.UnresponsiveLinkException
import io.github.langkurst.ruru.protocol.BmapPacket
import io.github.langkurst.ruru.session.QcUltra2Protocol
import io.github.langkurst.ruru.session.SinkSessionLog
import io.github.langkurst.ruru.session.StateCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/** Where the app is with the headset. */
sealed interface Phase {
    data object Starting : Phase
    data object Unsupported : Phase
    /** No paired Bose headset is connected; [pairedName] is one that is paired but off or elsewhere. */
    /** [blocked] is the system's reason for showing Ruru nothing (a missing permission), in words for the user. */
    data class NoHeadset(val pairedName: String?, val blocked: String? = null) : Phase
    data class Connecting(val name: String) : Phase
    data object Connected : Phase
    data class Failed(val name: String, val reason: String) : Phase
}

data class AppState(
    val phase: Phase = Phase.Starting,
    val headset: HeadphoneState = HeadphoneState(),
    /** The last command's problem, in words for the user; null when there is none. */
    val message: String? = null,
    val platformName: String = "",
    val demo: Boolean = false,
)

/**
 * Finds a connected Bose headset, keeps one session open to it, reconnects when it drops, and runs
 * the user's commands one at a time. The Bose logic itself is Earshot's [QcUltra2Protocol]; this
 * class only owns the connection's lifetime and the app state.
 */
class HeadsetController(
    private val platform: HeadsetPlatform,
    private val scope: CoroutineScope,
    val demo: Boolean = false,
) {
    private val headset = MutableStateFlow(HeadphoneState())
    private val phase = MutableStateFlow<Phase>(Phase.Starting)
    private val message = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private val log = SinkSessionLog("Ruru/App")

    @Volatile private var connection: BmapConnection? = null
    @Volatile private var protocol = QcUltra2Protocol()
    private var loopJob: Job? = null

    private val commands = StateCommands<BmapConnection, BmapPacket>(
        state = headset,
        mutex = mutex,
        requireConnection = { connection?.takeIf { it.isOpen } ?: throw HeadphoneCommandException(NOT_CONNECTED) },
        log = log,
        onPush = { packet, current -> protocol.onUnsolicited(packet, current) },
    )

    val state: StateFlow<AppState> = combine(phase, headset, message) { p, h, m ->
        AppState(phase = p, headset = h, message = m, platformName = platform.name, demo = demo)
    }.stateIn(scope, SharingStarted.Eagerly, AppState(platformName = platform.name, demo = demo))

    fun start() {
        if (loopJob == null) loopJob = scope.launch { loop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        connection?.close()
    }

    /** Look again now instead of waiting for the next try. */
    fun retryNow() {
        poke.trySend(Unit)
    }

    fun dismissMessage() {
        message.value = null
    }

    fun setMode(index: Int) = command("listening mode") { c, s -> protocol.setMode(c, s, index) }
    fun setNoise(settings: NoiseSettings) = command("noise") { c, s -> protocol.setNoise(c, s, settings) }
    fun setEq(eq: HeadphoneEq) = command("equaliser") { c, s -> protocol.setEq(c, s, eq) }
    fun setSidetone(level: Sidetone) = command("self voice") { c, s -> protocol.setSidetone(c, s, level, s.sidetonePersistent ?: true) }
    fun setMultipoint(on: Boolean) = command("multipoint") { c, s -> protocol.setMultipoint(c, s, on) }
    fun setAutoPlayPause(on: Boolean) = command("auto play/pause") { c, s -> protocol.setAutoPlayPause(c, s, on) }
    fun setAutoAnswer(on: Boolean) = command("auto-answer") { c, s -> protocol.setAutoAnswer(c, s, on) }
    fun setAutoOff(minutes: Int) = command("auto-off") { c, s -> protocol.setAutoOff(c, s, minutes) }
    fun setShortcut(action: ButtonAction) = command("shortcut button") { c, s -> protocol.setShortcutButton(c, s, action) }
    fun saveMode(index: Int, name: String, settings: NoiseSettings) =
        command("save mode") { c, s -> protocol.saveCustomMode(c, s, index, name, settings) }
    fun removeMode(index: Int) = command("remove mode") { c, s -> protocol.resetCustomMode(c, s, index) }
    fun setVoicePrompts(on: Boolean) = command("voice prompts") { c, s ->
        protocol.setVoicePrompts(c, s, (s.voicePrompts ?: VoicePrompts(enabled = on, languageId = 1)).copy(enabled = on))
    }

    private fun command(name: String, block: suspend (BmapConnection, HeadphoneState) -> HeadphoneState) {
        scope.launch {
            try {
                commands.run(name, block = block)
                message.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                message.value = plainMessage(e.message)
            }
        }
    }

    private suspend fun loop() {
        var failures = 0
        while (scope.isActive) {
            if (!platform.supported) {
                phase.value = Phase.Unsupported
                return
            }
            val seen = try {
                platform.headsets()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("listing headsets failed", e)
                emptyList()
            }
            val target = seen.firstOrNull { it.connected }
            if (target == null) {
                phase.value = Phase.NoHeadset(seen.firstOrNull()?.name, platform.blocked)
                failures = 0
                waitOrRetry(SEARCH_MS)
                continue
            }
            phase.value = Phase.Connecting(target.name)
            val problem = session(target)
            if (problem == null) {
                failures = 0
                waitOrRetry(AFTER_DROP_MS)
            } else {
                failures++
                phase.value = Phase.Failed(target.name, problem)
                waitOrRetry(minOf(MAX_BACKOFF_MS, FIRST_BACKOFF_MS shl (failures - 1).coerceAtMost(4)))
            }
        }
    }

    private suspend fun waitOrRetry(ms: Long) {
        withTimeoutOrNull(ms) { poke.receive() }
    }

    /** One session. Null once it was connected and has ended; otherwise why it never connected. */
    private suspend fun session(target: HeadsetCandidate): String? {
        val transport = try {
            platform.open(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("open failed for ${target.name}", e)
            return openProblem(target.name, e)
        }
        val conn = BmapConnection(transport, scope)
        protocol = QcUltra2Protocol()
        commands.reset()
        connection = conn
        headset.value = HeadphoneState(connection = ConnectionState(status = ConnectionStatus.CONNECTING, deviceName = target.name))
        val pushes = scope.launch { conn.unsolicited.collect { commands.push(it) } }
        var connected = false
        try {
            commands.run("connect", showBusy = false) { c, s -> protocol.refresh(c, protocol.onConnected(c, s)) }
            connected = true
            headset.update { it.copy(connection = ConnectionState(status = ConnectionStatus.CONNECTED, deviceName = target.name)) }
            message.value = null
            phase.value = Phase.Connected
            val poll = scope.launch {
                while (isActive) {
                    delay(if (headset.value.livePushEnabled) LIVE_POLL_MS else POLL_MS)
                    try {
                        commands.run("battery", showBusy = false) { c, s -> protocol.pollBattery(c, s) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HeadphoneCommandException) {
                        // A dead link closes itself; the session then ends below.
                    }
                }
            }
            conn.awaitClosed()
            poll.cancel()
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("session with ${target.name} ended", e)
            return if (connected) null else sessionProblem(target.name, e)
        } finally {
            connection = null
            pushes.cancel()
            conn.close()
            headset.update { it.copy(connection = ConnectionState(status = ConnectionStatus.DISCONNECTED, deviceName = target.name)) }
        }
    }

    private fun openProblem(name: String, e: Exception): String =
        if (e is RfcommConnectException && e.looksBusy) busy(name)
        else "Could not open the control channel to $name. Check that it is connected to this ${platformNoun()}, then try again."

    private fun sessionProblem(name: String, e: Exception): String =
        if (e is UnresponsiveLinkException || e.cause is UnresponsiveLinkException || e is HeadphoneCommandException) busy(name)
        else "$name stopped answering. Ruru will keep trying."

    private fun busy(name: String) =
        if (platformNoun() == "phone") {
            "$name did not answer. If the Bose app is open on this phone, close it. Only one app can control the headphones at a time."
        } else {
            // There is no Bose app for computers, but other tools for these headphones exist.
            "$name did not answer. Close any other app on this computer that controls the headphones, then try again. Only one app can control them at a time."
        }

    private fun platformNoun() = if (platform.name == "Android" || platform.name == "iPhone") "phone" else "computer"

    companion object {
        /**
         * The words shown when a change fails. The shared code's messages are written for the log
         * and the tests ("[31.6]: Invalid length"); people see what happened and what to do.
         */
        fun plainMessage(raw: String?): String {
            val text = raw?.trim().orEmpty()
            return when {
                text.isEmpty() -> NOT_ACCEPTED
                text.contains("no reply", ignoreCase = true) -> "The headphones did not answer. Try again."
                text in DISCONNECTED || text.endsWith("is not connected") -> "The headphones disconnected. Ruru reconnects by itself."
                text.startsWith("Slot ") && " is empty" in text -> "That mode is empty. Add a mode first, then choose it."
                text.startsWith("Only custom slots") -> "Only your own modes can be removed."
                " is a built-in mode and cannot be changed" in text ->
                    "${text.substringBefore(" is a built-in mode")} is built into the headphones and can't be changed. Add a mode of your own instead."
                text.startsWith("[") || text.startsWith("Unexpected reply") || text == "Command failed" -> NOT_ACCEPTED
                else -> text
            }
        }

        private const val NOT_ACCEPTED =
            "The headphones did not accept that change. If it keeps happening, press Copy connection report below and open an issue on GitHub."
        private val DISCONNECTED = setOf(
            "Connection to the headphones was lost", "The headphones stopped answering",
            "Headphones not connected", "The headphones are not connected",
        )

        const val NOT_CONNECTED = "The headphones are not connected"
        private const val SEARCH_MS = 4_000L
        private const val AFTER_DROP_MS = 1_500L
        private const val FIRST_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val POLL_MS = 60_000L
        private const val LIVE_POLL_MS = 300_000L
    }
}
