package io.github.langkurst.ruru.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/** Why Ruru needs to keep running in the background. */
enum class ServiceNeed {
    /** The Pro equaliser is on. */
    Eq,

    /** The night schedule has a step to run (the night schedule and alarm builders set this). */
    NightSchedule,
}

/**
 * The list of reasons [RuruService] should run. The service is started while the list has any reason and
 * stopped when it is empty, so a feature only says "I need it" and "I'm done":
 *
 *     serviceNeeds.set(ServiceNeed.NightSchedule, true)   // a night step is coming
 *     serviceNeeds.onStopRequested { nightSchedule.pause() }   // the person pressed Stop in the notification
 *
 * Pure, so the rules are tested without Android.
 */
class ServiceNeeds {
    private val current = MutableStateFlow<Set<ServiceNeed>>(emptySet())
    val needs: StateFlow<Set<ServiceNeed>> = current

    private val handlers = CopyOnWriteArrayList<() -> Unit>()

    fun set(need: ServiceNeed, on: Boolean) {
        current.update { if (on) it + need else it - need }
    }

    /** Called when the person presses Stop in the notification, before the needs are cleared: each feature switches itself off. */
    fun onStopRequested(handler: () -> Unit) {
        handlers += handler
    }

    /** Stop pressed: every feature is told, then nothing is needed any more. */
    fun requestStop() {
        for (handler in handlers) runCatching { handler() }
        current.value = emptySet()
    }
}
