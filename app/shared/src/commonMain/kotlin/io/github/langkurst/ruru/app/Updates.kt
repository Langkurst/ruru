package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the "Check for updates" button has got to. The screen draws one small card from it. */
sealed interface UpdateState {
    /** Nothing asked yet (or the card was put away). */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    /** [fraction] runs from 0 to 1. */
    data class Downloading(val version: String, val fraction: Float) : UpdateState

    /** Downloaded and checked; waiting for the person to choose Install and restart. */
    data class Ready(val version: String) : UpdateState

    data class Installing(val version: String) : UpdateState

    /** [reason] is a sentence in strings.xml; the default says the check itself did not work. */
    data class Failed(val reason: Words = Words("update_failed_check")) : UpdateState

    /** This system can't install by itself, so the download page was opened in the browser. */
    data object OpenedWebsite : UpdateState
}

/**
 * The Check for updates button. A person can only reach the internet through it: nothing here runs
 * until [check] is called. The desktop app downloads and installs; phones open the website.
 */
interface Updates {
    val state: StateFlow<UpdateState>

    /** The person pressed Check for updates. */
    fun check()

    /** The person pressed Install and restart (only after [UpdateState.Ready]). */
    fun installAndRestart()

    /** Put the card away (OK, Later). */
    fun dismiss()
}

/**
 * Phones, and any system that has no installer of its own: the button opens the download page in
 * the browser, which says whether this version is the newest. Ruru itself stays offline.
 * [open] shows a page in the browser and says whether it could.
 */
class WebsiteUpdates(private val appVersion: String, private val open: (String) -> Boolean) : Updates {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    override fun check() {
        _state.value = if (open("$WEBSITE_URL/?have=$appVersion")) UpdateState.OpenedWebsite else UpdateState.Idle
    }

    override fun installAndRestart() = Unit

    override fun dismiss() {
        _state.value = UpdateState.Idle
    }
}
