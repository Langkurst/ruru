package io.github.langkurst.ruru.ui.pages

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.PhoneTab

/** The kind of system the screen is drawn for, so a panel can say what is not possible there. */
val LocalSystemKind = staticCompositionLocalOf { SystemKind.Other }

/** The kind of system Ruru runs on, from the platform's name. Demo mode keeps the real system's kind. */
enum class SystemKind {
    Windows, Linux, Mac, Android, IPhone, Other;

    val phone: Boolean get() = this == Android || this == IPhone

    companion object {
        fun of(platformName: String): SystemKind = when (platformName) {
            "Windows" -> Windows
            "Linux" -> Linux
            "macOS" -> Mac
            "Android" -> Android
            "iPhone" -> IPhone
            else -> Other
        }
    }
}

/**
 * Which pages are shown. A page appears only when it has content: Sound and Settings from what the
 * headphones report (decided by the caller), every other page when its feature has put it in [ready]
 * and the page makes sense on [system]. A feature that finishes a page adds it to [ShippedPages]
 * and nothing else; until then its page file holds only a placeholder and the page stays out of the navigation.
 */
class PageAvailability(
    val ready: Set<DeskPage> = ShippedPages,
    val system: SystemKind = SystemKind.Other,
    /** Whether this build has a Pro EQ backend for [system] (Windows today). The Equaliser page is listed only then. */
    val hasProEq: Boolean = true,
) {

    /** Whether [page] belongs on this system at all: the Equaliser needs a way to filter the sound, Sleep is for phones. */
    fun existsOn(page: DeskPage): Boolean = when (page) {
        DeskPage.Equaliser -> system != SystemKind.Mac && system != SystemKind.IPhone && hasProEq
        DeskPage.Sleep -> system.phone
        else -> true
    }

    /** A feature page with content, on a system where it belongs. */
    fun shows(page: DeskPage): Boolean = page in ready && existsOn(page)

    /**
     * The sidebar's pages, top to bottom (Ctrl+1 is the first). [sound] and [settings] say whether the
     * headphones give those pages anything; the rest wait for their feature.
     */
    fun deskPages(sound: Boolean, settings: Boolean): List<DeskPage> = buildList {
        if (sound) add(DeskPage.Sound)
        for (page in listOf(DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices)) if (shows(page)) add(page)
        if (settings) add(DeskPage.Settings)
        if (shows(DeskPage.Tools)) add(DeskPage.Tools)
    }

    /**
     * The bottom bar of a phone or a narrow window: Headphones, then Sound, Sleep and More when they
     * have content. Headphones is always there; More holds the settings and About, so it is too.
     */
    fun phoneTabs(): List<PhoneTab> = buildList {
        add(PhoneTab.Headphones)
        if (shows(DeskPage.Equaliser)) add(PhoneTab.Sound)
        if (shows(DeskPage.Sleep)) add(PhoneTab.Sleep)
        add(PhoneTab.More)
    }

    companion object {
        /**
         * The pages that have content in this build. A feature milestone adds its page here, which is
         * the only change outside its own page file.
         */
        val ShippedPages: Set<DeskPage> = setOf(DeskPage.Equaliser)
    }
}
