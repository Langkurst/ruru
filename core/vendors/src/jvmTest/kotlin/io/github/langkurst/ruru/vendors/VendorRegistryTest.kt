package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.bose.BoseVendor
import io.github.langkurst.ruru.vendors.bragi.BragiVendor
import io.github.langkurst.ruru.vendors.jbl.JblVendor
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserVendor
import io.github.langkurst.ruru.vendors.sony.SonyVendor
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreVendor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VendorRegistryTest {
    private val everything = RouteKind.entries.toSet()

    private fun candidate(name: String, uuids: List<String> = emptyList(), link: HeadsetLink = HeadsetLink.CLASSIC) =
        HeadsetCandidate("k", name, connected = true, uuids = uuids, link = link)

    /** A transport that fails the test if anything is written to it. */
    private class Untouchable : ByteTransport {
        override val isConnected = true
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun write(bytes: ByteArray) = error("a stub vendor wrote ${bytes.size} bytes")
        override fun close() {}
    }

    @Test
    fun `one vendor per family, none for GENERIC`() {
        for (family in DeviceFamily.entries) {
            val vendors = VendorRegistry.vendors.filter { it.family == family }
            if (family == DeviceFamily.GENERIC) {
                assertTrue(vendors.isEmpty())
                assertNull(VendorRegistry.forFamily(family))
            } else {
                assertEquals(1, vendors.size, family.name)
                assertEquals(family, VendorRegistry.forFamily(family)?.family)
            }
        }
    }

    @Test
    fun `a vendor with a session has a demo headset, and the stubs refuse to build one and write nothing`() {
        assertTrue(VendorRegistry.forFamily(DeviceFamily.BOSE)!!.sessionReady)
        for (vendor in VendorRegistry.vendors) {
            if (vendor.sessionReady) {
                assertNotNull(vendor.virtual(), vendor.family.name)
                continue
            }
            assertFalse(vendor.sessionReady, vendor.family.name)
            assertNull(vendor.virtual(), vendor.family.name)
            assertFailsWith<NotImplementedError>(vendor.family.name) {
                vendor.session(Untouchable(), CoroutineScope(Dispatchers.Unconfined))
            }
        }
    }

    @Test
    fun `Bose keeps Ruru 0_2's routes`() {
        assertEquals(LinkRoute.BoseRfcomm(listOf(2, 8, 9)), BoseVendor.route(candidate("Bose QC Ultra Headphones"), setOf(RouteKind.BOSE_RFCOMM)))
        assertEquals(LinkRoute.BoseLe, BoseVendor.route(candidate("Bose QC Ultra Headphones", link = HeadsetLink.LE), setOf(RouteKind.BOSE_LE)))
        assertEquals(LinkRoute.BoseLe, BoseVendor.route(candidate("Bose QC Ultra Headphones"), setOf(RouteKind.BOSE_LE)))
        assertNull(BoseVendor.route(candidate("Bose QC Ultra Headphones"), setOf(RouteKind.RFCOMM_SERVICE, RouteKind.GATT)))
        // The model's own channel first (the QC35 family sits on 8).
        assertEquals(8, BoseVendor.channelsFor("Bose QC35 II").first())
    }

    @Test
    fun `other makers are reached by UUID or GATT, never by a bare channel`() {
        assertEquals(LinkRoute.RfcommService(SonyVendor.SERVICE_V2), SonyVendor.route(candidate("WH-1000XM5"), everything))
        assertNull(SonyVendor.route(candidate("WH-1000XM5", listOf(SonyVendor.SERVICE_V1)), everything))
        assertEquals(LinkRoute.RfcommService(SennheiserVendor.GAIA3_SERVICE), SennheiserVendor.route(candidate("MOMENTUM 4"), everything))
        assertNull(SennheiserVendor.route(candidate("MOMENTUM 4", listOf(SennheiserVendor.GAIA_LEGACY_SERVICE)), everything))
        val a30 = "0cf12d31-fac3-4553-bd80-d6832e7d1301"
        assertEquals(LinkRoute.RfcommService(a30), SoundcoreVendor.route(candidate("Soundcore Sleep A30", listOf(a30.uppercase())), everything))
        assertNull(SoundcoreVendor.route(candidate("Soundcore Sleep A30"), everything)) // no cached UUID, no fallback
        assertEquals(LinkRoute.Gatt(JblVendor.HARMAN), JblVendor.route(candidate("JBL Tour One M2"), everything))
        assertNull(JblVendor.route(candidate("JBL Tour One M2"), setOf(RouteKind.RFCOMM_SERVICE)))
        assertEquals(LinkRoute.RfcommService(BragiVendor.SERVICE_DATA), BragiVendor.route(candidate("Crusher 1080"), everything))
        assertNull(VendorRegistry.forFamily(DeviceFamily.APPLE_ADVERT)!!.route(candidate("AirPods Pro"), everything))
        for (vendor in VendorRegistry.vendors.filter { it.family != DeviceFamily.BOSE }) {
            val route = vendor.route(candidate("x", listOf(a30)), everything)
            assertFalse(route is LinkRoute.BoseRfcomm || route is LinkRoute.BoseLe, vendor.family.name)
        }
    }

    @Test
    fun `a GATT spec can't name a firmware-update service`() {
        assertFailsWith<IllegalArgumentException> {
            GattLinkSpec("ota", "66666666-6666-6666-6666-666666666666", "66666666-6666-6666-6666-666666666667", listOf("66666666-6666-6666-6666-666666666668"))
        }
        assertTrue(GattLinkSpec.isFirmwareService("65786365-6c70-6f69-6e74-2e636f820000"))
        assertFalse(GattLinkSpec.isFirmwareService(JblVendor.HARMAN.service))
    }

    @Test
    fun `an RFCOMM service route is a UUID, nothing else`() {
        assertFailsWith<IllegalArgumentException> { LinkRoute.RfcommService("2") }
        assertFailsWith<IllegalArgumentException> { LinkRoute.RfcommService(SonyVendor.SERVICE_V2.uppercase()) }
    }
}
