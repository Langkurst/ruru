package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.notYet
import kotlinx.coroutines.CoroutineScope

/**
 * JBL's Harman "AA" protocol (H-AA) over the Harman GATT service: the 2021-24 BES models (Earshot's
 * JblModel and JblLinkFactory, MIT; research from jbl-control). Names collide across JBL's stacks
 * ("Tune 770NC" is Airoha, "Live 770NC" is BES), so a name must match a model exactly; JBL's 2025+
 * Protocol 4 and Airoha models stay generic. Session: milestone C4.
 */
object JblVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.JBL

    private fun harman(suffix: String) = "65786365-6c70-6f69-6e74-2e636f6d$suffix"

    /**
     * JBL's Harman service ("excelpoint.com" in ASCII): notifications on `…0001`, write with
     * response on `…0002`, MTU 512. The raw `…0003` characteristic belongs to Protocol 4 and is left alone.
     */
    val HARMAN: GattLinkSpec = GattLinkSpec(
        name = "Harman",
        service = harman("0000"),
        write = harman("0002"),
        notify = listOf(harman("0001")),
    )

    /** The H-AA models' names as JBL's app lists them (without "JBL"). */
    private val MODEL_NAMES = listOf(
        "Live 460NC", "Live 660NC", "Live Pro+ TWS", "Tour Pro+ TWS", "Tour One", "UA Project Rock",
        "Reflect Flow Pro", "Tune 130NC TWS", "Tune 230NC TWS", "Tune 235NC TWS", "Live Pro 2 TWS", "Tune Flex",
        "Tour One M2", "Live Flex", "Tour Pro 2", "Tune Beam", "Tune Buds", "Tune 245NC", "Live 670NC",
        "Live 675NC", "Live 770NC", "Live 775NC", "Live Beam 3", "Live Flex 3", "Wave Buds 2", "Wave Flex 2",
        "Vibe Buds 2", "Vibe Flex 2", "Tune Buds 2", "Tune Flex 2", "Tune Beam 2", "Tune 255NC", "Junior 470NC",
    )

    private val LE_PREFIX = Regex("""^le[-_ ]""", RegexOption.IGNORE_CASE)
    private val LE_SUFFIX = Regex("""[-_ ]le$""", RegexOption.IGNORE_CASE)

    /** Case, spaces and punctuation ignored, an `LE` twin prefix or suffix dropped. */
    private fun normalise(name: String): String =
        name.trim().replace(LE_PREFIX, "").replace(LE_SUFFIX, "").lowercase().filter { it.isLetterOrDigit() }

    private val KEYS: List<String> = MODEL_NAMES.map(::normalise)

    /** "JBL Wave Buds 2", "JBL LIVE660NC", "LE-JBL Tour One M2" ("TWS" optional); never a substring match. */
    override fun detect(name: String?): Boolean {
        val n = normalise(name ?: return false)
        if (!n.startsWith("jbl")) return false
        val model = n.removePrefix("jbl")
        return KEYS.any { it == model || it.removeSuffix("tws") == model.removeSuffix("tws") }
    }

    /** Over GATT only (104 of the 123 products in JBL's app connect over BLE). */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? =
        if (RouteKind.GATT in available) LinkRoute.Gatt(HARMAN) else null

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)
}
