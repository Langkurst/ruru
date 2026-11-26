package io.github.langkurst.ruru.app.tools

import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * A sound tool could not start, with the sentence to show: the key is a string in strings_tools.xml
 * (so it is in every language), and the log gets [cause].
 */
class AudioProblem(val words: Words, cause: Throwable? = null) : Exception(words.key, cause)

/** Somewhere to play PCM (16-bit samples as a [ShortArray], channels interleaved). Blocks while its buffer is full. */
interface PcmSink {
    fun write(pcm: ShortArray, count: Int = pcm.size)

    /** Waits until everything written has been played. */
    fun drain()

    /** Makes a blocked [write] or [drain] return now (called from another thread). */
    fun interrupt()
    fun close()
}

interface PcmOutput {
    /** Throws [AudioProblem] when this system has no way to play the sound. */
    fun open(sampleRate: Int, channels: Int): PcmSink
}

/** A microphone, mono 16-bit. */
interface PcmSource {
    /** Fills [buffer] as far as the microphone has samples, blocking; returns how many, or -1 when it has stopped. */
    fun read(buffer: ShortArray): Int
    fun interrupt()
    fun close()
}

interface PcmInput {
    /** Throws [AudioProblem] when there is no microphone or it is in use. */
    fun open(sampleRate: Int): PcmSource
}

// ---------------------------------------------------------------- Find my headphones

sealed interface FindState {
    data object Idle : FindState
    data object Ringing : FindState
    data class Problem(val words: Words) : FindState
}

/**
 * Plays [FindTone] through [output] until [stop] or [FindTone.MAX_RING_MS]. The volume ramps up from
 * a low start. Only one ring at a time. The loop writes whole periods, so the time is counted from
 * what was written (a blocked write is what keeps it in step with the clock), which makes it testable.
 */
class FindController(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val output: PcmOutput,
) {
    private val _state = MutableStateFlow<FindState>(FindState.Idle)
    val state: StateFlow<FindState> = _state

    private var job: Job? = null
    @Volatile private var sink: PcmSink? = null
    @Volatile private var generation = 0

    fun start() {
        if (_state.value == FindState.Ringing) return
        val mine = ++generation
        _state.value = FindState.Ringing
        job = scope.launch(dispatcher) {
            var open: PcmSink? = null
            // The state is published only after the sound device is closed: whoever sees Idle (or starts again) finds it free.
            var result: FindState = FindState.Idle
            try {
                open = output.open(FindTone.SAMPLE_RATE, FindTone.CHANNELS)
                sink = open
                var elapsed = 0L
                while (isActive && elapsed < FindTone.MAX_RING_MS) {
                    open.write(FindTone.period(FindTone.gainAt(elapsed)))
                    elapsed += FindTone.PERIOD_MS
                }
                if (isActive) open.drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioProblem) {
                result = FindState.Problem(e.words)
            } catch (e: Exception) {
                // A write that fails because stop() closed the line is not a problem to show.
                result = if (isActive) FindState.Problem(Words("tool_audio_failed")) else FindState.Idle
            } finally {
                sink = null
                runCatching { open?.close() }
            }
            finish(mine, result)
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        sink?.let { runCatching { it.interrupt() } }
        _state.value = FindState.Idle
    }

    private fun finish(mine: Int, next: FindState) {
        if (mine == generation) _state.value = next
    }
}

// ---------------------------------------------------------------- Mic check

sealed interface MicCheckState {
    data object Idle : MicCheckState

    /** The system has been asked for the microphone permission (Android) and has not answered yet. */
    data object NeedsPermission : MicCheckState
    data class Recording(val secondsLeft: Int) : MicCheckState
    data object Playing : MicCheckState
    data object Finished : MicCheckState
    data class Problem(val words: Words) : MicCheckState
}

/**
 * Records [SECONDS] seconds from the microphone, then plays it back, so a person hears what the
 * other end of a call hears. Nothing is kept: the recording lives in memory and is dropped when the
 * check ends. [hasPermission] and [askPermission] are for a system that asks (Android).
 */
class MicCheckController(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val input: PcmInput,
    private val output: PcmOutput,
    private val hasPermission: () -> Boolean = { true },
    private val askPermission: () -> Unit = {},
) {
    private val _state = MutableStateFlow<MicCheckState>(MicCheckState.Idle)
    val state: StateFlow<MicCheckState> = _state

    private var job: Job? = null
    @Volatile private var source: PcmSource? = null
    @Volatile private var sink: PcmSink? = null
    @Volatile private var generation = 0

    private val busy get() = _state.value is MicCheckState.Recording || _state.value == MicCheckState.Playing

    fun start() {
        if (busy) return
        if (!hasPermission()) {
            _state.value = MicCheckState.NeedsPermission
            askPermission()
            return
        }
        val mine = ++generation
        _state.value = MicCheckState.Recording(SECONDS)
        job = scope.launch(dispatcher) {
            var mic: PcmSource? = null
            var speaker: PcmSink? = null
            try {
                mic = input.open(SAMPLE_RATE)
                source = mic
                val recording = ShortArray(SECONDS * SAMPLE_RATE)
                var have = 0
                val chunk = ShortArray(SAMPLE_RATE / 10)
                var shown = SECONDS
                while (isActive && have < recording.size) {
                    val got = mic.read(chunk)
                    if (got < 0) throw AudioProblem(Words("tool_mic_stopped"))
                    val take = minOf(got, recording.size - have)
                    chunk.copyInto(recording, have, 0, take)
                    have += take
                    val left = ((recording.size - have) + SAMPLE_RATE - 1) / SAMPLE_RATE
                    if (left != shown) {
                        shown = left
                        publish(mine, MicCheckState.Recording(left))
                    }
                }
                source = null
                mic.close()
                mic = null
                if (!isActive) return@launch
                publish(mine, MicCheckState.Playing)
                speaker = output.open(SAMPLE_RATE, 1)
                sink = speaker
                speaker.write(recording, have)
                if (isActive) speaker.drain()
                publish(mine, MicCheckState.Finished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioProblem) {
                publish(mine, MicCheckState.Problem(e.words))
            } catch (e: Exception) {
                publish(mine, if (isActive) MicCheckState.Problem(Words("tool_audio_failed")) else MicCheckState.Idle)
            } finally {
                source = null
                sink = null
                runCatching { mic?.close() }
                runCatching { speaker?.close() }
            }
        }
    }

    /** The person's answer to the permission question. */
    fun permissionAnswered(granted: Boolean) {
        if (_state.value != MicCheckState.NeedsPermission) return
        if (granted) {
            _state.value = MicCheckState.Idle
            start()
        } else {
            _state.value = MicCheckState.Problem(Words("tool_mic_denied"))
        }
    }

    /** Ends a recording or playback and forgets it. */
    fun stop() {
        generation++
        job?.cancel()
        source?.let { runCatching { it.interrupt() } }
        sink?.let { runCatching { it.interrupt() } }
        _state.value = MicCheckState.Idle
    }

    private fun publish(mine: Int, next: MicCheckState) {
        if (mine == generation) _state.value = next
    }

    companion object {
        const val SECONDS = 5

        /** 16 kHz holds everything a call carries (a call is 8 or 16 kHz) and keeps the recording small. */
        const val SAMPLE_RATE = 16_000
    }
}

// ---------------------------------------------------------------- what a system has

/**
 * The sound tools this system has: null for one it can't do. [find] and [mic] are both null on the
 * iPhone for now.
 */
class AudioTools(val find: FindController?, val mic: MicCheckController?)

/** The tools for this system, made once. */
expect fun audioTools(): AudioTools
