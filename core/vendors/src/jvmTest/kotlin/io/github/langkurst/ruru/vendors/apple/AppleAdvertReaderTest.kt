package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.core.FormFactor
import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.HeadsetAdvert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleAdvertReaderTest {
    private var now = 1_000L
    private val apple = ContinuityAdvert.APPLE_COMPANY_ID

    private fun reader(name: String = "AirPods Pro") = AppleAdvertReader(name) { now }
    private fun adv(bytes: ByteArray, key: String, rssi: Int? = null) = HeadsetAdvert(apple, bytes, null, key, rssi)
    private fun AppleAdvertReader.read(vararg heard: HeadsetAdvert) = fold(heard.toList(), HeadphoneState())

    @Test fun `the battery of the advert that fits the paired name`() {
        val s = reader().read(adv(AppleFixtures.PRO2_NOT_FLIPPED, "a"))
        assertEquals(80, s.batteryPercent)
        assertEquals(true, s.isCharging)
        assertEquals(80, s.battery?.left?.percent)
        assertEquals(90, s.battery?.right?.percent)
        assertEquals(50, s.battery?.case?.percent)
        assertEquals(false, s.battery?.left?.charging)
        assertEquals(true, s.battery?.right?.charging)
        assertEquals(false, s.battery?.case?.charging)
    }

    @Test fun `another maker's adverts and other Apple messages are ignored`() {
        val s = reader().read(
            HeadsetAdvert(0x0006, AppleFixtures.PRO2_NOT_FLIPPED, null, "ms"),
            adv(AppleFixtures.NEARBY_INFO, "n"),
            adv(AppleFixtures.IBEACON, "i"),
        )
        assertNull(s.batteryPercent)
        assertNull(s.battery)
    }

    @Test fun `a stranger's AirPods of another model are never shown`() {
        val s = reader("AirPods Pro").read(adv(AppleFixtures.MAX, "max"), adv(AppleFixtures.AIRPODS2_ONE_BUD, "plain"))
        assertNull(s.batteryPercent)
    }

    @Test fun `AirPods Max read as one battery and a headphones form factor`() {
        val s = reader("AirPods Max").read(adv(AppleFixtures.MAX, "m"))
        assertEquals(60, s.batteryPercent)
        assertEquals(60, s.battery?.overall?.percent)
        assertNull(s.battery?.left)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
    }

    @Test fun `weak adverts are far away and ignored when the signal is known`() {
        val s = reader().read(adv(AppleFixtures.PRO2_NOT_FLIPPED, "far", rssi = -90))
        assertNull(s.batteryPercent)
    }

    @Test fun `two that fit - the clearly stronger one wins, a tie or no signal shows nothing`() {
        val mine = adv(AppleFixtures.PRO2_NOT_FLIPPED, "mine", rssi = -45)
        val theirs = adv(AppleFixtures.PRO_FLIPPED, "theirs", rssi = -62)
        assertEquals(80, reader().read(mine, theirs).batteryPercent)

        val tie = reader().read(adv(AppleFixtures.PRO2_NOT_FLIPPED, "x", rssi = -50), adv(AppleFixtures.PRO_FLIPPED, "y", rssi = -50))
        assertNull(tie.batteryPercent)
        // Without any signal strengths there is nothing to choose by either.
        assertNull(reader().read(adv(AppleFixtures.PRO2_NOT_FLIPPED, "x"), adv(AppleFixtures.PRO_FLIPPED, "y")).batteryPercent)
    }

    @Test fun `once an advertiser is followed it stays the one, even when another is louder`() {
        val r = reader()
        assertEquals(80, r.read(adv(AppleFixtures.PRO2_NOT_FLIPPED, "mine")).batteryPercent)
        now += 20_000
        val s = r.read(adv(AppleFixtures.PRO_FLIPPED, "theirs", rssi = -40), adv(AppleFixtures.PRO2_NOT_FLIPPED, "mine", rssi = -60))
        assertEquals(80, s.batteryPercent)
    }

    @Test fun `the last reading is kept between windows and dropped when it is stale`() {
        val r = reader()
        r.read(adv(AppleFixtures.PRO2_NOT_FLIPPED, "a"))
        now += AppleAdvertReader.STALE_MS - 1
        assertEquals(80, r.fold(emptyList(), HeadphoneState()).batteryPercent)
        now += 2
        val gone = r.fold(emptyList(), HeadphoneState())
        assertNull(gone.batteryPercent)
        assertNull(gone.battery)
        assertNull(gone.isCharging)
    }

    @Test fun `a part with no reading is no battery, not zero`() {
        val s = reader("AirPods").read(adv(AppleFixtures.AIRPODS2_ONE_BUD, "a"))
        assertEquals(70, s.battery?.left?.percent)
        assertNull(s.battery?.right)
        assertNull(s.battery?.case)
    }
}
