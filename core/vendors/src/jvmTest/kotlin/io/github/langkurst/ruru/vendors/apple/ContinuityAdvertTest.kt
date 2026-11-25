package io.github.langkurst.ruru.vendors.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Earshot's ContinuityAdvertTest (MIT), on the hex fixtures of [AppleFixtures]. */
class ContinuityAdvertTest {
    @Test fun `not flipped - low nibble is the left bud`() {
        val a = assertNotNull(ContinuityAdvert.parse(AppleFixtures.PRO2_NOT_FLIPPED))
        assertEquals("AirPods Pro (2nd generation)", a.modelName)
        assertEquals(80, a.leftPercent)
        assertEquals(90, a.rightPercent)
        assertEquals(50, a.casePercent)
        assertFalse(a.leftCharging)
        assertTrue(a.rightCharging)
        assertFalse(a.caseCharging)
        assertEquals(80, a.headlinePercent)
        assertTrue(a.charging)
    }

    @Test fun `flipped - high nibble is the left bud, charge bits swap`() {
        val a = assertNotNull(ContinuityAdvert.parse(AppleFixtures.PRO_FLIPPED))
        assertEquals(30, a.leftPercent)
        assertEquals(100, a.rightPercent)
        assertEquals(80, a.casePercent)
        assertTrue(a.leftCharging)
        assertFalse(a.rightCharging)
        assertTrue(a.caseCharging)
    }

    @Test fun `15 means no reading, the case in particular when the lid is shut`() {
        val a = assertNotNull(ContinuityAdvert.parse(AppleFixtures.AIRPODS2_ONE_BUD))
        assertEquals(70, a.leftPercent)
        assertNull(a.rightPercent)
        assertNull(a.casePercent)
        assertEquals(70, a.headlinePercent)
    }

    @Test fun `AirPods Max report one battery`() {
        val a = assertNotNull(ContinuityAdvert.parse(AppleFixtures.MAX))
        assertTrue(a.isHeadphones)
        assertEquals(60, a.headlinePercent)
    }

    @Test fun `other Continuity messages and short data are ignored`() {
        assertNull(ContinuityAdvert.parse(null))
        assertNull(ContinuityAdvert.parse(byteArrayOf(0x07, 0x19, 0x01)))
        assertNull(ContinuityAdvert.parse(AppleFixtures.NEARBY_INFO))
        assertNull(ContinuityAdvert.parse(AppleFixtures.IBEACON))
    }

    @Test fun `an advert only counts for a headset whose name fits its model`() {
        assertTrue(AppleModels.fitsName(0x1420, "AirPods Pro"))
        assertFalse(AppleModels.fitsName(0x0F20, "AirPods Pro")) // someone else's plain AirPods
        assertFalse(AppleModels.fitsName(0x0A20, "AirPods Pro"))
        assertTrue(AppleModels.fitsName(0x0A20, "AirPods Max"))
        assertTrue(AppleModels.fitsName(0x0B20, "Powerbeats Pro"))
        assertFalse(AppleModels.fitsName(0x0B20, "AirPods"))
        assertTrue(AppleModels.fitsName(0x7777, "AirPods")) // unknown model: allowed
    }

    @Test fun `the fixtures are the length of a real advert and every model has a name`() {
        val all = listOf(
            AppleFixtures.PRO2_NOT_FLIPPED, AppleFixtures.PRO_FLIPPED, AppleFixtures.AIRPODS2_ONE_BUD,
            AppleFixtures.MAX, AppleFixtures.POWERBEATS_PRO,
        )
        for (f in all) {
            assertEquals(27, f.size)
            assertNotNull(ContinuityAdvert.parse(f)?.modelName)
        }
    }
}
