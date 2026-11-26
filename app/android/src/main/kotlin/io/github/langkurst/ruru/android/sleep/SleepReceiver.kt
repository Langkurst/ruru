package io.github.langkurst.ruru.android.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.langkurst.ruru.android.RuruApplication

/** What a broadcast asks of Ruru. Pure, so the table is tested. */
enum class ReceiverJob { RING, STAGE, NIGHT_STEP, REARM_ALL, NONE }

object ReceiverRules {
    fun jobFor(action: String?): ReceiverJob = when (action) {
        ArmKind.WAKE.action, ArmKind.SNOOZE.action -> ReceiverJob.RING
        ArmKind.STAGE.action -> ReceiverJob.STAGE
        ArmKind.NIGHT.action -> ReceiverJob.NIGHT_STEP
        // The phone started again, Ruru was updated, or the clock or time zone changed: every alarm is set again.
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        -> ReceiverJob.REARM_ALL
        else -> ReceiverJob.NONE
    }
}

/** Receives the alarms Ruru set (wake-ups, snoozes, night stages, night-schedule steps) and the restart and clock-change broadcasts. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RuruApplication
        when (ReceiverRules.jobFor(intent.action)) {
            ReceiverJob.RING -> intent.getStringExtra(AlarmArmer.EXTRA_KEY)?.let { SleepService.ring(context, it) }
            ReceiverJob.STAGE -> SleepService.send(context, SleepService.ACTION_TICK)
            ReceiverJob.NIGHT_STEP -> app.nightDriver.onAlarm()
            ReceiverJob.REARM_ALL -> {
                app.sleepRuntime.rearmWake()
                app.nightDriver.refresh()
            }
            ReceiverJob.NONE -> Unit
        }
    }
}
