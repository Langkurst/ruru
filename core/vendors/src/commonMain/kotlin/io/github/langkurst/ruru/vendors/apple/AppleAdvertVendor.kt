package io.github.langkurst.ruru.vendors.apple

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
 * AirPods and Beats: their battery comes from the "proximity pairing" adverts they broadcast
 * (manufacturer 0x004C, Earshot's ContinuityAdvert, MIT), read through
 * [io.github.langkurst.ruru.link.HeadsetPlatform.adverts]. There is no link to open and nothing is
 * ever sent, so [route] is always null. Reader: milestone C6.
 */
object AppleAdvertVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.APPLE_ADVERT

    const val APPLE_COMPANY_ID = 0x004C

    private val APPLE_NAME = Regex("""airpods|beats""", RegexOption.IGNORE_CASE)

    override fun detect(name: String?): Boolean = name != null && APPLE_NAME.containsMatchIn(name)

    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? = null

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)
}
