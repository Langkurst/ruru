package io.github.langkurst.ruru.vendors.bose

import io.github.langkurst.ruru.link.BmapConnection
import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.protocol.bose.BoseModel
import io.github.langkurst.ruru.session.QcUltra2Protocol
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.VirtualHeadset
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualTransport
import kotlinx.coroutines.CoroutineScope

/** Bose: BMAP over classic RFCOMM (desktop, Android) or over Bluetooth LE (iPhone), as Ruru 0.2 did it. */
object BoseVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.BOSE

    /**
     * Earshot's rule: "Bose" as a word, "QuietComfort", or "QC" followed by a model ("QCY-T13" is
     * another brand). Plus Ruru 0.2's own rule, any name containing "Bose", so no headset Ruru 0.2
     * drove is dropped (asked after Bragi, Soundcore, Sennheiser, Sony and JBL).
     */
    override fun detect(name: String?): Boolean =
        name != null && (BOSE_NAME.containsMatchIn(name) || name.contains("bose", ignoreCase = true))

    /**
     * An LE-only headset on a system that opens Bose LE: [LinkRoute.BoseLe]. Otherwise the classic
     * route with the model's known channel first, then 2, 8 and 9 (Ruru 0.2's order, from bosectl
     * and Earshot), else LE when that is all the system has.
     */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? = when {
        candidate.link == HeadsetLink.LE && RouteKind.BOSE_LE in available -> LinkRoute.BoseLe
        RouteKind.BOSE_RFCOMM in available -> LinkRoute.BoseRfcomm(channelsFor(candidate.name))
        RouteKind.BOSE_LE in available -> LinkRoute.BoseLe
        else -> null
    }

    override val sessionReady: Boolean get() = true

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession =
        BoseSession(BmapConnection(transport, scope), QcUltra2Protocol())

    override fun virtual(): VirtualHeadset {
        val device = VirtualQcUltra2(profile = VirtualProfile.QC_ULTRA_1)
        return VirtualHeadset("Bose QC Ultra Headphones", VirtualTransport.factory(device))
    }

    /** The RFCOMM channels to try, in order: the model's own, then 2, 8 and 9. */
    fun channelsFor(name: String?): List<Int> = listOf(BoseModel.fromName(name)?.rfcommChannel ?: 2, 2, 8, 9).distinct()

    private val BOSE_NAME = Regex(
        """\bbose\b|quietcomfort|\bqc(?:\s?\d|\s+(?:ultra|earbuds|headphones|se|sc)\b)""",
        RegexOption.IGNORE_CASE,
    )
}
