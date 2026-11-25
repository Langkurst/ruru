package io.github.langkurst.ruru.vendors.sennheiser

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.notYet
import kotlinx.coroutines.CoroutineScope

/**
 * Sennheiser's Qualcomm GAIA v3 stack (Earshot's SennheiserModel and SennheiserLinkFactory, MIT;
 * research from momentum4-control and momentumctl). Opened by its SDP UUID only: channels differ
 * by model and the Accentum's accessory (iAP2) record is reported to power the headset off when it
 * sees GAIA frames. Session: milestone C2.
 */
object SennheiserVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.SENNHEISER

    /** GAIA v3 (`GAIA3_SPP_UUID` in Smart Control): the only socket Ruru opens on these headsets. */
    const val GAIA3_SERVICE = "a2129ff3-081b-4c45-8afe-469d9c4842ec"

    /** GAIA v1/v2 of the 2020 models (MTW2, HD 4x0BT): recognised, never opened. */
    const val GAIA_LEGACY_SERVICE = "a2129ff3-081b-4c45-8afe-469d9c4842eb"

    /**
     * Momentum 4 / 5, Accentum Plus, Accentum, Momentum True Wireless 3 / 4, Accentum True Wireless
     * and Momentum Sport. The app's internal codenames are left out: a device someone named "Mars"
     * should not be claimed. BLE-only models (CX Plus TW, CX 500BT) are not here and stay generic.
     */
    private val NAMES = listOf(
        """\bmomentum\s*4\b""",
        """\bmomentum\s*5\b""",
        """\baccentum\s*plus\b""",
        """\baccentum\b(?!\s*(?:plus|tw|true|clip))""",
        """\bmomentum\s*(?:tw|true\s*wireless)\s*3\b""",
        """\bmomentum\s*(?:tw|true\s*wireless)\s*4\b""",
        """\baccentum\s*(?:tw|true\s*wireless)\b""",
        """\bmomentum\s*sport|\bmsport1\b""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    override fun detect(name: String?): Boolean = name != null && NAMES.any { it.containsMatchIn(name) }

    /** GAIA v3 by UUID; a cached record without it (GAIA v1/v2 only, say) is refused before any socket opens. */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? {
        if (RouteKind.RFCOMM_SERVICE !in available) return null
        if (candidate.uuids.isNotEmpty() && candidate.uuids.none { it.lowercase() == GAIA3_SERVICE }) return null
        return LinkRoute.RfcommService(GAIA3_SERVICE)
    }

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)
}
