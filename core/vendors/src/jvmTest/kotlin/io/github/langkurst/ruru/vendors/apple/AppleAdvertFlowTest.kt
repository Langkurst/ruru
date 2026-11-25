package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.AdvertVendor
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.VendorRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Adverts through the vendor into [HeadphoneState], the way the controller does it, with nothing opened. */
class AppleAdvertFlowTest {
    @Test fun `the demo's adverts show the paired pair's battery and none of the strangers'`() = runBlocking {
        val platform = VirtualAirPods(left = 80, right = 90, case = 60, caseCharging = true)
        val vendor = assertIs<AdvertVendor>(VendorRegistry.forFamily(DeviceFamily.APPLE_ADVERT))
        val candidate = platform.headsets().single()
        assertEquals(DeviceFamily.APPLE_ADVERT, DeviceFamily.forName(candidate.name))
        val state = vendor.reader(candidate.name).fold(platform.adverts(), HeadphoneState())
        assertEquals(80, state.batteryPercent)
        assertEquals(90, state.battery?.right?.percent)
        assertEquals(60, state.battery?.case?.percent)
        assertEquals(true, state.battery?.case?.charging)
        assertEquals(false, state.isCharging)
        assertEquals("AirPods Pro (2nd generation)", state.info?.modelName)
        assertEquals(0, platform.opens, "listening never opens anything")
    }

    @Test fun `no route is ever offered for these headsets, whatever the system can open`() {
        val candidate = HeadsetCandidate("k", "AirPods Pro", connected = true, uuids = listOf("0000110b-0000-1000-8000-00805f9b34fb"))
        for (kinds in listOf(RouteKind.entries.toSet(), setOf(RouteKind.RFCOMM_SERVICE), setOf(RouteKind.GATT), emptySet())) {
            assertNull(AppleAdvertVendor.route(candidate, kinds))
        }
        assertFalse(AppleAdvertVendor.sessionReady)
        assertNull(AppleAdvertVendor.virtual())
    }
}
