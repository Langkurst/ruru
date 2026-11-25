package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Sennheiser vendor: which names it claims, and the one route it opens (the GAIA v3 service, by UUID). */
class SennheiserVendorTest {
    private val everything = RouteKind.entries.toSet()

    private fun candidate(name: String, uuids: List<String> = emptyList()) = HeadsetCandidate("k", name, connected = true, uuids = uuids)

    @Test fun `the vendor claims exactly the names the model table does`() {
        val names = listOf(
            "MOMENTUM 4", "Momentum 4 Wireless", "MOMENTUM 5", "MOMENTUM TW 3", "MOMENTUM TW 4", "MOMENTUM TW4", "ACCENTUM",
            "ACCENTUM Plus", "ACCENTUM TW", "MOMENTUM SPORT", "MOMENTUM Sport", "MSPORT1-HR",
            // BLE-only, GAIA v1/v2, another protocol, unanalysed, codenames and look-alikes: generic.
            "CX Plus True Wireless", "CX 500BT", "MOMENTUM TW 2", "MOMENTUM 3", "HD 450BT", "PXC 550",
            "MOMENTUM TW 5", "ACCENTUM Clip", "Mars", "Venus", "Bose QC Ultra Headphones", "momentumctl", "Sennheiser MOMENTUM 4",
        )
        for (name in names) assertEquals(SennheiserModel.fromName(name) != null, SennheiserVendor.detect(name), name)
        assertEquals(false, SennheiserVendor.detect(null))
    }

    @Test fun `GAIA v3 is opened by its UUID, never a channel, and an older record is refused before any socket`() {
        assertEquals(LinkRoute.RfcommService(SennheiserVendor.GAIA3_SERVICE), SennheiserVendor.route(candidate("MOMENTUM 4"), everything))
        assertEquals(
            LinkRoute.RfcommService(SennheiserVendor.GAIA3_SERVICE),
            SennheiserVendor.route(candidate("MOMENTUM 4", listOf("00001101-0000-1000-8000-00805f9b34fb", SennheiserVendor.GAIA3_SERVICE.uppercase())), everything),
        )
        // Only the legacy record, or only the serial port and the iAP2 record (a BLE-first model): not opened.
        assertNull(SennheiserVendor.route(candidate("MOMENTUM 4", listOf(SennheiserVendor.GAIA_LEGACY_SERVICE)), everything))
        assertNull(SennheiserVendor.route(candidate("MOMENTUM 4", listOf("00001101-0000-1000-8000-00805f9b34fb", "00000000-deca-fade-deca-deafdecacaff")), everything))
        // A system that cannot open a service by UUID gets nothing.
        assertNull(SennheiserVendor.route(candidate("MOMENTUM 4"), setOf(RouteKind.BOSE_RFCOMM, RouteKind.BOSE_LE)))
    }
}
