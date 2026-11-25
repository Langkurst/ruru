package io.github.langkurst.ruru.vendors.jbl

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.VirtualHeadset
import kotlinx.coroutines.CoroutineScope

/**
 * JBL's Harman "AA" protocol (H-AA) over the Harman GATT service: the 2021-24 BES models (Earshot's
 * JblModel and JblLinkFactory, MIT; research from jbl-control). Names collide across JBL's stacks
 * ("Tune 770NC" is Airoha, "Live 770NC" is BES), so a name must match a model exactly; JBL's 2025+
 * Protocol 4 and Airoha models stay generic ([JblModel.refusal]).
 *
 * Over GATT only, on the headset's LE side. On earbuds that can be a separate `-LE` twin with its own
 * name and address: finding it is the platform's job (the candidate's name may carry the `LE`
 * prefix or suffix, which [detect] ignores); the session then checks the product id the headset
 * reports. Whether the device info's address is the classic or the LE one is unconfirmed, so no
 * address check is made here.
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

    /** "JBL Wave Buds 2", "JBL LIVE660NC", "LE-JBL Tour One M2" ("TWS" optional); never a substring match ([JblModel.fromName]). */
    override fun detect(name: String?): Boolean = JblModel.fromName(name) != null

    /** Over GATT only (104 of the 123 products in JBL's app connect over BLE). */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? =
        if (RouteKind.GATT in available) LinkRoute.Gatt(HARMAN) else null

    override val sessionReady: Boolean get() = true

    /**
     * The model comes from the product id the headset reports in its device info (it sends it by
     * itself when the link opens); a 2025+ or Airoha id is refused there, before anything but
     * that read has been sent. The Bluetooth name is not passed to a session.
     */
    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession =
        JblSession(JblConnection(transport, scope))

    /** One shared Wave Buds 2, so the demo's settings survive a reconnect; a new link re-attaches to it. */
    override fun virtual(): VirtualHeadset {
        val unit = VirtualJbl()
        return VirtualHeadset(unit.name) { VirtualJblTransport(unit) }
    }
}
