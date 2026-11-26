package io.github.langkurst.ruru.android.sleep

import io.github.langkurst.ruru.android.service.ServiceNeed
import io.github.langkurst.ruru.android.service.ServiceNeeds
import io.github.langkurst.ruru.app.night.NightRunner
import io.github.langkurst.ruru.sleep.NightAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Whether Ruru has to be kept awake for the night schedule's next step. Pure, so the timing is tested. */
object NightKeepAwake {
    /** A step due within this long (or overdue by less than [GIVE_UP_MS]) keeps the service running. */
    const val BEFORE_MS = AlarmArming.LEAD_MS + 60_000L

    /** An overdue step the headphones never took (they were not connected) stops holding the phone awake after this. */
    const val GIVE_UP_MS = 20 * 60_000L

    fun needed(action: NightAction?, nowMillis: Long): Boolean {
        action ?: return false
        val until = action.atMillis - nowMillis
        return until <= BEFORE_MS && -until <= GIVE_UP_MS
    }
}

/**
 * Carries the night schedule on Android. The [NightRunner] does the work while Ruru's process lives; this driver
 * makes sure it does: an exact alarm wakes Ruru a couple of minutes before each step, [ServiceNeed.NightSchedule]
 * starts the foreground service so the connection stays up while the step is due, and the service is let go once
 * the step is done and the next is hours away. It looks again whenever the schedule or its progress changes, on
 * every restart, and every half minute.
 */
class NightDriver(
    private val scope: CoroutineScope,
    private val night: NightRunner,
    private val needs: ServiceNeeds,
    private val armer: AlarmArmer,
    private val connect: () -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        night.start()
        job = scope.launch {
            launch { combine(night.currentSchedule, night.currentProgress) { _, _ -> }.collect { refresh() } }
            while (true) {
                refresh()
                delay(30_000)
            }
        }
    }

    /** The exact alarm went off: Ruru is awake, so keep it so and connect to the headphones. */
    fun onAlarm() {
        start()
        needs.set(ServiceNeed.NightSchedule, true)
        connect()
        refresh()
    }

    fun refresh() {
        val now = night.nowMillis()
        val next = night.nextAction()
        val keep = NightKeepAwake.needed(next, now)
        if (keep) connect()
        needs.set(ServiceNeed.NightSchedule, keep)
        armer.sync(setOf(ArmKind.NIGHT), listOfNotNull(AlarmArming.night(next, now).takeIf { !keep && next != null && next.atMillis > now }))
    }
}
