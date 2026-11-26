package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.app.night.NightHeadset
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

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
    /** Which maker's simulated headset demo mode shows. Chosen by a hidden setting or the snapshot task, never in the UI. */
    private val demoFamily: DeviceFamily = DeviceFamily.BOSE,
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

    /**
     * The headset the night schedule drives: whichever controller is running now, so turning demo mode
     * on or off moves the schedule between the simulated headset and the real one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun nightHeadset(): NightHeadset = object : NightHeadset {
        override val headset: Flow<HeadphoneState> = current.flatMapLatest { c -> c.state.map { it.headset } }
        override suspend fun send(command: HeadsetCommand): Boolean = current.value.send(command)
    }

    private fun create(demo: Boolean) = HeadsetController(if (demo) DemoHeadsets.platform(demoFamily) else platform, scope, demo)
}
