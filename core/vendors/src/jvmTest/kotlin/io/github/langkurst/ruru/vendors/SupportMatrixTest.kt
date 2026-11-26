package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.core.ConnectionState
import io.github.langkurst.ruru.core.ConnectionStatus
import io.github.langkurst.ruru.core.HeadphoneCapabilities
import io.github.langkurst.ruru.core.DeviceInfo
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.SupportTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportMatrixTest {
    private val all = SupportMatrix.rows.flatMap { row -> row.cells.map { (system, cell) -> Triple(row.family, system, cell) } }

    @Test fun `exactly one cell is Tested, the 1st Gen on Windows`() {
        val tested = all.filter { it.third.level == SupportLevel.TESTED }
        assertEquals(listOf(Pair(DeviceFamily.BOSE, SupportSystem.WINDOWS)), tested.map { it.first to it.second })
        assertEquals(SupportSystem.WINDOWS, SupportMatrix.TESTED_SYSTEM)
    }

    @Test fun `every maker has a cell for every system, and every driven maker has a row`() {
        assertEquals(
            setOf(DeviceFamily.BOSE, DeviceFamily.SONY, DeviceFamily.SENNHEISER, DeviceFamily.SOUNDCORE, DeviceFamily.JBL, DeviceFamily.BRAGI, DeviceFamily.APPLE_ADVERT),
            SupportMatrix.rows.map { it.family }.toSet(),
        )
        assertEquals(VendorRegistry.vendors.map { it.family }.toSet(), SupportMatrix.rows.map { it.family }.toSet())
        for (row in SupportMatrix.rows) assertEquals(SupportSystem.entries.toSet(), row.cells.keys, row.brand)
    }

    @Test fun `Not possible always comes with its reason`() {
        for ((family, system, cell) in all) {
            assertEquals(cell.level == SupportLevel.NOT_POSSIBLE, cell.why != null, "$family $system")
        }
        assertEquals(SupportLevel.NOT_POSSIBLE, SupportMatrix.cell(DeviceFamily.SONY, SupportSystem.IPHONE)!!.level)
        assertEquals(SupportLevel.NOT_POSSIBLE, SupportMatrix.cell(DeviceFamily.APPLE_ADVERT, SupportSystem.MACOS)!!.level)
        assertEquals(SupportLevel.BATTERY_ONLY, SupportMatrix.cell(DeviceFamily.APPLE_ADVERT, SupportSystem.WINDOWS)!!.level)
        assertEquals(SupportLevel.EXPERIMENTAL, SupportMatrix.cell(DeviceFamily.JBL, SupportSystem.IPHONE)!!.level)
    }

    @Test fun `a maker that opens a Bluetooth service is not possible on an iPhone, and every other cell of those makers is Experimental`() {
        for (family in listOf(DeviceFamily.SONY, DeviceFamily.SENNHEISER, DeviceFamily.SOUNDCORE, DeviceFamily.BRAGI)) {
            for (system in SupportSystem.entries) {
                val level = SupportMatrix.cell(family, system)!!.level
                assertEquals(if (system == SupportSystem.IPHONE) SupportLevel.NOT_POSSIBLE else SupportLevel.EXPERIMENTAL, level, "$family $system")
            }
        }
    }

    @Test fun `the table as Markdown has one line per maker and the words people read`() {
        val md = SupportMatrix.markdown().lines().filter { it.isNotBlank() }
        assertEquals(2 + SupportMatrix.rows.size, md.size)
        assertTrue(md[0].contains("Windows") && md[0].contains("iPhone"))
        assertTrue(md.any { it.startsWith("| Bose | Tested |") })
        assertTrue(md.any { it.startsWith("| Sony |") && it.endsWith("| Not possible |") })
        assertFalse(md.drop(2).any { it.contains("Tested") && !it.startsWith("| Bose") })
    }

    @Test fun `platform names match the support systems`() {
        assertEquals(SupportSystem.MACOS, SupportSystem.ofPlatform("macOS"))
        assertEquals(SupportSystem.IPHONE, SupportSystem.ofPlatform("iPhone"))
        assertNull(SupportSystem.ofPlatform("Demo"))
    }

    // ---- the tier the chip shows

    private fun connected(caps: HeadphoneCapabilities) = HeadphoneState(
        connection = ConnectionState(status = ConnectionStatus.CONNECTED, deviceName = "x"),
        info = DeviceInfo(modelName = "Model"),
        capabilities = caps,
    )

    @Test fun `a tested model is Tested on Windows and Experimental anywhere else`() {
        val s = connected(HeadphoneCapabilities(tested = true))
        assertEquals(SupportTier.TESTED, SupportTier.of(s, "Windows"))
        for (other in listOf("macOS", "Linux", "Android", "iPhone", "Demo")) assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(s, other), other)
    }

    @Test fun `an untested model is Experimental everywhere, and battery only stays battery only`() {
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(connected(HeadphoneCapabilities(tested = false)), "Windows"))
        val battery = connected(HeadphoneCapabilities(ownSettings = false, batteryOnly = true))
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(battery, "Windows"))
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(battery, "Linux"))
    }

    @Test fun `no tier while nothing is connected`() {
        assertNull(SupportTier.of(HeadphoneState(), "Windows"))
    }
}
