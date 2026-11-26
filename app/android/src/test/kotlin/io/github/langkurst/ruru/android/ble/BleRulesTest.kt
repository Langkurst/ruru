package io.github.langkurst.ruru.android.ble

import io.github.langkurst.ruru.link.LinkRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleRulesTest {
    private val spp = "00001101-0000-1000-8000-00805f9b34fb"
    private val vendor = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    @Test
    fun `a service the system's list lacks is refused, an empty list is not`() {
        assertTrue(BondedRules.serviceMissing(listOf(spp, "0000110b-0000-1000-8000-00805f9b34fb"), vendor))
        assertFalse(BondedRules.serviceMissing(listOf(spp, vendor), vendor))
        assertFalse(BondedRules.serviceMissing(listOf(spp, vendor.uppercase()), vendor))
        assertFalse(BondedRules.serviceMissing(emptyList(), vendor))
    }

    @Test
    fun `an RFCOMM route is named by a lower-case UUID, never a channel number`() {
        assertFailsWith<IllegalArgumentException> { LinkRoute.RfcommService("2") }
        assertFailsWith<IllegalArgumentException> { LinkRoute.RfcommService(vendor.uppercase()) }
        assertEquals(vendor, LinkRoute.RfcommService(vendor).uuid)
    }

    @Test
    fun `audio devices are found by class or by an audio service`() {
        assertTrue(BondedRules.isAudio(BondedRules.MAJOR_AUDIO_VIDEO, emptyList()))
        assertTrue(BondedRules.isAudio(null, listOf("0000110B-0000-1000-8000-00805f9b34fb")))
        assertTrue(BondedRules.isAudio(0x0100, listOf("0000111e-0000-1000-8000-00805f9b34fb")))
        assertFalse(BondedRules.isAudio(0x0100, listOf(spp)))
        assertFalse(BondedRules.isAudio(null, emptyList()))
    }

    @Test
    fun `an LE Audio twin is dropped only when its classic twin is bonded`() {
        assertEquals(listOf("Bose QC Ultra"), BondedRules.dropLeTwins(listOf("Bose QC Ultra", "LE-Bose QC Ultra")))
        assertEquals(listOf("LE-Other Buds", "Bose QC Ultra"), BondedRules.dropLeTwins(listOf("LE-Other Buds", "Bose QC Ultra")))
        assertEquals(listOf("A", "A"), BondedRules.dropLeTwins(listOf("A", "A")))
    }

    @Test
    fun `an advert name fits its headset by exact name or its LE twin forms`() {
        assertTrue(LeAdvertMatch.fits("Live Buds 3-LE", "Live Buds 3"))
        assertTrue(LeAdvertMatch.fits("live buds 3", "Live Buds 3"))
        assertTrue(LeAdvertMatch.fits("LE-Live Buds 3", "Live Buds 3"))
        assertFalse(LeAdvertMatch.fits("Live Buds 3 Pro-LE", "Live Buds 3"))
        assertFalse(LeAdvertMatch.fits(null, "Live Buds 3"))
        assertFalse(LeAdvertMatch.fits("Bud", "Bud"))
        assertTrue("Live Buds 3_LE" in LeAdvertMatch.candidateNames("Live Buds 3"))
        assertTrue("LE Live Buds 3" in LeAdvertMatch.candidateNames("Live Buds 3"))
    }

    @Test
    fun `a scan needs the Nearby devices permission on Android 12 and the location rules before`() {
        fun missing(sdk: Int, scan: Boolean = false, fine: Boolean = false, coarse: Boolean = false, on: Boolean = true) =
            BleScanAccess.missing(sdk, scan, fine, coarse, on)
        assertNull(missing(33, scan = true))
        assertTrue(missing(33)!!.contains("Nearby devices"))
        assertTrue(missing(30)!!.contains("precise location"))
        assertNull(missing(30, fine = true))
        assertTrue(missing(30, fine = true, on = false)!!.contains("location turned on"))
        assertTrue(missing(28)!!.contains("Android 9"))
        assertNull(missing(28, coarse = true))
    }

    @Test
    fun `only Apple's proximity pairing adverts are kept`() {
        assertTrue(AdvertRules.isProximityPairing(byteArrayOf(0x07, 0x19, 0x01)))
        assertFalse(AdvertRules.isProximityPairing(byteArrayOf(0x10, 0x05)))
        assertFalse(AdvertRules.isProximityPairing(byteArrayOf()))
        assertFalse(AdvertRules.isProximityPairing(null))
    }

    @Test
    fun `an advertiser's key is stable per run, differs between runs and hides the address`() {
        val a = "AA:BB:CC:DD:EE:FF"
        val salt1 = ByteArray(16) { 1 }
        val salt2 = ByteArray(16) { 2 }
        assertEquals(AdvertRules.key(a, salt1), AdvertRules.key(a.lowercase(), salt1))
        assertNotEquals(AdvertRules.key(a, salt1), AdvertRules.key(a, salt2))
        assertNotEquals(AdvertRules.key(a, salt1), AdvertRules.key("AA:BB:CC:DD:EE:00", salt1))
        val key = AdvertRules.key(a, salt1)
        assertEquals(16, key.length)
        assertFalse(key.contains("aabbccddeeff"))
    }
}
