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
    /** Whether this build has a Pro EQ backend for [system] (Windows and Linux). The Equaliser page is listed only then. */
    val hasProEq: Boolean = true,
    /**
     * Whether something on this system carries out the night schedule. Computers do (the tray process);
     * a phone keeps the Night page hidden until its own runner exists, so it never shows a schedule that nothing runs.
     */
    val hasNightRunner: Boolean = !system.phone,
    /** Whether this build has a sleep engine for [system] (Android now; an iPhone build joins when its engine does). The Sleep tab is listed only then. */
    val hasSleep: Boolean = true,
) {

    /** Whether [page] belongs on this system at all: the Equaliser needs a way to filter the sound, Sleep is for phones. */
    fun existsOn(page: DeskPage): Boolean = when (page) {
        DeskPage.Equaliser -> system != SystemKind.Mac && system != SystemKind.IPhone && hasProEq
        DeskPage.Sleep -> system.phone && hasSleep
        DeskPage.Night -> hasNightRunner
        else -> true
    }

    /** A feature page with content, on a system where it belongs. */
    fun shows(page: DeskPage): Boolean = page in ready && existsOn(page)

    /**
     * The sidebar's pages, top to bottom (Ctrl+1 is the first). [sound] and [settings] say whether the
     * headphones give those pages anything; the rest wait for their feature. [devices] is whether the
     * headphones report paired devices and [tools] whether this system has a tool to show; both pages
     * need their feature shipped as well.
     */
    fun deskPages(sound: Boolean, settings: Boolean, devices: Boolean = true, tools: Boolean = true): List<DeskPage> = buildList {
        if (sound) add(DeskPage.Sound)
        for (page in listOf(DeskPage.Equaliser, DeskPage.Night)) if (shows(page)) add(page)
        if (devices && shows(DeskPage.Devices)) add(DeskPage.Devices)
        if (settings) add(DeskPage.Settings)
        if (tools && shows(DeskPage.Tools)) add(DeskPage.Tools)
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
        val ShippedPages: Set<DeskPage> = setOf(DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Tools, DeskPage.Sleep)
    }
}
