package io.github.langkurst.ruru.android.service

import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.Phase
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the foreground service is wanted, and what its notification says. */
class ServiceNeedsTest {
    private val needs = ServiceNeeds()

    @Test
    fun `the service is wanted while any feature needs it`() {
        assertEquals(emptySet(), needs.needs.value)
        needs.set(ServiceNeed.Eq, true)
        needs.set(ServiceNeed.NightSchedule, true)
        needs.set(ServiceNeed.Eq, false)
        assertEquals(setOf(ServiceNeed.NightSchedule), needs.needs.value)
        needs.set(ServiceNeed.NightSchedule, false)
        assertEquals(emptySet(), needs.needs.value)
    }

    @Test
    fun `Stop tells every feature to let go and clears the list, even if one handler fails`() {
        val told = ArrayList<String>()
        needs.onStopRequested { told += "eq" }
        needs.onStopRequested { error("broken") }
        needs.onStopRequested { told += "night" }
        needs.set(ServiceNeed.Eq, true)
        needs.requestStop()
        assertEquals(listOf("eq", "night"), told)
        assertEquals(emptySet(), needs.needs.value)
    }

    @Test
    fun `the notification names the headphones only while they are connected`() {
        assertEquals(NotificationLine.Connected("Quiet Pair"), NotificationLine.of(Phase.Connected, "Quiet Pair"))
        assertEquals(NotificationLine.Waiting, NotificationLine.of(Phase.Connected, null))
        assertEquals(NotificationLine.Waiting, NotificationLine.of(Phase.Connected, "  "))
        assertEquals(NotificationLine.Waiting, NotificationLine.of(Phase.Starting, "Quiet Pair"))
        assertEquals(NotificationLine.Waiting, NotificationLine.of(AppState()))
    }
}
