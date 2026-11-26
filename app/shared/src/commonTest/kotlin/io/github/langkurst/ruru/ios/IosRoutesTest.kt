package io.github.langkurst.ruru.ios

import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.VendorRegistry
import io.github.langkurst.ruru.vendors.jbl.JblVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IosRoutesTest {
    @Test
    fun onlyLeRoutesAreOffered() {
        assertEquals(setOf(RouteKind.BOSE_LE, RouteKind.GATT), IosRoutes.OFFERED)
        assertNull(IosRoutes.refusal(LinkRoute.BoseLe))
        assertNull(IosRoutes.refusal(LinkRoute.Gatt(JblVendor.HARMAN)))
    }

    @Test
    fun rfcommRoutesAreRefusedWithPlainWords() {
        val words = Words("problem_ios_classic_only")
        assertEquals(words, IosRoutes.refusal(LinkRoute.BoseRfcomm(listOf(2))))
        assertEquals(words, IosRoutes.refusal(LinkRoute.RfcommService("00001101-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun airPodsCannotBeListenedToOnIphone() {
        assertFalse(IosRoutes.LISTENS_FOR_ADVERTS)
        val apple = VendorRegistry.forFamily(DeviceFamily.APPLE_ADVERT)!!
        assertNull(apple.route(io.github.langkurst.ruru.link.HeadsetCandidate("k", "AirPods Pro", true), IosRoutes.OFFERED))
    }

    @Test
    fun jblGetsItsGattSpecAndBoseDoesNot() {
        assertEquals(JblVendor.HARMAN, IosRoutes.gattSpecFor("JBL LIVE660NC"))
        assertEquals(JblVendor.HARMAN, IosRoutes.gattSpecFor("JBL Tour One M2"))
        assertNull(IosRoutes.gattSpecFor("Bose QuietComfort Ultra Headphones"))
        assertNull(IosRoutes.gattSpecFor("Some Speaker"))
        assertNotNull(IosRoutes.OFFERED)
    }
}
