package io.github.langkurst.ruru.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.langkurst.ruru.android.MainActivity
import io.github.langkurst.ruru.android.R
import io.github.langkurst.ruru.android.RuruApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Ruru running while a feature needs it ([ServiceNeeds]: the equaliser, later the night schedule).
 * A foreground service of type connectedDevice with one quiet notification, "Ruru is connected to <headphones>",
 * with Open and Stop. The connection itself lives in [RuruApplication] (its [io.github.langkurst.ruru.app.AppModel]),
 * so it survives the screen; this service is what stops Android from ending the process while the screen is off.
 *
 * Started only from a visible screen or a feature the person just switched on: Android refuses a background
 * start, and then the feature still works for as long as the process lives.
 */
class RuruService : Service() {
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RuruApplication
        if (intent?.action == ACTION_STOP) {
            app.serviceNeeds.requestStop()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        makeChannel()
        val line = NotificationLine.of(app.model.controller.value.state.value)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, build(line), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, build(line))
            }
        } catch (e: RuntimeException) {
            // Android refused (for example Bluetooth is not allowed yet): the feature works while the process lives.
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) {
            // Say what the headphones are called as soon as they are known, and when they go.
            watching = app.scope.launch {
                app.model.controller.flatMapLatest { it.state }.map { NotificationLine.of(it) }.distinctUntilChanged().collect { text ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watching?.cancel()
        watching = null
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun makeChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_channel_description)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun build(line: NotificationLine): Notification {
        val text = when (line) {
            is NotificationLine.Connected -> getString(R.string.notif_connected, line.name)
            NotificationLine.Waiting -> getString(R.string.notif_waiting)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuruService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_open), open).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.langkurst.ruru.action.STOP"
        private const val CHANNEL_ID = "ruru_background"
        private const val NOTIFICATION_ID = 1

        /**
         * Start the service. Never throws: Android refuses a start from the background, and a phone
         * without the Nearby devices permission cannot run a connectedDevice service on Android 14 and newer.
         */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            runCatching { context.startForegroundService(Intent(context, RuruService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RuruService::class.java)) }
        }
    }
}
