package io.github.langkurst.ruru.app.eq

import io.github.langkurst.ruru.dsp.ProEqState
import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Pro EQ backend of this system can do right now. The page shows it as a status row with at
 * most one button ([NeedsSetup.action]); nothing is hidden behind a failure.
 */
sealed interface EqBackendStatus {
    /** The equaliser cannot run here, for [reason]. No button: nothing the person can press fixes it. */
    data class Unavailable(val reason: Words) : EqBackendStatus

    /** One step from the person is needed first. */
    data class NeedsSetup(val action: EqSetupAction) : EqBackendStatus

    /** Everything is in place and the equaliser is off. */
    data object Ready : EqBackendStatus

    /** The equaliser is running. */
    data object On : EqBackendStatus

    /** Whether the on/off switch can be used: it can unless the backend needs something first (a request for consent counts as switchable: switching on asks for it). */
    val switchable: Boolean
        get() = when (this) {
            Ready, On -> true
            is NeedsSetup -> action.kind == EqSetupKind.ALLOW
            is Unavailable -> false
        }
}

/** The kind of button a [EqSetupAction] is: what the page does when it is pressed. */
enum class EqSetupKind {
    /** Open [EqSetupAction.url] in the browser (the person goes online, Ruru does not). */
    OPEN_WEBSITE,

    /** The person agrees to what [EqSetupAction.message] says; the backend carries on. */
    ALLOW,

    /** Look again: the person has done what the message asks. */
    CHECK_AGAIN,
}

/** The one thing the person can do about a [EqBackendStatus.NeedsSetup]: a sentence and a button. */
data class EqSetupAction(val kind: EqSetupKind, val message: Words, val button: Words, val url: String? = null)

/**
 * The part of the system that filters the sound: Equalizer APO on Windows, later PipeWire on Linux and
 * Android's own effect. The page and [ProEqModel] only know this interface.
 */
interface EqBackend {
    val status: StateFlow<EqBackendStatus>

    /** Whether the backend has a limiter. Without one the preamp is held at the headroom the bands need. */
    val hasLimiter: Boolean

    /** A plain note shown under the status row (for example how to set the backend up), or null. */
    val setupNote: Words?

    /** Run [state] (switching on when [ProEqState.enabled], off when not). Never throws; a failure becomes [status]. */
    suspend fun apply(state: ProEqState)

    /** Stop filtering and undo what [apply] set up. Never throws. */
    suspend fun off()

    /** The person pressed the button of [action] (except [EqSetupKind.OPEN_WEBSITE], which the page opens itself). */
    fun perform(action: EqSetupAction)

    /** Look again at the system (the person may have installed or fixed something). */
    fun refresh()
}

/** The statuses more than one backend uses. */
object EqStatuses {
    const val APO_URL = "https://equalizerapo.com/"

    /** Equalizer APO is not installed. The button opens its website; Ruru downloads nothing. */
    fun apoNotInstalled(): EqBackendStatus = EqBackendStatus.NeedsSetup(
        EqSetupAction(EqSetupKind.OPEN_WEBSITE, Words("eq_apo_not_installed"), Words("eq_apo_get"), APO_URL),
    )
}
