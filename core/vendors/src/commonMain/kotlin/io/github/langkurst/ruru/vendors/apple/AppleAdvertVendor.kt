package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.AdvertReader
import io.github.langkurst.ruru.vendors.AdvertVendor
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.notYet
import kotlinx.coroutines.CoroutineScope

/**
 * AirPods and Beats: their battery comes from the "proximity pairing" adverts they broadcast
 * (manufacturer 0x004C, Earshot's ContinuityAdvert, MIT), read through
 * [io.github.langkurst.ruru.link.HeadsetPlatform.adverts]. There is no link to open and nothing is
 * ever sent, so [route] is always null and [session] never exists; the controller reads an
 * [AppleAdvertReader] instead. Listening modes are not covered: they need a Bluetooth-stack
 * workaround that Apple can close. Windows through its LE helper; Linux and Android when their
 * scanners land; not possible on Mac and iPhone, which never hand an app Apple's adverts.
 */
object AppleAdvertVendor : AdvertVendor {
    override val family: DeviceFamily get() = DeviceFamily.APPLE_ADVERT

    const val APPLE_COMPANY_ID = ContinuityAdvert.APPLE_COMPANY_ID

    private val APPLE_NAME = Regex("""airpods|beats""", RegexOption.IGNORE_CASE)

    override fun detect(name: String?): Boolean = name != null && APPLE_NAME.containsMatchIn(name)

    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? = null

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)

    override fun reader(name: String): AdvertReader = AppleAdvertReader(name)

    override val notPossibleKey: String get() = AppleStrings.NOTE_NOT_POSSIBLE
}
