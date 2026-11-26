package io.github.langkurst.ruru.app.tools

import io.github.langkurst.ruru.app.HeadsetController
import io.github.langkurst.ruru.core.HeadphoneCommandException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.HeadsetSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the Lab stands. */
sealed interface LabState {
    data object Idle : LabState
    data object Scanning : LabState

    /** The scan finished: [lines] one per read, as the session listed them. */
    data class Done(val lines: List<String>) : LabState
    data class Problem(val words: Words) : LabState
}

/** What the Lab runs on: the open headset's [HeadsetSession.scanLab], which only reads. */
interface LabSource {
    suspend fun scan(): List<String>
}

/** One scan at a time; the Lab never changes anything on the headphones (each session sends GETs only, through its write guard). */
class LabController(private val scope: CoroutineScope, private val source: LabSource) {
    constructor(scope: CoroutineScope, controller: HeadsetController) : this(scope, object : LabSource {
        override suspend fun scan(): List<String> = controller.scanLab()
    })

    private val _state = MutableStateFlow<LabState>(LabState.Idle)
    val state: StateFlow<LabState> = _state
    private var job: Job? = null

    fun start() {
        if (_state.value == LabState.Scanning) return
        _state.value = LabState.Scanning
        job = scope.launch {
            _state.value = try {
                LabState.Done(source.scan().map(LabReport::clean))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HeadphoneCommandException) {
                LabState.Problem(if (e.message == HeadsetSession.LAB_UNAVAILABLE) Words("tools_lab_unavailable") else HeadsetController.plainMessage(e.message))
            } catch (e: Exception) {
                LabState.Problem(Words("tools_lab_failed"))
            }
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = LabState.Idle
    }
}

/** The text of a Lab report, to paste into an issue. */
object LabReport {
    private val ADDRESS = Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b")

    /** One line with any Bluetooth address masked (the sessions keep addresses out already; this is the second net). */
    fun clean(line: String): String = line.replace(ADDRESS, "xx:xx:xx:xx:xx:xx")

    fun build(appVersion: String, system: String, model: String?, lines: List<String>): String = buildString {
        appendLine("Ruru $appVersion on $system, Lab (read only)")
        appendLine("Model: ${model ?: "unknown"}")
        appendLine("Reads: ${lines.size}")
        appendLine()
        lines.forEach { appendLine(clean(it)) }
    }
}
