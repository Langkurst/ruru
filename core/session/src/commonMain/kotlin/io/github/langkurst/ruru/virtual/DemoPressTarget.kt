package io.github.langkurst.ruru.virtual

/**
 * A virtual unit that demo mode's "press a control" buttons can press: it then does and pushes
 * what real headphones would, so the press reaches the app the way a real one does.
 */
interface DemoPressTarget {
    /** Play / pause on the headset itself. */
    fun pressPlayPause()

    /** The headset's own volume control, [delta] steps. */
    fun swipeVolume(delta: Int)

    /** The headset's main programmable button, pressed as it is configured. */
    fun pressMainButton()
}
