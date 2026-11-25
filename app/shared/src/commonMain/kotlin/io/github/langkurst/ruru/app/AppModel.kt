package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.HeadsetPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The pages of the wide desktop layout. [Status] is the "no headphones" screen and is never remembered. */
enum class DeskPage { Sound, Settings, About, Status }

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
