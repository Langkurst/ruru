package io.github.langkurst.ruru.android.service

import io.github.langkurst.ruru.app.AppState
import io.github.langkurst.ruru.app.Phase

/** What the notification says. Pure, so the choice is tested; the words themselves are Android resources. */
sealed interface NotificationLine {
    /** "Ruru is connected to <name>". */
    data class Connected(val name: String) : NotificationLine

    /** "Ruru is waiting for your headphones". */
    data object Waiting : NotificationLine

    companion object {
        fun of(state: AppState): NotificationLine =
            of(state.phase, state.headset.info?.customName?.takeIf { it.isNotBlank() } ?: state.headset.connection.deviceName)

        /** [name] is what the headphones are called; a blank one reads as no name. */
        fun of(phase: Phase, name: String?): NotificationLine {
            val shown = name?.takeIf { it.isNotBlank() }
            return if (phase == Phase.Connected && shown != null) Connected(shown) else Waiting
        }
    }
}
