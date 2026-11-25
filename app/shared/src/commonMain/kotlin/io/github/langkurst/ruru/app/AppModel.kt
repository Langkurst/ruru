package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.HeadsetPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The pages of the app, in sidebar order (docs/DESIGN.md, "Pages"). [Sleep] and [More] are the phone's
 * tabs of the same names. [Status] is the "no headphones" screen and is never remembered.
 */
enum class DeskPage { Sound, Equaliser, Night, Devices, Settings, Tools, Sleep, More, About, Status }

/** The tabs of the bottom bar on a phone or a narrow window. */
enum class PhoneTab { Headphones, Sound, Sleep, More }

/** Holds the running controller and swaps it when the user turns demo mode on or off. */
class AppModel(
    private val platform: HeadsetPlatform,
    private val scope: CoroutineScope,
    startInDemo: Boolean = false,
) {
    private val current = MutableStateFlow(create(startInDemo))
    val controller: StateFlow<HeadsetController> = current

    /** The page the wide layout shows. It lives here, not in the window, so closing and opening the window keeps it. */
    val page = MutableStateFlow(DeskPage.Sound)

    /** The tab the bottom bar shows (phones and narrow windows). Kept here for the same reason as [page]. */
    val tab = MutableStateFlow(PhoneTab.Headphones)

    /** The name of the real system's platform ("Windows", "Android"...), also while the demo is on. */
    val platformName: String get() = platform.name

    fun start() = current.value.start()

    fun setDemo(on: Boolean) {
        val old = current.value
        if (old.demo == on) return
        old.stop()
        current.value = create(on).also { it.start() }
    }

    fun stop() = current.value.stop()

    private fun create(demo: Boolean) = HeadsetController(if (demo) DemoPlatform() else platform, scope, demo)
}
