package io.github.langkurst.ruru.sleep

/** One thing the end of the timer tries on the headset, in order. */
enum class EndStep { POWER_OFF, DISCONNECT }

/** How the end action will really play out on this headset, for the line above the timer. */
enum class EndOutcome {
    /** Sounds stop; the headset stays connected (chosen). */
    STAYS_CONNECTED,
    DISCONNECTS,
    SWITCHES_OFF,

    /** Disconnect or switch-off was chosen but this headset can't: it stays connected. */
    STAYS_CONNECTED_CANT,
}

/**
 * The end action once the alarm is taken into account: what to do, whether the alarm will have
 * to ring on the phone, and how long before the alarm to page the headset again.
 */
data class EndPlan(
    val action: SleepEndAction,
    /** The alarm can't ring in the earbuds after this; the screen says so before Start. */
    val alarmMovesToPhone: Boolean,
    /** Minutes before the earbud alarm to reconnect the headset; null = no reconnect planned. */
    val reconnectLeadMinutes: Int?,
)

/**
 * The end-action conflict rules (Earshot sleep-mode section 4.6). Switching the headset off or
 * disconnecting it can stop an earbud alarm from ringing. So when the next alarm is meant for the
 * earbuds:
 *  - "Switch off" is refused: a headset that switched itself off can't be woken, so it becomes
 *    "disconnect" with a reconnect [RECONNECT_LEAD_MINUTES] before the alarm, when the phone can
 *    page the headset again;
 *  - "Disconnect" is kept only when that same reconnect is possible;
 *  - when it isn't possible, the choice stands, the alarm will ring on the phone, and
 *    [EndPlan.alarmMovesToPhone] tells the screen to say so before Start.
 */
object EndActionRules {
    /** How long before an earbud alarm a disconnected headset is paged again. */
    const val RECONNECT_LEAD_MINUTES = 2

    fun resolve(plan: SleepPlan, nextAlarm: SleepAlarm?, canReconnect: Boolean): EndPlan {
        val action = plan.endAction
        val earbudAlarm = nextAlarm != null && nextAlarm.enabled && nextAlarm.output != AlarmOutput.PHONE
        if (action == SleepEndAction.STOP_SOUNDS || !earbudAlarm) return EndPlan(action, false, null)
        return if (canReconnect) {
            EndPlan(SleepEndAction.DISCONNECT, alarmMovesToPhone = false, reconnectLeadMinutes = RECONNECT_LEAD_MINUTES)
        } else {
            EndPlan(action, alarmMovesToPhone = true, reconnectLeadMinutes = null)
        }
    }

    /** What the end action tries, in order: switching off falls back to disconnecting. STOP_SOUNDS tries nothing. */
    fun steps(action: SleepEndAction): List<EndStep> = when (action) {
        SleepEndAction.STOP_SOUNDS -> emptyList()
        SleepEndAction.DISCONNECT -> listOf(EndStep.DISCONNECT)
        SleepEndAction.POWER_OFF -> listOf(EndStep.POWER_OFF, EndStep.DISCONNECT)
    }

    /** The end action as it will really play out on a headset with [abilities]. */
    fun outcome(action: SleepEndAction, abilities: HeadsetAbilities): EndOutcome = when (action) {
        SleepEndAction.STOP_SOUNDS -> EndOutcome.STAYS_CONNECTED
        SleepEndAction.DISCONNECT -> if (abilities.disconnect.available) EndOutcome.DISCONNECTS else EndOutcome.STAYS_CONNECTED_CANT
        SleepEndAction.POWER_OFF -> when {
            abilities.powerOff.available -> EndOutcome.SWITCHES_OFF
            abilities.disconnect.available -> EndOutcome.DISCONNECTS
            else -> EndOutcome.STAYS_CONNECTED_CANT
        }
    }

    /** Whether the chosen end action can happen at all on [abilities]. Stopping the sounds always works. */
    fun abilityFor(action: SleepEndAction, abilities: HeadsetAbilities): SleepAbility = when (action) {
        SleepEndAction.STOP_SOUNDS -> SleepAbility(true)
        SleepEndAction.DISCONNECT -> abilities.disconnect
        SleepEndAction.POWER_OFF -> abilities.powerOff
    }

    /** When to page the headset before an earbud alarm at [alarmAtMillis], or null when no reconnect is planned. */
    fun reconnectAt(plan: EndPlan, alarmAtMillis: Long): Long? =
        plan.reconnectLeadMinutes?.let { alarmAtMillis - it * SleepPlanner.MINUTE }
}
