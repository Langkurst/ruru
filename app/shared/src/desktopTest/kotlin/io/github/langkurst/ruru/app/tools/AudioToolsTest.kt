package io.github.langkurst.ruru.app.tools

import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Find and mic check, against fake sound devices (nothing here plays or records anything). */
class AudioToolsTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @AfterTest fun tearDown() = scope.cancel()

    /** Records what is written and how many sinks were opened and closed. */
    private class FakeOutput(private val blockAfter: Int = Int.MAX_VALUE, private val openFails: AudioProblem? = null) : PcmOutput {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val interrupted = CountDownLatch(1)
        val writes = ArrayList<ShortArray>()
        var rate = 0
        var channels = 0

        override fun open(sampleRate: Int, channels: Int): PcmSink {
            openFails?.let { throw it }
            opened.incrementAndGet()
            rate = sampleRate
            this.channels = channels
            return object : PcmSink {
                override fun write(pcm: ShortArray, count: Int) {
                    val n = synchronized(writes) { writes += pcm.copyOf(count); writes.size }
                    // A real sink blocks while its buffer is full; this one blocks from write number blockAfter until interrupted.
                    if (n >= blockAfter) interrupted.await(10, TimeUnit.SECONDS)
                }
                override fun drain() = Unit
                override fun interrupt() = interrupted.countDown()
                override fun close() { closed.incrementAndGet() }
            }
        }
    }

    private fun find(output: PcmOutput) = FindController(scope, Dispatchers.IO, output)

    private suspend fun FindController.await(want: (FindState) -> Boolean) = withTimeout(5_000) { state.first(want) }

    @Test
    fun `find rings for thirty seconds of tone, louder as it goes, then stops by itself`() = runBlocking {
        val out = FakeOutput()
        val find = find(out)
        find.start()
        find.await { it == FindState.Ringing || it == FindState.Idle }
        find.await { it == FindState.Idle }
        assertEquals(1, out.opened.get())
        assertEquals(1, out.closed.get())
        assertEquals(FindTone.SAMPLE_RATE, out.rate)
        assertEquals(2, out.channels)
        // 30 s of 860 ms periods, rounded up to a whole period.
        assertEquals((FindTone.MAX_RING_MS + FindTone.PERIOD_MS - 1) / FindTone.PERIOD_MS, out.writes.size.toLong())
        fun loudest(i: Int) = out.writes[i].maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(loudest(0) < loudest(out.writes.size - 1), "starts quieter than it ends")
        assertTrue(loudest(1) >= loudest(0))
    }

    @Test
    fun `stop ends a ring that is blocked in a write, and closes the sound device`() = runBlocking {
        val out = FakeOutput(blockAfter = 2)
        val find = find(out)
        find.start()
        withTimeout(5_000) { while (out.writes.size < 2) kotlinx.coroutines.delay(10) }
        assertEquals(FindState.Ringing, find.state.value)
        find.stop()
        assertEquals(FindState.Idle, find.state.value)
        withTimeout(5_000) { while (out.closed.get() < 1) kotlinx.coroutines.delay(10) }
        assertEquals(1, out.closed.get())
        // and it can ring again afterwards
        val before = out.opened.get()
        find.start()
        withTimeout(5_000) { while (out.opened.get() == before) kotlinx.coroutines.delay(10) }
        find.stop()
    }

    @Test
    fun `starting twice rings once`() = runBlocking {
        val out = FakeOutput(blockAfter = 1)
        val find = find(out)
        find.start(); find.start(); find.start()
        withTimeout(5_000) { while (out.writes.isEmpty()) kotlinx.coroutines.delay(10) }
        find.stop()
        assertEquals(1, out.opened.get())
    }

    @Test
    fun `a sound system that cannot play says so in words, and nothing stays open`() = runBlocking {
        val find = find(FakeOutput(openFails = AudioProblem(Words("tool_no_output"))))
        find.start()
        val s = find.await { it is FindState.Problem }
        assertEquals("tool_no_output", (s as FindState.Problem).words.key)
    }

    // ---- mic check

    private class FakeInput(private val openFails: AudioProblem? = null, private val stopAfter: Int = Int.MAX_VALUE) : PcmInput {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        var rate = 0
        override fun open(sampleRate: Int): PcmSource {
            openFails?.let { throw it }
            opened.incrementAndGet()
            rate = sampleRate
            var reads = 0
            return object : PcmSource {
                override fun read(buffer: ShortArray): Int {
                    if (++reads > stopAfter) return -1
                    buffer.fill(1_000)
                    return buffer.size
                }
                override fun interrupt() = Unit
                override fun close() { closed.incrementAndGet() }
            }
        }
    }

    private fun mic(input: PcmInput, output: PcmOutput, has: () -> Boolean = { true }, ask: () -> Unit = {}) =
        MicCheckController(scope, Dispatchers.IO, input, output, has, ask)

    private suspend fun MicCheckController.await(want: (MicCheckState) -> Boolean) = withTimeout(5_000) { state.first(want) }

    @Test
    fun `mic check records five seconds, plays exactly that back, then finishes`() = runBlocking {
        val input = FakeInput()
        val out = FakeOutput()
        val mic = mic(input, out)
        mic.start()
        mic.await { it == MicCheckState.Finished }
        assertEquals(MicCheckController.SAMPLE_RATE, input.rate)
        assertEquals(MicCheckController.SAMPLE_RATE, out.rate)
        assertEquals(1, out.channels)
        assertEquals(MicCheckController.SECONDS * MicCheckController.SAMPLE_RATE, out.writes.sumOf { it.size })
        assertTrue(out.writes.all { chunk -> chunk.all { it == 1_000.toShort() } }, "plays back what was recorded")
        assertEquals(1, input.closed.get())
        assertEquals(1, out.closed.get())
    }

    @Test
    fun `mic check counts the seconds down while it records`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        val mic = mic(FakeInput(), FakeOutput())
        // Unconfined runs the collector on the thread that sets each state, so no step is skipped.
        val watcher = launch(Dispatchers.Unconfined) {
            mic.state.collect { s -> if (s is MicCheckState.Recording) seen += s.secondsLeft }
        }
        mic.start()
        mic.await { it == MicCheckState.Finished }
        watcher.cancel()
        assertEquals(listOf(5, 4, 3, 2, 1, 0), seen.toList().distinct(), "counts down one second at a time: $seen")
    }

    @Test
    fun `stop during playback ends it and nothing is kept`() = runBlocking {
        val out = FakeOutput(blockAfter = 1)
        val mic = mic(FakeInput(), out)
        mic.start()
        mic.await { it == MicCheckState.Playing }
        withTimeout(5_000) { while (out.writes.isEmpty()) kotlinx.coroutines.delay(10) }
        mic.stop()
        assertEquals(MicCheckState.Idle, mic.state.value)
        withTimeout(5_000) { while (out.closed.get() < 1) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `no microphone is explained, and the speaker is never opened`() = runBlocking {
        val out = FakeOutput()
        val mic = mic(FakeInput(openFails = AudioProblem(Words("tool_no_input"))), out)
        mic.start()
        val s = mic.await { it is MicCheckState.Problem }
        assertEquals("tool_no_input", (s as MicCheckState.Problem).words.key)
        assertEquals(0, out.opened.get())
    }

    @Test
    fun `a microphone that stops early is a problem, not a silent playback`() = runBlocking {
        val out = FakeOutput()
        val mic = mic(FakeInput(stopAfter = 3), out)
        mic.start()
        val s = mic.await { it is MicCheckState.Problem }
        assertEquals("tool_mic_stopped", (s as MicCheckState.Problem).words.key)
        assertEquals(0, out.opened.get())
    }

    @Test
    fun `without permission it asks once and records only after the person says yes`() = runBlocking {
        val asked = AtomicInteger()
        var granted = false
        val input = FakeInput()
        val mic = mic(input, FakeOutput(), has = { granted }, ask = { asked.incrementAndGet() })
        mic.start()
        assertEquals(MicCheckState.NeedsPermission, mic.state.value)
        assertEquals(1, asked.get())
        assertEquals(0, input.opened.get(), "the microphone is not opened before permission")
        granted = true
        mic.permissionAnswered(true)
        mic.await { it == MicCheckState.Finished }
        assertEquals(1, input.opened.get())
    }

    @Test
    fun `a no to the permission is explained and nothing is recorded`() = runBlocking {
        val input = FakeInput()
        val mic = mic(input, FakeOutput(), has = { false })
        mic.start()
        mic.permissionAnswered(false)
        val s = assertIs<MicCheckState.Problem>(mic.state.value)
        assertEquals("tool_mic_denied", s.words.key)
        assertEquals(0, input.opened.get())
        assertFalse(mic.state.value is MicCheckState.Recording)
    }
}
