package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.ListeningMode

/**
 * The modes in a quick list (the tray menu): the favourites when the headphones have favourites and
 * any are set, otherwise every mode that is built in or has been set up.
 */
fun quickModes(h: HeadphoneState): List<ListeningMode> {
    val usable = h.modes.filter { it.isPreset || (it.isConfigured && it.name.isNotBlank()) }
    val favourites = usable.filter { it.isFavorite }
    return if (h.capabilities.favoriteModes && favourites.isNotEmpty()) favourites else usable
}
