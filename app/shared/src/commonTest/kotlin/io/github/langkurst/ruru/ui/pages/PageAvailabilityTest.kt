package io.github.langkurst.ruru.ui.pages

import io.github.langkurst.ruru.app.DeskPage
import io.github.langkurst.ruru.app.PhoneTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageAvailabilityTest {
    private val everything = DeskPage.entries.toSet()

    @Test
    fun `this build ships the Equaliser, Night, Devices and Tools, the Equaliser listed only where a Pro EQ backend exists`() {
        assertEquals(setOf(DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Tools), PageAvailability.ShippedPages, "a feature that ships a page updates this test with it")
        val windows = PageAvailability(system = SystemKind.Windows)
        assertEquals(
            listOf(DeskPage.Sound, DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Settings, DeskPage.Tools),
            windows.deskPages(sound = true, settings = true),
        )
        assertEquals(listOf(PhoneTab.Headphones, PhoneTab.Sound, PhoneTab.More), windows.phoneTabs())
        // A system with no backend yet (Linux and Android until theirs is built) keeps today's navigation.
        val noBackend = PageAvailability(system = SystemKind.Linux, hasProEq = false)
        assertEquals(listOf(DeskPage.Sound, DeskPage.Night, DeskPage.Devices, DeskPage.Settings, DeskPage.Tools), noBackend.deskPages(sound = true, settings = true))
        assertEquals(listOf(PhoneTab.Headphones, PhoneTab.More), noBackend.phoneTabs())
    }

    @Test
    fun `Devices and Tools appear only when they have content`() {
        val windows = PageAvailability(system = SystemKind.Windows)
        // No paired devices reported: no Devices page. No tool on this system: no Tools page.
        assertEquals(
            listOf(DeskPage.Sound, DeskPage.Equaliser, DeskPage.Night, DeskPage.Settings),
            windows.deskPages(sound = true, settings = true, devices = false, tools = false),
        )
        assertEquals(listOf(DeskPage.Equaliser, DeskPage.Night, DeskPage.Tools), windows.deskPages(sound = false, settings = false, devices = false, tools = true))
    }

    @Test
    fun `Sound and Settings follow what the headphones give`() {
        val none = PageAvailability(system = SystemKind.Windows)
        assertEquals(listOf(DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Tools), none.deskPages(sound = false, settings = false))
        assertEquals(listOf(DeskPage.Equaliser, DeskPage.Night, DeskPage.Devices, DeskPage.Settings, DeskPage.Tools), none.deskPages(sound = false, settings = true))
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
    fun `Devices and Tools are on every system and Sleep only on phones`() {
        for (system in SystemKind.entries) {
            val pages = PageAvailability(everything, system)
            for (page in listOf(DeskPage.Devices, DeskPage.Tools)) assertTrue(pages.shows(page), "$page on $system")
            assertEquals(system.phone, pages.shows(DeskPage.Sleep), "Sleep on $system")
        }
    }

    @Test
    fun `Night is on computers, and on a phone only once that phone has a runner for it`() {
        for (system in SystemKind.entries) {
            assertEquals(!system.phone, PageAvailability(everything, system).shows(DeskPage.Night), "Night on $system without a runner")
            assertTrue(PageAvailability(everything, system, hasNightRunner = true).shows(DeskPage.Night), "Night on $system with a runner")
            assertTrue(!PageAvailability(everything, system, hasNightRunner = false).shows(DeskPage.Night), "Night on $system with the runner said to be missing")
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
