package io.github.langkurst.ruru.android.sleep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.langkurst.ruru.android.MainActivity
import io.github.langkurst.ruru.android.R
import io.github.langkurst.ruru.android.RuruApplication
import io.github.langkurst.ruru.sleep.AlarmOutput
import io.github.langkurst.ruru.sleep.SleepAlarm
import io.github.langkurst.ruru.sleep.SleepPlan
import io.github.langkurst.ruru.sleep.SleepSessionState
import io.github.langkurst.ruru.sleep.SleepTimeline
import io.github.langkurst.ruru.sleep.sound.AlarmTone
import io.github.langkurst.ruru.sleep.sound.RecordingLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Plays the night and rings the alarms: a foreground service of type mediaPlayback, so Android keeps the sound
 * going with the screen off. It is started from a visible screen (Start sleep), from an alarm that has just
 * gone off (an exact alarm is allowed to start it) and from the notification's own buttons. All the rules are
 * in [SleepMachine]; this class runs the effects it returns: sound ([SleepPlayer]), the headset's night steps
 * ([SleepRuntime]), the notification, vibration and the next alarm.
 */
class SleepService : Service() {
    private val app get() = application as RuruApplication
    private val runtime get() = app.sleepRuntime
    private var player: SleepPlayer? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var ringing: SleepAlarm? = null
    private var seed = 1L

    /** Effects are run one batch at a time, in order, off the main thread. */
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        player = SleepPlayer(this)
        worker = app.scope.launch { for (job in work) runCatching { job() }.onFailure { if (it is CancellationException) throw it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        makeChannels()
        // Android wants a notification within seconds of every start, so it comes first, whatever the intent asks.
        enterForeground(runtime.machine.state.value)
        if (watcher == null) {
            watcher = app.scope.launch {
                runtime.machine.state.collect { state ->
                    if (state is SleepSessionState.Idle) return@collect
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(state))
                }
            }
        }
        val effects = when (intent?.action) {
            ACTION_START -> { submit { begin() }; null }
            ACTION_RING -> { val id = intent.getStringExtra(EXTRA_ALARM_ID); submit { ring(id) }; null }
            ACTION_STOP -> runtime.machine.stop()
            ACTION_EXTEND -> runtime.machine.extend(intent.getIntExtra(EXTRA_MINUTES, 15))
            ACTION_STOP_ALARM -> runtime.machine.dismiss()
            ACTION_SNOOZE -> runtime.machine.snooze()
            ACTION_TICK -> runtime.machine.tick()
            else -> emptyList()
        }
        if (effects != null) {
            run(effects)
            // Nothing to do and nothing running (a stray start, a tick after the night ended): do not linger.
            if (effects.isEmpty() && runtime.machine.state.value is SleepSessionState.Idle) finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        watcher?.cancel()
        worker?.cancel()
        work.close()
        player?.release()
        stopVibrating()
        releaseWake()
        if (runtime.machine.state.value is SleepSessionState.Running || runtime.machine.state.value is SleepSessionState.Ringing) runtime.machine.reset()
        super.onDestroy()
    }

    private fun submit(job: suspend () -> Unit) {
        work.trySend(job)
    }

    private fun run(effects: List<SleepEffect>) {
        if (effects.isEmpty()) return
        submit { for (e in effects) execute(e) }
    }

    // ------------------------------------------------------------------ starting

    private suspend fun begin() {
        val plan = runtime.plan()
        val demoSeconds = pendingDemoSeconds
        pendingDemoSeconds = null
        runtime.clearNotes()
        runtime.recordings.prepare(plan.layers.map { it.soundId })
        val effects = runtime.machine.start(plan, headsetName = null, lengthMillis = demoSeconds?.let { it * 1000L })
        for (e in effects) execute(e)
    }

    private suspend fun ring(alarmId: String?) {
        val alarm = runtime.alarms().firstOrNull { it.id == alarmId }
        // A once-only alarm switches itself off after it rings; the next time of a repeating one is set now.
        if (alarm == null) {
            if (runtime.machine.state.value is SleepSessionState.Idle) finish()
            return
        }
        val tone = AlarmTone.fromStoredId(alarm.toneId)
        if (tone == AlarmTone.BIRDS) runtime.recordings.prepare(listOf(RecordingLibrary.ALARM_PREFIX + "birds"))
        if (alarm.toneId.startsWith(SleepAlarm.TONE_SOUND_PREFIX)) runtime.recordings.prepare(listOf(alarm.toneId.removePrefix(SleepAlarm.TONE_SOUND_PREFIX)))
        val reachable = player?.earbudsReachable() == true
        for (e in runtime.machine.ring(alarm, reachable)) execute(e)
        app.sleepModel.let { model -> if (alarm.days.isEmpty()) model.setAlarmOn(alarm.id, false) }
        runtime.rearmWake()
    }

    // ------------------------------------------------------------------ the effects

    private suspend fun execute(effect: SleepEffect) {
        when (effect) {
            is SleepEffect.BeginNight -> {
                lastPlan = effect.plan
                acquireWake(effect.timeline.durationMillis + 30 * 60_000L)
                seed = System.nanoTime()
                player?.startNight(effect.plan, effect.timeline, seed)
                runtime.armer.sync(setOf(ArmKind.STAGE), AlarmArming.stages(effect.timeline, runtime.clock()))
                startTicker()
                runtime.beginHeadset(effect.plan)
            }
            is SleepEffect.TimelineMoved -> {
                player?.moveTimeline(effect.timeline)
                runtime.armer.sync(setOf(ArmKind.STAGE), AlarmArming.stages(effect.timeline, runtime.clock()))
                acquireWake(effect.timeline.endsAtMillis - runtime.clock() + 30 * 60_000L)
            }
            SleepEffect.NoiseOff -> runtime.noiseOffNow()
            is SleepEffect.EndNight -> endNight(effect)
            is SleepEffect.Ring -> startRinging(effect)
            is SleepEffect.AlsoRingOnPhone -> {
                player?.addPhone(effect.alarm, seed)
                vibrate(effect.alarm)
            }
            SleepEffect.StopRinging -> {
                player?.stopAlarm()
                stopVibrating()
                ringing = null
                getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
            }
            is SleepEffect.ArmSnooze -> runtime.armer.sync(setOf(ArmKind.SNOOZE), listOf(ArmedAlarm(ArmKind.SNOOZE, effect.alarmId, effect.atMillis)))
            SleepEffect.Finished -> finish()
        }
    }

    private suspend fun endNight(effect: SleepEffect.EndNight) {
        val plan: SleepPlan = lastPlan ?: runtime.plan()
        if (effect.byPerson) {
            // A short fade so a stop is not a click.
            val now = runtime.clock()
            player?.moveTimeline(SleepTimeline(now - 1, now + 2_000, now, null))
            delay(2_100)
        }
        player?.stopNight()
        runtime.recordings.release()
        runtime.armer.clear(ArmKind.STAGE)
        runtime.endHeadset(plan, effect.byPerson)
        releaseWake()
    }

    private var lastPlan: SleepPlan? = null

    private fun startRinging(effect: SleepEffect.Ring) {
        ringing = effect.alarm
        acquireWake(RING_WAKE_MS)
        seed = System.nanoTime()
        player?.startAlarm(effect.alarm, effect.output, seed)
        if (effect.output != AlarmOutput.EARBUDS) vibrate(effect.alarm)
        startTicker()
        // Show the alarm screen over a locked phone, as well as the notification.
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALARM_NOTIFICATION_ID, build(runtime.machine.state.value))
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = app.scope.launch {
            while (true) {
                delay(500)
                val effects = runtime.machine.tick()
                if (effects.isNotEmpty()) run(effects)
            }
        }
    }

    private fun finish() {
        ticker?.cancel()
        ticker = null
        player?.stopNight()
        stopForegroundCompat()
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun makeChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_NIGHT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_NIGHT, getString(R.string.sleep_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.sleep_channel_description)
                    setShowBadge(false)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ALARM) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, getString(R.string.sleep_alarm_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = getString(R.string.sleep_alarm_channel_description)
                    setSound(null, null)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun enterForeground(state: SleepSessionState) {
        val notification = build(state)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            // Android refused a start from the background: nothing can play, so there is nothing to keep.
            stopSelf()
        }
    }

    private fun build(state: SleepSessionState): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        fun service(action: String, code: Int, extras: Intent.() -> Unit = {}) =
            PendingIntent.getService(this, code, Intent(this, SleepService::class.java).setAction(action).apply(extras), flags)
        if (state is SleepSessionState.Ringing) {
            val screen = PendingIntent.getActivity(this, 2, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), flags)
            val b = Notification.Builder(this, CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(state.label ?: getString(R.string.sleep_notif_alarm))
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), service(ACTION_STOP_ALARM, 3)).build())
            if (state.canSnooze) b.addAction(Notification.Action.Builder(null, getString(R.string.sleep_notif_snooze), service(ACTION_SNOOZE, 4)).build())
            return b.build()
        }
        val b = Notification.Builder(this, CHANNEL_NIGHT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sleep_notif_sleeping))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (state is SleepSessionState.Running) {
            b.setShowWhen(true).setWhen(state.timeline.endsAtMillis).setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true)
            b.addAction(Notification.Action.Builder(null, getString(R.string.sleep_notif_plus, 15), service(ACTION_EXTEND, 5) { putExtra(EXTRA_MINUTES, 15) }).build())
        } else {
            b.setShowWhen(false)
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), service(ACTION_STOP, 6)).build())
        return b.build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    // ------------------------------------------------------------------ wake lock and vibration

    private fun acquireWake(forMillis: Long) {
        releaseWake()
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruru:sleep").apply {
            setReferenceCounted(false)
            acquire(forMillis.coerceIn(60_000L, MAX_WAKE_MS))
        }
    }

    private fun releaseWake() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    @Suppress("DEPRECATION")
    private fun vibrate(alarm: SleepAlarm) {
        if (!alarm.vibrate) return
        val v = getSystemService(Vibrator::class.java) ?: return
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun stopVibrating() {
        runCatching { getSystemService(Vibrator::class.java)?.cancel() }
    }

    companion object {
        const val ACTION_START = "io.github.langkurst.ruru.sleep.START"
        const val ACTION_STOP = "io.github.langkurst.ruru.sleep.STOP"
        const val ACTION_EXTEND = "io.github.langkurst.ruru.sleep.EXTEND"
        const val ACTION_RING = "io.github.langkurst.ruru.sleep.RING"
        const val ACTION_STOP_ALARM = "io.github.langkurst.ruru.sleep.STOP_ALARM"
        const val ACTION_SNOOZE = "io.github.langkurst.ruru.sleep.SNOOZE"
        const val ACTION_TICK = "io.github.langkurst.ruru.sleep.TICK"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_ALARM_ID = "alarm"
        private const val CHANNEL_NIGHT = "ruru_sleep"
        private const val CHANNEL_ALARM = "ruru_alarm"
        private const val NOTIFICATION_ID = 2
        private const val ALARM_NOTIFICATION_ID = 2
        private const val RING_WAKE_MS = SleepMachine.RING_LIMIT_MS + 60_000L
        private const val MAX_WAKE_MS = 8 * 3_600_000L + 30 * 60_000L

        /** Length override for the emulator check's one-minute demo night; read once by the next start. */
        @Volatile internal var pendingDemoSeconds: Int? = null

        /**
         * Sends [action] to the service. Never throws: Android refuses a start from the background (the person
         * has to be on a screen of Ruru, or an alarm has just gone off), and then nothing happens.
         */
        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, SleepService::class.java).setAction(action).apply(extras)
            runCatching { context.startForegroundService(intent) }
        }

        /** A wake-up alarm is due. */
        fun ring(context: Context, alarmId: String) = send(context, ACTION_RING) { putExtra(EXTRA_ALARM_ID, alarmId) }
    }
}
