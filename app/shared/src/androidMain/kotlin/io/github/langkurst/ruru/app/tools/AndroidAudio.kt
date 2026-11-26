package io.github.langkurst.ruru.app.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * What the Android build of the sound tools needs from the app: the application context
 * (RuruApplication calls [init]) and a way to ask for the microphone permission (MainActivity sets
 * [requestMicPermission] and calls [micPermissionAnswered] with the answer).
 */
object AndroidAudio {
    @Volatile
    internal var context: Context? = null

    /** Opens the system's permission question for RECORD_AUDIO; set by the activity. */
    @Volatile
    var requestMicPermission: (() -> Unit)? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun micPermissionAnswered(granted: Boolean) {
        audioTools().mic?.permissionAnswered(granted)
    }
}

private val headphoneOutputTypes = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

private fun isHeadphoneOutput(device: AudioDeviceInfo): Boolean =
    device.type in headphoneOutputTypes || (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

/**
 * Plays through an [AudioTrack]. With [headphonesOnly] the sound goes to a connected pair of headphones
 * or nowhere: a find tone that came out of the phone's speaker would send the person the wrong way.
 */
private class AndroidOutput(private val context: Context, private val headphonesOnly: Boolean) : PcmOutput {
    override fun open(sampleRate: Int, channels: Int): PcmSink {
        val audio = context.getSystemService(AudioManager::class.java) ?: throw AudioProblem(Words("tool_no_output"))
        val target = if (headphonesOnly) {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isHeadphoneOutput)
                ?: throw AudioProblem(Words("tool_find_no_headphones"))
        } else null
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val track = try {
            val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (headphonesOnly) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(mask).build(),
                )
                // A quarter of a second, so stop is quick.
                .setBufferSizeInBytes(maxOf(min, sampleRate * channels * 2 / 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            throw AudioProblem(Words("tool_no_output"), e)
        }
        if (target != null && !track.setPreferredDevice(target)) {
            track.release()
            throw AudioProblem(Words("tool_find_no_headphones"))
        }
        track.play()
        return object : PcmSink {
            private var frames = 0L

            override fun write(pcm: ShortArray, count: Int) {
                var offset = 0
                while (offset < count) {
                    val n = track.write(pcm, offset, count - offset)
                    if (n <= 0) return // paused by interrupt(), or the output stopped
                    offset += n
                }
                frames += count / channels
            }

            override fun drain() {
                runCatching { track.stop() } // a stream track plays what is queued, then stops
                val limit = System.currentTimeMillis() + frames * 1000 / sampleRate + 1_000
                while (track.playbackHeadPosition < frames && System.currentTimeMillis() < limit) Thread.sleep(20)
            }

            override fun interrupt() {
                // A paused track makes a blocked write return.
                runCatching { track.pause() }
                runCatching { track.flush() }
            }

            override fun close() {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
        }
    }
}

/**
 * Records through an [AudioRecord] on the voice-call source. From Android 12 the headphones'
 * own microphone is chosen as the communication device while it records, and released after.
 * Needs RECORD_AUDIO, which the caller has checked.
 */
private class AndroidInput(private val context: Context) : PcmInput {
    @Suppress("MissingPermission")
    override fun open(sampleRate: Int): PcmSource {
        val audio = context.getSystemService(AudioManager::class.java)
        val previousMode = audio?.mode
        var chose = false
        if (audio != null && Build.VERSION.SDK_INT >= 31) {
            val headset = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (headset != null) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                chose = audio.setCommunicationDevice(headset)
            }
        }
        fun release() {
            if (audio == null) return
            if (chose && Build.VERSION.SDK_INT >= 31) runCatching { audio.clearCommunicationDevice() }
            if (previousMode != null) runCatching { audio.mode = previousMode }
        }
        val record = try {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate * 2),
            )
        } catch (e: Exception) {
            release()
            throw AudioProblem(Words("tool_no_input"), e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            release()
            throw AudioProblem(Words("tool_no_input"))
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            release()
            throw AudioProblem(Words("tool_no_input"))
        }
        return object : PcmSource {
            override fun read(buffer: ShortArray): Int {
                val n = record.read(buffer, 0, buffer.size)
                return if (n < 0) -1 else n
            }

            override fun interrupt() {
                runCatching { record.stop() }
            }

            override fun close() {
                runCatching { record.stop() }
                runCatching { record.release() }
                release()
            }
        }
    }
}

private val androidTools: AudioTools by lazy {
    val context = AndroidAudio.context ?: return@lazy AudioTools(find = null, mic = null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    AudioTools(
        find = FindController(scope, Dispatchers.IO, AndroidOutput(context, headphonesOnly = true)),
        mic = MicCheckController(
            scope, Dispatchers.IO, AndroidInput(context), AndroidOutput(context, headphonesOnly = false),
            hasPermission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            askPermission = { AndroidAudio.requestMicPermission?.invoke() },
        ),
    )
}

actual fun audioTools(): AudioTools = androidTools
