package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.dsp.FilterChainWriter
import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pro EQ on Linux through PipeWire's own filter-chain. Ruru writes `~/.config/ruru/eq-filter-chain.conf`
 * ([FilterChainWriter]) and runs `pipewire -c` on it as a child. That adds a sound output called "Ruru EQ"
 * which plays, filtered, into the sink of the connected headphones. Nothing is installed or downloaded and no
 * root rights are needed: the tools used are the ones PipeWire ships (`pipewire`, `pw-dump`) and WirePlumber's `wpctl`.
 *
 * "Use as the output" ([perform]) makes Ruru EQ the default output, after noting the previous default. Switching
 * off, quitting (a shutdown hook) and starting again after a crash put that default back. Moving a slider
 * restarts the child after [debounceMs] without a further change (the filter has to be written again).
 *
 * PipeWire older than 0.3.64, a PulseAudio-only system and missing tools give [EqBackendStatus.Unavailable]
 * with a plain reason. Everything that touches the system goes through [PipeWireSystem].
 */
class LinuxPipeWireEqBackend(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val system: PipeWireSystem = RealPipeWireSystem,
    private val configFile: File = defaultConfigFile(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 300,
    private val sinkWaitMs: Long = 4_000,
    private val installShutdownHook: Boolean = true,
) : EqBackend {
    private val current = MutableStateFlow<EqBackendStatus>(EqBackendStatus.Unavailable(Words("eq_pw_checking")))
    override val status: StateFlow<EqBackendStatus> = current

    override val hasLimiter: Boolean = false
    override val setupNote: Words = Words("eq_pw_note")

    // All state below is changed only while holding [lock]; the shutdown hook takes it too.
    private val lock = ReentrantLock()
    @Volatile private var wanted: ProEqState? = null
    @Volatile private var headphone: String? = null
    private var child: PipeWireChild? = null
    private var childTarget: String? = null
    private var routed = false
    private var hookInstalled = false
    private var pending: Job? = null

    override fun setHeadphone(name: String?) {
        val changed = headphone != name
        headphone = name
        if (changed && wanted?.enabled == true) schedule(wanted!!, restart = true)
    }

    override suspend fun apply(state: ProEqState) {
        wanted = state
        if (!state.enabled) {
            off()
            return
        }
        schedule(state, restart = false)
    }

    override suspend fun off() {
        wanted = null
        pending?.cancel()
        withContext(io + NonCancellable) { lock.withLock { switchOff() } }
    }

    override fun perform(action: EqSetupAction) {
        when (action.kind) {
            EqSetupKind.ALLOW -> scope.launch(io) { lock.withLock { useAsOutput() } }
            EqSetupKind.CHECK_AGAIN -> refresh()
            EqSetupKind.OPEN_WEBSITE -> Unit
        }
    }

    override fun refresh() {
        scope.launch(io) { detect() }
    }

    /** Look at the system and set the status. Runs the PipeWire tools, so not on the screen's thread. */
    suspend fun detect() = withContext(io) {
        lock.withLock { detectLocked() }
    }

    /** Stop the child and give the default output back. Blocking; for the shutdown hook and tests. */
    fun shutdown() {
        pending?.cancel()
        lock.withLock { switchOff(updateStatus = false) }
    }

    // ---------------------------------------------------------------- switching on

    /** Run [state]: at once when nothing runs yet, else after [debounceMs] without a newer one. */
    private fun schedule(state: ProEqState, restart: Boolean) {
        pending?.cancel()
        val running = lock.withLock { child?.alive == true }
        pending = scope.launch(io) {
            if (running || restart) delay(debounceMs)
            withContext(NonCancellable) { lock.withLock { switchOn(state) } }
        }
    }

    private fun switchOn(state: ProEqState) {
        if (wanted != state) return // a newer state is on its way
        problem()?.let { current.value = it; return }
        val sinks = readSinks()
        val target = PipeWireText.findHeadphoneSink(sinks, headphone)
        if (target == null) {
            current.value = checkAgain(Words(if (headphone == null) "eq_pw_no_headphones" else "eq_pw_no_sink", headphone.orEmpty()))
            return
        }
        val text = FilterChainWriter.write(state, target.nodeName)
        if (child?.alive == true && childTarget == target.nodeName && text == lastWritten) {
            current.value = runningStatus()
            return
        }
        try {
            configFile.parentFile?.mkdirs()
            Files.write(configFile.toPath(), text.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            current.value = checkAgain(Words("eq_pw_cant_write", configFile.parent.orEmpty()))
            return
        }
        stopChild()
        val started = system.spawn(listOf("pipewire", "-c", configFile.path))
        if (started == null) {
            current.value = checkAgain(Words("eq_pw_failed"))
            return
        }
        child = started
        childTarget = target.nodeName
        lastWritten = text
        settings.putString(PID_KEY, started.pid.toString())
        installHook()
        if (!awaitSink(started)) {
            stopChild()
            current.value = checkAgain(Words("eq_pw_failed"))
            return
        }
        if (routed) setDefaultTo(FilterChainWriter.SINK_NODE_NAME) // the sink was gone for a moment; take the output again
        current.value = runningStatus()
    }

    private var lastWritten: String? = null

    /** Wait until PipeWire lists the Ruru EQ sink, or the child ends, or [sinkWaitMs] is over. */
    private fun awaitSink(started: PipeWireChild): Boolean {
        val end = System.nanoTime() + sinkWaitMs * 1_000_000
        while (true) {
            if (!started.alive) return false
            if (PipeWireText.sinkByName(readSinks(), FilterChainWriter.SINK_NODE_NAME) != null) return true
            if (System.nanoTime() >= end) return false
            Thread.sleep(POLL_MS)
        }
    }

    private fun runningStatus(): EqBackendStatus = if (routed && defaultName() == FilterChainWriter.SINK_NODE_NAME) {
        EqBackendStatus.On
    } else {
        routed = false
        EqBackendStatus.NeedsSetup(
            EqSetupAction(EqSetupKind.ALLOW, Words("eq_pw_use_output"), Words("eq_pw_use_output_button"), running = true),
        )
    }

    // ---------------------------------------------------------------- the default output

    /** Make Ruru EQ the default output, remembering the one it replaces. */
    private fun useAsOutput() {
        if (child?.alive != true) {
            current.value = checkAgain(Words("eq_pw_failed"))
            return
        }
        val before = defaultName()
        if (before != null && before != FilterChainWriter.SINK_NODE_NAME) settings.putString(PREVIOUS_KEY, before)
        routed = setDefaultTo(FilterChainWriter.SINK_NODE_NAME)
        current.value = runningStatus()
    }

    /** Put the output Ruru replaced back, if Ruru EQ is still the default and the old one is still there. */
    private fun restoreOutput() {
        val previous = settings.getString(PREVIOUS_KEY)?.takeIf { it.isNotEmpty() }
        if (previous != null && defaultName() == FilterChainWriter.SINK_NODE_NAME) {
            val sink = PipeWireText.sinkByName(readSinks(), previous)
            if (sink != null) system.run("wpctl", "set-default", sink.id.toString())
        }
        settings.putString(PREVIOUS_KEY, "")
        routed = false
    }

    private fun setDefaultTo(nodeName: String): Boolean {
        val sink = PipeWireText.sinkByName(readSinks(), nodeName) ?: return false
        return system.run("wpctl", "set-default", sink.id.toString())?.exitCode == 0
    }

    private fun defaultName(): String? =
        system.run("wpctl", "inspect", "@DEFAULT_AUDIO_SINK@")?.takeIf { it.exitCode == 0 }?.let { PipeWireText.parseInspectNodeName(it.text) }

    private fun readSinks(): List<PipeWireSink> =
        system.run("pw-dump")?.takeIf { it.exitCode == 0 }?.let { PipeWireText.parseSinks(it.text) }.orEmpty()

    // ---------------------------------------------------------------- switching off, checking

    private fun switchOff(updateStatus: Boolean = true) {
        restoreOutput()
        stopChild()
        settings.putString(PID_KEY, "")
        if (updateStatus) detectLocked()
    }

    private fun stopChild() {
        child?.stop()
        child = null
        childTarget = null
        lastWritten = null
    }

    private fun detectLocked() {
        // An earlier Ruru that crashed may have left its child and its output change behind.
        if (child == null) {
            settings.getString(PID_KEY)?.toLongOrNull()?.let { system.stopLeftover(it) }
            settings.putString(PID_KEY, "")
            if (settings.getString(PREVIOUS_KEY).orEmpty().isNotEmpty() && problem() == null) {
                if (PipeWireText.sinkByName(readSinks(), FilterChainWriter.SINK_NODE_NAME) == null) {
                    // Ruru EQ is gone; if it was still the default PipeWire has moved on already.
                    settings.putString(PREVIOUS_KEY, "")
                } else {
                    restoreOutput()
                }
            }
        }
        val trouble = problem()
        val on = wanted
        current.value = when {
            trouble != null -> trouble
            child?.alive == true -> runningStatus()
            on != null && on.enabled -> {
                schedule(on, restart = false) // it should be running: try again, and keep what the status says meanwhile
                current.value
            }
            else -> EqBackendStatus.Ready
        }
    }

    /** What stops the equaliser from working here, or null when PipeWire, `pw-dump` and `wpctl` are all there and PipeWire runs. */
    private fun problem(): EqBackendStatus? {
        val version = system.run("pipewire", "--version")?.let { PipeWireText.parseVersion(it.text) }
            ?: return unavailable("eq_pw_missing")
        if (version < PipeWireText.MINIMUM) return unavailable("eq_pw_old", version.toString(), PipeWireText.MINIMUM.toString())
        val wpctl = system.run("wpctl", "status") ?: return unavailable("eq_pw_no_tools")
        if (wpctl.exitCode != 0) return unavailable("eq_pw_not_running")
        if (system.run("pw-dump", "--help") == null) return unavailable("eq_pw_no_tools")
        return null
    }

    private fun unavailable(key: String, vararg args: Any) = EqBackendStatus.Unavailable(Words(key, *args))

    private fun checkAgain(message: Words) =
        EqBackendStatus.NeedsSetup(EqSetupAction(EqSetupKind.CHECK_AGAIN, message, Words("eq_check_again")))

    private fun installHook() {
        if (!installShutdownHook || hookInstalled) return
        hookInstalled = true
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { shutdown() } }, "ruru-eq-shutdown"))
    }

    companion object {
        const val CONFIG_NAME = "eq-filter-chain.conf"
        const val PID_KEY = "eq.pw.pid"
        const val PREVIOUS_KEY = "eq.pw.previousSink"
        private const val POLL_MS = 150L

        /** `~/.config/ruru/eq-filter-chain.conf` (under XDG_CONFIG_HOME when that is set). */
        fun defaultConfigFile(): File {
            val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".config")
            return File(File(base, "ruru"), CONFIG_NAME)
        }
    }
}
