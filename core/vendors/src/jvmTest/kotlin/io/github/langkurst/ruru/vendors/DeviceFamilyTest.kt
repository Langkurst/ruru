package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.vendors.sony.SonyVendor
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceFamilyTest {
    private fun byName(name: String?) = DeviceFamily.forName(name)

    @Test
    fun `Bose names, and not QCY or a plain Ultra`() {
        for (n in listOf("Bose QC Ultra Headphones", "Bose QC35 II", "QC45", "QuietComfort Earbuds", "Test QC Ultra", "Bose NC 700")) {
            assertEquals(DeviceFamily.BOSE, byName(n), n)
        }
        for (n in listOf("QCY-T13", "Galaxy Buds2 Ultra", "Pixel Buds Pro", "", null)) {
            assertEquals(DeviceFamily.GENERIC, byName(n), n.toString())
        }
    }

    @Test
    fun `any name with Bose in it stays Bose, as in Ruru 0_2`() {
        assertEquals(DeviceFamily.BOSE, byName("MyBoseCans"))
    }

    @Test
    fun `other makers by name, each before Bose`() {
        assertEquals(DeviceFamily.SENNHEISER, byName("MOMENTUM 4"))
        assertEquals(DeviceFamily.SENNHEISER, byName("ACCENTUM Plus"))
        assertEquals(DeviceFamily.SENNHEISER, byName("MOMENTUM TW 4"))
        assertEquals(DeviceFamily.SONY, byName("WH-1000XM5"))
        assertEquals(DeviceFamily.SONY, byName("LE_WF-1000XM5"))
        assertEquals(DeviceFamily.GENERIC, byName("WH-1000XM4")) // MDR v1
        assertEquals(DeviceFamily.SOUNDCORE, byName("soundcore Liberty 4 NC"))
        assertEquals(DeviceFamily.SOUNDCORE, byName("Soundcore Sleep A30"))
        assertEquals(DeviceFamily.JBL, byName("JBL Tour One M2"))
        assertEquals(DeviceFamily.JBL, byName("LE-JBL LIVE660NC"))
        assertEquals(DeviceFamily.GENERIC, byName("JBL Tune 770NC")) // Airoha, not H-AA
        assertEquals(DeviceFamily.BRAGI, byName("Crusher 1080 by Bose"))
        assertEquals(DeviceFamily.BRAGI, byName("Skullcandy Method-360_ANC"))
        assertEquals(DeviceFamily.GENERIC, byName("Method")) // a plain "Method" is not enough
        assertEquals(DeviceFamily.APPLE_ADVERT, byName("AirPods Pro"))
        assertEquals(DeviceFamily.APPLE_ADVERT, byName("Powerbeats Pro"))
        // A Sennheiser named with "Bose" in it would still be a Sennheiser: it must never get the BMAP walk.
        assertEquals(DeviceFamily.SENNHEISER, byName("MOMENTUM 4 (not a Bose)"))
    }

    @Test
    fun `override, then learned, then name`() {
        assertEquals(DeviceFamily.SONY, DeviceFamily.resolve(override = "sony", learned = DeviceFamily.BOSE, name = "Bose QC45"))
        assertEquals(DeviceFamily.BOSE, DeviceFamily.resolve(override = null, learned = DeviceFamily.BOSE, name = "Alex's headphones"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve(override = null, learned = null, name = "Alex's headphones"))
        assertEquals(DeviceFamily.BOSE, DeviceFamily.resolve(override = "nonsense", learned = null, name = "Bose QC45"))
    }

    @Test
    fun `a cached Soundcore service names an unknown headset, unless the name is JBL's`() {
        val a30 = listOf("0000110b-0000-1000-8000-00805f9b34fb", "0cf12d31-fac3-4553-bd80-d6832e7d1301")
        assertEquals(DeviceFamily.SOUNDCORE, DeviceFamily.resolve(null, null, "My earbuds", a30))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve(null, null, "JBL something", listOf("0cf12d31-fac3-4553-bd80-d6832e7b3850")))
        // A suffix no driven model publishes is not enough.
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve(null, null, "My earbuds", listOf("0cf12d31-fac3-4553-bd80-d6832e7fffff")))
    }

    @Test
    fun `a Sony whose record shows only MDR v1 is refused`() {
        val v1 = listOf(SonyVendor.SERVICE_V1)
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve(null, null, "WH-1000XM5", v1))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.resolve(null, null, "WH-1000XM5", listOf(SonyVendor.reversed(SonyVendor.SERVICE_V1))))
        assertEquals(DeviceFamily.SONY, DeviceFamily.resolve(null, null, "WH-1000XM5", v1 + SonyVendor.SERVICE_V2))
        assertEquals(DeviceFamily.SONY, DeviceFamily.resolve(null, null, "WH-1000XM5", emptyList()))
    }
}
