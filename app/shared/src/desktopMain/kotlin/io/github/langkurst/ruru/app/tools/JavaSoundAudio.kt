package io.github.langkurst.ruru.app.tools

import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

private fun format(sampleRate: Int, channels: Int) =
    AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

private fun ShortArray.toLittleEndian(count: Int): ByteArray {
    val out = ByteArray(count * 2)
    for (i in 0 until count) {
        out[i * 2] = (this[i].toInt() and 0xFF).toByte()
        out[i * 2 + 1] = ((this[i].toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

/** Plays through the system's default output (whatever the person has chosen as the sound output). */
internal object JavaSoundOutput : PcmOutput {
    override fun open(sampleRate: Int, channels: Int): PcmSink {
        val fmt = format(sampleRate, channels)
        val info = DataLine.Info(SourceDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) throw AudioProblem(Words("tool_no_output"))
        val line = try {
            AudioSystem.getSourceDataLine(fmt).also {
                // A short buffer (a quarter of a second) keeps stop quick.
                it.open(fmt, sampleRate * channels * 2 / 4)
                it.start()
            }
        } catch (e: Exception) {
            throw AudioProblem(Words("tool_no_output"), e)
        }
        return object : PcmSink {
            override fun write(pcm: ShortArray, count: Int) {
                val bytes = pcm.toLittleEndian(count)
                var offset = 0
                while (offset < bytes.size) {
                    val n = line.write(bytes, offset, bytes.size - offset)
                    if (n <= 0) return // closed from stop()
                    offset += n
                }
            }

            override fun drain() = line.drain()
            override fun interrupt() {
                // close() is what makes a writer blocked on a full buffer return.
                runCatching { line.stop() }
                runCatching { line.flush() }
                runCatching { line.close() }
            }

            override fun close() {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }
    }
}

/** Records from the system's default input (the person picks it in the system's sound settings). */
internal object JavaSoundInput : PcmInput {
    override fun open(sampleRate: Int): PcmSource {
        val fmt = format(sampleRate, 1)
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) throw AudioProblem(Words("tool_no_input"))
        val line = try {
            AudioSystem.getTargetDataLine(fmt).also {
                it.open(fmt)
                it.start()
            }
        } catch (e: Exception) {
            // The usual cause on Windows is the microphone privacy switch, or another app holding the microphone.
            throw AudioProblem(Words("tool_no_input"), e)
        }
        return object : PcmSource {
            override fun read(buffer: ShortArray): Int {
                val bytes = ByteArray(buffer.size * 2)
                val n = line.read(bytes, 0, bytes.size)
                if (n <= 0) return -1
                val samples = n / 2
                for (i in 0 until samples) {
                    buffer[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
                return samples
            }

            override fun interrupt() {
                runCatching { line.stop() }
                runCatching { line.flush() }
                runCatching { line.close() }
            }

            override fun close() {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }
    }
}

private val desktopTools: AudioTools by lazy {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    AudioTools(
        find = FindController(scope, Dispatchers.IO, JavaSoundOutput),
        mic = MicCheckController(scope, Dispatchers.IO, JavaSoundInput, JavaSoundOutput),
    )
}

actual fun audioTools(): AudioTools = desktopTools
