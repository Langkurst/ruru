package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.app.settings.SettingsStore
import io.github.langkurst.ruru.dsp.ApoConfigWriter
import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Where Equalizer APO keeps its configuration. */
fun interface ApoLocator {
    /** The folder holding APO's `config.txt`, or null when APO is not installed. */
    fun configDir(): File?
}

/**
 * Reads APO's `ConfigPath` from HKLM\SOFTWARE\EqualizerAPO with `reg query` (reading needs no admin
 * rights and no extra library). RURU_APO_CONFIG_DIR names a folder to use instead, for trying Ruru against
 * a scratch folder without APO.
 */
object RegistryApoLocator : ApoLocator {
    override fun configDir(): File? {
        System.getenv("RURU_APO_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        for (view in listOf(null, "/reg:32")) {
            val text = runReg(view) ?: continue
            parseConfigPath(text)?.let { return it }
        }
        return null
    }

    /** The folder named by a `reg query ... /v ConfigPath` answer, or null. */
    fun parseConfigPath(output: String): File? = parseConfigPathText(output)?.let { File(it) }

    internal fun parseConfigPathText(output: String): String? {
        for (line in output.lineSequence()) {
            val at = line.indexOf("REG_SZ")
            if (at < 0 || !line.trimStart().startsWith("ConfigPath")) continue
            return line.substring(at + "REG_SZ".length).trim().trimEnd('\\', '/').takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun runReg(view: String?): String? = runCatching {
        val command = buildList {
            add("reg"); add("query"); add("HKLM\\SOFTWARE\\EqualizerAPO"); add("/v"); add("ConfigPath")
            if (view != null) add(view)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() == 0) text else null
    }.getOrNull()
}

/**
 * Pro EQ on Windows through Equalizer APO (https://equalizerapo.com), which filters the sound of an output
 * device system-wide. Ruru never downloads or installs APO. With the person's consent (asked on the first
 * switch-on) it writes `ruru.txt` in APO's config folder and adds one `Include: ruru.txt` line to `config.txt`;
 * switching off takes that line out and removes `ruru.txt`. APO has no limiter, so the file carries the headroom preamp.
 *
 * Which output APO filters is chosen in APO's own Configurator, not here. Nothing is written until the
 * person agrees, and a folder Ruru cannot write to becomes a plain [EqBackendStatus.NeedsSetup] saying how to fix it.
 */
class ApoEqBackend(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val locator: ApoLocator = RegistryApoLocator,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EqBackend {
    private val current = MutableStateFlow<EqBackendStatus>(EqBackendStatus.Unavailable(Words("eq_apo_checking")))
    override val status: StateFlow<EqBackendStatus> = current

    override val hasLimiter: Boolean = false
    override val setupNote: Words = Words("eq_apo_note")

    private val lock = Mutex()
    @Volatile private var wanted: ProEqState? = null

    override suspend fun apply(state: ProEqState) {
        wanted = state
        withContext(io) { lock.withLock { if (state.enabled) switchOn(state) else switchOff() } }
    }

    override suspend fun off() {
        wanted = null
        withContext(io) { lock.withLock { switchOff() } }
    }

    override fun perform(action: EqSetupAction) {
        when (action.kind) {
            EqSetupKind.ALLOW -> {
                settings.putBoolean(CONSENT_KEY, true)
                scope.launch(io) { wanted?.let { apply(it) } ?: detect() }
            }
            EqSetupKind.CHECK_AGAIN -> refresh()
            EqSetupKind.OPEN_WEBSITE -> Unit
        }
    }

    override fun refresh() {
        scope.launch(io) { detect() }
    }

    /** Look at APO and set the status. Runs `reg`, so not on the screen's thread. */
    suspend fun detect() = withContext(io) {
        lock.withLock {
            current.value = when (val p = probe()) {
                is Probe.Bad -> p.status
                is Probe.Ok -> if (ApoConfigFile.hasInclude(p.config.readBytes())) EqBackendStatus.On else EqBackendStatus.Ready
            }
        }
    }

    private suspend fun switchOn(state: ProEqState) {
        val p = probe()
        if (p !is Probe.Ok) {
            current.value = (p as Probe.Bad).status
            return
        }
        if (!settings.getBoolean(CONSENT_KEY, false)) {
            current.value = EqBackendStatus.NeedsSetup(
                EqSetupAction(EqSetupKind.ALLOW, Words("eq_apo_consent", p.dir.path), Words("eq_apo_allow")),
            )
            return
        }
        current.value = try {
            // A write that has begun is finished even if the page moves on, so the two files are never left half done.
            withContext(NonCancellable) {
                write(File(p.dir, ApoConfigWriter.FILE_NAME), ApoConfigWriter.write(state).toByteArray(Charsets.UTF_8))
                val before = p.config.readBytes()
                val after = ApoConfigFile.withInclude(before)
                if (!after.contentEquals(before)) write(p.config, after)
            }
            EqBackendStatus.On
        } catch (e: IOException) {
            cantWrite(p.dir)
        }
    }

    private suspend fun switchOff() {
        val p = probe()
        if (p !is Probe.Ok) {
            current.value = (p as Probe.Bad).status
            return
        }
        current.value = try {
            withContext(NonCancellable) {
                val before = p.config.readBytes()
                val after = ApoConfigFile.withoutInclude(before)
                if (!after.contentEquals(before)) write(p.config, after)
                Files.deleteIfExists(File(p.dir, ApoConfigWriter.FILE_NAME).toPath())
            }
            EqBackendStatus.Ready
        } catch (e: IOException) {
            cantWrite(p.dir)
        }
    }

    private sealed interface Probe {
        class Ok(val dir: File, val config: File) : Probe
        class Bad(val status: EqBackendStatus) : Probe
    }

    private fun probe(): Probe {
        val dir = locator.configDir()?.takeIf { it.isDirectory } ?: return Probe.Bad(EqStatuses.apoNotInstalled())
        val config = File(dir, "config.txt")
        if (!config.isFile) {
            return Probe.Bad(
                EqBackendStatus.NeedsSetup(EqSetupAction(EqSetupKind.CHECK_AGAIN, Words("eq_apo_no_config", dir.path), Words("eq_check_again"))),
            )
        }
        val bytes = runCatching { config.readBytes() }.getOrNull() ?: return Probe.Bad(cantWrite(dir))
        if (ApoConfigFile.isUtf16(bytes)) return Probe.Bad(EqBackendStatus.Unavailable(Words("eq_apo_unreadable")))
        if (!Files.isWritable(dir.toPath()) || !Files.isWritable(config.toPath())) return Probe.Bad(cantWrite(dir))
        return Probe.Ok(dir, config)
    }

    private fun cantWrite(dir: File): EqBackendStatus = EqBackendStatus.NeedsSetup(
        EqSetupAction(EqSetupKind.CHECK_AGAIN, Words("eq_apo_cant_write", dir.path), Words("eq_check_again")),
    )

    /** Overwrite in place: the file keeps its permissions, and APO reads a whole small file. */
    private fun write(file: File, bytes: ByteArray) {
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    companion object {
        const val CONSENT_KEY = "eq.apo.consent"
    }
}
