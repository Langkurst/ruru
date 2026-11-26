package io.github.langkurst.ruru.android.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.langkurst.ruru.android.MainActivity
import io.github.langkurst.ruru.app.settings.SettingsStore

/** The one place that talks to Android's AlarmManager. What to set comes from [AlarmArming]. */
class AlarmArmer(private val context: Context, private val settings: SettingsStore) {
    private val manager get() = context.getSystemService(AlarmManager::class.java)

    /** True when exact (not just roughly timed) alarms are allowed: always before Android 12, then by the permission. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)

    /**
     * Makes the armed alarms of [kinds] exactly [wanted]: new ones are set (an alarm with the same identity is
     * replaced), ones set earlier that are no longer wanted are cancelled. Alarms of other kinds are left alone.
     */
    fun sync(kinds: Set<ArmKind>, wanted: List<ArmedAlarm>) {
        val previous = settings.getStringList(KEY).toSet()
        val mine = previous.filter { id -> kinds.any { id.startsWith(it.name + ":") } }.toSet()
        for (id in AlarmArming.stale(mine, wanted)) cancel(id)
        for (alarm in wanted) set(alarm)
        settings.putStringList(KEY, ((previous - mine) + wanted.map { it.id }).toList())
    }

    /** Cancels everything of [kind]. */
    fun clear(kind: ArmKind) = sync(setOf(kind), emptyList())

    private fun set(alarm: ArmedAlarm) {
        val pending = pendingFor(alarm.kind, alarm.key, alarm.id, AlarmArming.requestCode(alarm))
        runCatching {
            when (alarm.kind) {
                ArmKind.WAKE, ArmKind.SNOOZE -> {
                    // A clock alarm: exact on every Android, needs no permission, and shows in the status bar.
                    val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), FLAGS)
                    manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.atMillis, show), pending)
                }
                ArmKind.STAGE, ArmKind.NIGHT ->
                    if (canScheduleExact()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMillis, pending)
                    else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMillis, pending)
            }
        }
    }

    private fun cancel(id: String) {
        val kind = ArmKind.entries.firstOrNull { id.startsWith(it.name + ":") } ?: return
        val key = id.substringAfter(':')
        val alarm = ArmedAlarm(kind, key, 0L)
        runCatching { manager.cancel(pendingFor(kind, key, id, AlarmArming.requestCode(alarm))) }
    }

    private fun pendingFor(kind: ArmKind, key: String, id: String, code: Int): PendingIntent {
        val intent = Intent(context, SleepReceiver::class.java)
            .setAction(kind.action)
            .setData(Uri.parse("ruru://arm/" + Uri.encode(id)))
            .putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(context, code, intent, FLAGS)
    }

    companion object {
        const val EXTRA_KEY = "key"
        private const val KEY = "sleep.armed"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
