package io.github.langkurst.ruru.android.sleep

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.langkurst.ruru.sleep.sound.LoopPcm
import io.github.langkurst.ruru.sleep.sound.RecordingLibrary
import io.github.langkurst.ruru.sleep.sound.SleepSounds
import io.github.langkurst.ruru.ui.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Cuts the loop out of a decoded file. Each bundled .m4a holds one second of the loop's own end, then the loop,
 * then one second of its own start (tools/sleep-sounds/make_loops.py), because AAC adds a little start-up and
 * tail and Android may or may not remove them. The file is therefore periodic inside, and any [frames] frames
 * taken from well inside it are a seamless loop, whatever the decoder trimmed. Pure, so it is tested on the JVM.
 */
object LoopCut {
    /** The padding the script puts on each side, in frames at 48 kHz. */
    const val PAD = 48_000

    /** Frames to keep clear of the file's start (the start-up) and end (the tail). */
    private const val SAFE = 4_096

    /** [decoded] is interleaved stereo; returns [frames] frames from inside it, or null when the file is too short to hold the loop. */
    fun cut(decoded: ShortArray, frames: Int): ShortArray? {
        val total = decoded.size / 2
        if (frames <= 0 || total < frames + 2 * SAFE) return null
        // Half a second in: far from the start-up, far from the tail, whichever way the decoder trimmed.
        val start = (PAD / 2).coerceAtMost(total - frames - SAFE).coerceAtLeast(SAFE)
        return decoded.copyOfRange(start * 2, (start + frames) * 2)
    }
}

/** Turns a bundled .m4a into 16-bit stereo samples with Android's own decoder. */
internal object RecordingDecoder {
    class Decoded(val samples: ShortArray, val sampleRate: Int)

    /** Decodes [bytes] (an AAC file); null when the phone can't decode it. Slow (hundreds of milliseconds): never call it on the audio thread. */
    fun decode(bytes: ByteArray): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(size, bytes.size - position.toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = bytes.size.toLong()
                override fun close() = Unit
            })
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }

            var out = ShortArray(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) * 2 * 70)
            var written = 0
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buffer = codec.getOutputBuffer(o)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = shorts.remaining()
                        // Mono becomes stereo; more than two channels keep their first two.
                        val frames = count / channels
                        if ((written + frames * 2) > out.size) out = out.copyOf(maxOf(out.size * 2, written + frames * 2))
                        for (f in 0 until frames) {
                            val left = shorts.get(f * channels)
                            val right = if (channels > 1) shorts.get(f * channels + 1) else left
                            out[written++] = left
                            out[written++] = right
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            return Decoded(out.copyOf(written), rate)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}

/**
 * The bundled recordings (rain, ocean, stream, fire and the birdsong alarm) as the sleep engine's
 * [RecordingLibrary]. They are decoded when a night or an alarm needs them ([prepare]) and let go
 * afterwards ([release]); a sound that is not decoded plays its synthesised version, so a phone that can't
 * decode them still sleeps to something.
 */
class AndroidRecordings {
    private val loops = ConcurrentHashMap<String, LoopPcm>()

    /** The file names (without .m4a) and the loop length of each, from `files/sleep/loops.txt`. */
    @Volatile private var lengths: Map<String, Int>? = null

    private val library = object : RecordingLibrary {
        override val ids: Set<String> get() = BUNDLED
        override fun loop(id: String): LoopPcm? = loops[id]
    }

    /** Installs this library as the one the sound engine uses. Call once at start-up. */
    fun install() {
        SleepSounds.recordings = library
    }

    /** True once [id] is decoded and ready to play. */
    fun isReady(id: String): Boolean = loops.containsKey(id)

    /** Decodes what [ids] need (the ids a mix or an alarm will ask for); ones already decoded or unknown are skipped. */
    suspend fun prepare(ids: Collection<String>) {
        val wanted = ids.filter { it in BUNDLED && it !in loops }.distinct()
        if (wanted.isEmpty()) return
        withContext(Dispatchers.Default) {
            val sizes = lengths ?: readLengths().also { lengths = it }
            for (id in wanted) {
                val frames = sizes[id] ?: continue
                val bytes = runCatching { Res.readBytes("files/sleep/$id.m4a") }.getOrNull() ?: continue
                val decoded = RecordingDecoder.decode(bytes) ?: continue
                if (decoded.sampleRate != SleepSounds.SAMPLE_RATE) continue
                val cut = LoopCut.cut(decoded.samples, frames) ?: continue
                loops[id] = LoopPcm(cut, decoded.sampleRate)
            }
        }
    }

    /** Lets the decoded loops go (about 11 MB each); they are decoded again when needed. */
    fun release() {
        loops.clear()
    }

    private suspend fun readLengths(): Map<String, Int> =
        runCatching { Res.readBytes("files/sleep/loops.txt").decodeToString() }.getOrDefault("").lineSequence()
            .mapNotNull { line -> line.trim().split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } }
            .mapNotNull { (id, n) -> n?.let { id to it } }
            .toMap()

    companion object {
        /** The sound ids with a file: the four recorded sounds and the birdsong alarm ([RecordingLibrary.ALARM_PREFIX] + "birds"). */
        val BUNDLED: Set<String> = setOf("rain", "ocean", "stream", "fire", RecordingLibrary.ALARM_PREFIX + "birds")
    }
}
