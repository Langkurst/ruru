package io.github.langkurst.ruru.ui.pages

import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.PhoneTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageAvailabilityTest {
    private val everything = DeskPage.entries.toSet()

    @Test
    fun `this build shows only Sound and Settings, and a bar of Headphones and More`() {
        assertEquals(emptySet(), PageAvailability.ShippedPages, "a feature that ships a page updates this test with it")
        val shipped = PageAvailability(system = SystemKind.Windows)
        assertEquals(listOf(DeskPage.Sound, DeskPage.Settings), shipped.deskPages(sound = true, settings = true))
        assertEquals(listOf(PhoneTab.Headphones, PhoneTab.More), shipped.phoneTabs())
    }

    @Test
    fun `Sound and Settings follow what the headphones give`() {
        val none = PageAvailability(system = SystemKind.Windows)
        assertEquals(emptyList(), none.deskPages(sound = false, settings = false))
        assertEquals(listOf(DeskPage.Settings), none.deskPages(sound = false, settings = true))
    }

    @Test
    fun `with every feature ready the sidebar has six pages in order`() {
        val all = PageAvailability(everything, SystemKind.Windows)
        assertEquals(
            listOf(DeskPage.Sound, DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Settings, DeskPage.Tools),
            all.deskPages(sound = true, settings = true),
        )
    }

    @Test
    fun `the Equaliser is not on a Mac or an iPhone`() {
        for (system in SystemKind.entries) {
            val shown = PageAvailability(everything, system).shows(DeskPage.Equaliser)
            assertEquals(system != SystemKind.Mac && system != SystemKind.IPhone, shown, "$system")
        }
        assertTrue(DeskPage.Equaliser !in PageAvailability(everything, SystemKind.Mac).deskPages(true, true))
    }

    @Test
    fun `Night, Devices and Tools are on every system and Sleep only on phones`() {
        for (system in SystemKind.entries) {
            val pages = PageAvailability(everything, system)
            for (page in listOf(DeskPage.Night, DeskPage.Devices, DeskPage.Tools)) assertTrue(pages.shows(page), "$page on $system")
            assertEquals(system.phone, pages.shows(DeskPage.Sleep), "Sleep on $system")
        }
    }

    @Test
    fun `a page not marked ready stays hidden even where it belongs`() {
        val pages = PageAvailability(setOf(DeskPage.Night), SystemKind.Linux)
        assertEquals(listOf(DeskPage.Sound, DeskPage.Night, DeskPage.Settings), pages.deskPages(true, true))
    }

    @Test
    fun `the bottom bar has up to four tabs and each appears only with content`() {
        assertEquals(
            listOf(PhoneTab.Headphones, PhoneTab.Sound, PhoneTab.Sleep, PhoneTab.More),
            PageAvailability(everything, SystemKind.Android).phoneTabs(),
        )
        // The iPhone has no Pro EQ, so no Sound tab; a narrow Windows window has no Sleep.
        assertEquals(listOf(PhoneTab.Headphones, PhoneTab.Sleep, PhoneTab.More), PageAvailability(everything, SystemKind.IPhone).phoneTabs())
        assertEquals(listOf(PhoneTab.Headphones, PhoneTab.Sound, PhoneTab.More), PageAvailability(everything, SystemKind.Windows).phoneTabs())
        assertEquals(listOf(PhoneTab.Headphones, PhoneTab.More), PageAvailability(everything, SystemKind.Mac).phoneTabs())
        for (system in SystemKind.entries) assertTrue(PageAvailability(everything, system).phoneTabs().size <= 4)
    }

    @Test
    fun `the system comes from the platform name`() {
        assertEquals(SystemKind.Mac, SystemKind.of("macOS"))
        assertEquals(SystemKind.IPhone, SystemKind.of("iPhone"))
        assertEquals(SystemKind.Other, SystemKind.of("Demo"))
    }
}
