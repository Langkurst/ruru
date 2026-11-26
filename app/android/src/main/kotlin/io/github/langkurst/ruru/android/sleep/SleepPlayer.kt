package io.github.langkurst.ruru.android.sleep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Process
import io.github.langkurst.ruru.sleep.AlarmOutput
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepPlanner
import io.github.langkurst.ruru.sleep.SleepTimeline
import io.github.langkurst.ruru.sleep.SoundSide
import io.github.langkurst.ruru.sleep.sound.AlarmTone
import io.github.langkurst.ruru.sleep.sound.AlarmTones
import io.github.langkurst.ruru.sleep.sound.ChannelMode
import io.github.langkurst.ruru.sleep.sound.FadeCurve
import io.github.langkurst.ruru.sleep.sound.SleepSounds
import io.github.langkurst.ruru.sleep.sound.SoundLayer
import io.github.langkurst.ruru.sleep.sound.SoundMixer
import io.github.langkurst.ruru.sleep.sound.SoundSource

/** Where a track plays: the media route (Bluetooth when connected) or the phone's own speaker as an alarm. */
internal enum class Route { MEDIA, PHONE_ALARM }

/**
 * Plays the night's sounds and the alarms through `AudioTrack`s. A track is fed by its own thread that pulls
 * stereo floats from the sound engine ([SoundMixer] or an alarm tone) and writes them (a blocking write
 * paces it), at the volume the night's timeline or the alarm's fade-in says. Nothing here decides *when*:
 * the service calls [startNight], [moveTimeline], [stopNight], [startAlarm] and [stopAlarm].
 */
internal class SleepPlayer(private val context: Context) {
    private val audio get() = context.getSystemService(AudioManager::class.java)

    private var night: TrackThread? = null
    @Volatile private var timeline: SleepTimeline? = null
    private val alarmTracks = ArrayList<TrackThread>(2)
    private var ringtone: Ringtone? = null

    /** True while the night's sounds are being played. */
    val nightPlaying: Boolean get() = night != null

    fun startNight(plan: SleepPlan, timeline: SleepTimeline, seed: Long) {
        stopNight()
        this.timeline = timeline
        if (plan.layers.isEmpty()) return
        val mixer = SoundMixer(seed)
        mixer.setLayers(plan.layers.map { SoundLayer(it.soundId, it.gain) })
        mixer.channelMode = when (plan.soundSide) {
            SoundSide.BOTH -> ChannelMode.STEREO
            SoundSide.MONO -> ChannelMode.MONO
            SoundSide.LEFT_ONLY -> ChannelMode.LEFT_ONLY
            SoundSide.RIGHT_ONLY -> ChannelMode.RIGHT_ONLY
        }
        val volume = plan.volume
        night = TrackThread(
            name = "ruru-sleep-night",
            route = Route.MEDIA,
            render = { out, frames, nowMillis ->
                val line = this.timeline
                mixer.masterGain = if (line == null) 0f else SleepPlanner.envelope(line, nowMillis) * volume
                mixer.render(out, frames)
            },
        ).also { it.start() }
    }

    /** "+N min": the fade moves later with the end. */
    fun moveTimeline(line: SleepTimeline) {
        timeline = line
    }

    /** Ends the night's sounds now. The caller has already faded them if it wanted a fade (the timer's own fade-out does). */
    fun stopNight() {
        night?.finish()
        night = null
        timeline = null
    }

    /**
     * Rings [alarm] in [output]; the volume rises over the alarm's fade-in. A recorded tone plays when it is decoded,
     * the synthesised one otherwise.
     */
    fun startAlarm(alarm: SleepAlarm, output: AlarmOutput, seed: Long) {
        stopAlarm()
        if (alarm.toneId == SleepAlarm.TONE_SYSTEM) {
            if (output != AlarmOutput.EARBUDS) startRingtone()
            if (output == AlarmOutput.EARBUDS || output == AlarmOutput.BOTH) addAlarmTrack(alarm, Route.MEDIA, seed)
            return
        }
        if (output == AlarmOutput.EARBUDS || output == AlarmOutput.BOTH) addAlarmTrack(alarm, Route.MEDIA, seed)
        if (output == AlarmOutput.PHONE || output == AlarmOutput.BOTH) addAlarmTrack(alarm, Route.PHONE_ALARM, seed)
    }

    /** The earbuds alarm was not stopped: add the phone's speaker to what rings. */
    fun addPhone(alarm: SleepAlarm, seed: Long) {
        if (alarmTracks.any { it.route == Route.PHONE_ALARM }) return
        if (alarm.toneId == SleepAlarm.TONE_SYSTEM) startRingtone() else addAlarmTrack(alarm, Route.PHONE_ALARM, seed)
    }

    fun stopAlarm() {
        for (t in alarmTracks) t.finish()
        alarmTracks.clear()
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    fun release() {
        stopNight()
        stopAlarm()
    }

    private fun addAlarmTrack(alarm: SleepAlarm, route: Route, seed: Long) {
        val source: SoundSource = alarmSource(alarm, seed)
        val fadeMs = alarm.fadeInSeconds.coerceAtLeast(0) * 1000L
        val startedAt = System.currentTimeMillis()
        alarmTracks += TrackThread(
            name = "ruru-sleep-alarm-${route.name.lowercase()}",
            route = route,
            render = { out, frames, nowMillis ->
                source.render(out, 0, frames)
                val gain = FadeCurve.fadeIn(nowMillis - startedAt, fadeMs)
                if (gain < 1f) for (i in 0 until frames * 2) out[i] *= gain
            },
        ).also { it.start() }
    }

    private fun alarmSource(alarm: SleepAlarm, seed: Long): SoundSource {
        val id = alarm.toneId
        if (id.startsWith(SleepAlarm.TONE_SOUND_PREFIX)) return SleepSounds.create(id.removePrefix(SleepAlarm.TONE_SOUND_PREFIX), seed)
        return AlarmTones.create(AlarmTone.fromStoredId(id) ?: AlarmTone.CHIME, seed)
    }

    private fun startRingtone() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(context, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        }
    }

    /** True when something that can carry sound to the ears (Bluetooth headphones, a wired headset) is connected. */
    fun earbudsReachable(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isEarbudRoute(it.type) }

    /**
     * One `AudioTrack` and the thread that feeds it. [render] fills `frames` stereo frames and is told the wall-clock
     * time, so the volume follows the clock, not the number of frames written.
     */
    private inner class TrackThread(
        name: String,
        val route: Route,
        private val render: (FloatArray, Int, Long) -> Unit,
    ) : Thread(name) {
        @Volatile private var running = true
        private var track: AudioTrack? = null

        init {
            priority = MAX_PRIORITY
        }

        fun finish() {
            running = false
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { join(1_000) }
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val usage = if (route == Route.PHONE_ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
            val content = if (route == Route.PHONE_ALARM) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(content).build())
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, CHUNK_FRAMES * 2 * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                return
            }
            track = t
            if (route == Route.PHONE_ALARM) {
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { t.setPreferredDevice(it) }
            }
            val buffer = FloatArray(CHUNK_FRAMES * 2)
            try {
                t.play()
                while (running) {
                    render(buffer, CHUNK_FRAMES, System.currentTimeMillis())
                    val written = t.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                }
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }

    companion object {
        const val RATE = SleepSounds.SAMPLE_RATE
        const val CHUNK_FRAMES = 2_048

        /** Output types that reach the ears. */
        internal fun isEarbudRoute(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }
}
