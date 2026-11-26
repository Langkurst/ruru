package io.github.langkurst.ruru.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndActionRulesTest {
    private val earbudAlarm = SleepAlarm("a", 7, 0, output = AlarmOutput.EARBUDS)
    private val phoneAlarm = SleepAlarm("p", 7, 0, output = AlarmOutput.PHONE)

    @Test
    fun `stopping the sounds never conflicts with an alarm`() {
        val r = EndActionRules.resolve(SleepPlan(endAction = SleepEndAction.STOP_SOUNDS), earbudAlarm, canReconnect = false)
        assertEquals(EndPlan(SleepEndAction.STOP_SOUNDS, false, null), r)
    }

    @Test
    fun `no earbud alarm means the chosen end action stands`() {
        for (alarm in listOf(null, phoneAlarm, earbudAlarm.copy(enabled = false))) {
            val r = EndActionRules.resolve(SleepPlan(endAction = SleepEndAction.POWER_OFF), alarm, canReconnect = true)
            assertEquals(EndPlan(SleepEndAction.POWER_OFF, false, null), r)
        }
    }

    @Test
    fun `power off before an earbud alarm becomes disconnect with a reconnect two minutes ahead`() {
        val r = EndActionRules.resolve(SleepPlan(endAction = SleepEndAction.POWER_OFF), earbudAlarm, canReconnect = true)
        assertEquals(SleepEndAction.DISCONNECT, r.action)
        assertFalse(r.alarmMovesToPhone)
        assertEquals(2, r.reconnectLeadMinutes)
        assertEquals(1_000_000L - 2 * SleepPlanner.MINUTE, EndActionRules.reconnectAt(r, 1_000_000L))
    }

    @Test
    fun `disconnect before an earbud alarm is kept when it can reconnect`() {
        val r = EndActionRules.resolve(SleepPlan(endAction = SleepEndAction.DISCONNECT), earbudAlarm, canReconnect = true)
        assertEquals(EndPlan(SleepEndAction.DISCONNECT, false, 2), r)
    }

    @Test
    fun `without a way to reconnect the alarm moves to the phone and the screen is told`() {
        for (action in listOf(SleepEndAction.DISCONNECT, SleepEndAction.POWER_OFF)) {
            val r = EndActionRules.resolve(SleepPlan(endAction = action), earbudAlarm, canReconnect = false)
            assertEquals(action, r.action)
            assertTrue(r.alarmMovesToPhone)
            assertNull(r.reconnectLeadMinutes)
            assertNull(EndActionRules.reconnectAt(r, 1_000_000L))
        }
    }

    @Test
    fun `power off falls back to disconnect, and stopping tries nothing`() {
        assertEquals(listOf(EndStep.POWER_OFF, EndStep.DISCONNECT), EndActionRules.steps(SleepEndAction.POWER_OFF))
        assertEquals(listOf(EndStep.DISCONNECT), EndActionRules.steps(SleepEndAction.DISCONNECT))
        assertTrue(EndActionRules.steps(SleepEndAction.STOP_SOUNDS).isEmpty())
    }

    @Test
    fun `the outcome follows what the headset can do`() {
        val yes = SleepAbility(true)
        val no = SleepAbility(false)
        assertEquals(EndOutcome.SWITCHES_OFF, EndActionRules.outcome(SleepEndAction.POWER_OFF, HeadsetAbilities(powerOff = yes, disconnect = yes)))
        assertEquals(EndOutcome.DISCONNECTS, EndActionRules.outcome(SleepEndAction.POWER_OFF, HeadsetAbilities(powerOff = no, disconnect = yes)))
        assertEquals(EndOutcome.STAYS_CONNECTED_CANT, EndActionRules.outcome(SleepEndAction.DISCONNECT, HeadsetAbilities(disconnect = no)))
        assertEquals(EndOutcome.STAYS_CONNECTED, EndActionRules.outcome(SleepEndAction.STOP_SOUNDS, HeadsetAbilities()))
        assertTrue(EndActionRules.abilityFor(SleepEndAction.STOP_SOUNDS, HeadsetAbilities()).available)
    }
}
