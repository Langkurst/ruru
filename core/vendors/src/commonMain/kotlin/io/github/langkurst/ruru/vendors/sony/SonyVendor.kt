package io.github.langkurst.ruru.vendors.sony

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
 * Sony MDR v2 (Earshot's SonyModel and SonyLinkFactory, MIT; research from SonyHeadphonesClient).
 * The control service is found by SDP UUID, never by channel: many Sony models are Airoha-based
 * and also expose the Airoha RACE debug channel, which Ruru must never open. Session: milestone C1.
 */
object SonyVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.SONY

    /** MDR v2 (WF-1000XM4 and every 2022+ model). */
    const val SERVICE_V2 = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

    /** MDR v1 (WH-1000XM4 and older): not driven, such a headset stays generic. */
    const val SERVICE_V1 = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    /** The same 16 bytes in reverse order, as some Android stacks report SDP UUIDs. */
    fun reversed(uuid: String): String {
        val hex = uuid.replace("-", "").lowercase()
        require(hex.length == 32) { "not a UUID: $uuid" }
        val r = hex.chunked(2).reversed().joinToString("")
        return "${r.substring(0, 8)}-${r.substring(8, 12)}-${r.substring(12, 16)}-${r.substring(16, 20)}-${r.substring(20)}"
    }

    private val SERVICE_V2_REVERSED = reversed(SERVICE_V2)
    private val SERVICE_V1_REVERSED = reversed(SERVICE_V1)

    fun isV2Service(uuid: String): Boolean = uuid.lowercase().let { it == SERVICE_V2 || it == SERVICE_V2_REVERSED }
    fun isV1Service(uuid: String): Boolean = uuid.lowercase().let { it == SERVICE_V1 || it == SERVICE_V1_REVERSED }

    /** The cached record shows the MDR v1 service and not v2: an older Sony Ruru doesn't drive. */
    fun onlyV1(uuids: List<String>): Boolean = uuids.any(::isV1Service) && uuids.none(::isV2Service)

    /**
     * MDR v2 models by their Bluetooth name (also with an `LE_` prefix): WH-1000XM5/XM6,
     * WF-1000XM4/XM5/XM6, the LinkBuds family, ULT WEAR (WH-ULT900N), WH-CH720N, WF-C510,
     * WF-C700N and WF-C710N. The WH-1000XM4 is MDR v1 and deliberately not listed.
     */
    private val V2_NAME = Regex(
        """\bWH-1000XM[56]\b|\bWF-1000XM[456]\b|\bLinkBuds\b|\bWF-L(?:S)?9\d0N?\b|\bWF-LC900\b|\bULT\s*WEAR\b|\bWH-ULT900N\b|\bWH-CH720N\b|\bWF-C(?:510|700N|710N)\b""",
        RegexOption.IGNORE_CASE,
    )

    override fun detect(name: String?): Boolean =
        name != null && V2_NAME.containsMatchIn(name.trim().removePrefix("LE_").removePrefix("LE-"))

    /** The v2 service by its canonical UUID (the SDP record holds it; only Android's cache may be reversed). */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? {
        if (RouteKind.RFCOMM_SERVICE !in available) return null
        if (candidate.uuids.isNotEmpty() && candidate.uuids.none(::isV2Service)) return null
        return LinkRoute.RfcommService(SERVICE_V2)
    }

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)
}
