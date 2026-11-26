package io.github.langkurst.ruru.ios

import io.github.langkurst.ruru.link.GattLinkSpec
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.VendorRegistry

/**
 * What the iPhone may open, decided without CoreBluetooth so it is tested on any PC. iOS gives an
 * app Bluetooth LE only: no RFCOMM (classic serial) and no AirPods adverts, so those are refused
 * before a connection is made. Nothing here ever touches Apple's accessory (iAP2) channel.
 */
object IosRoutes {
    /** Bose over LE (FEBE) and any maker's GATT pipe; never RFCOMM. */
    val OFFERED: Set<RouteKind> = setOf(RouteKind.BOSE_LE, RouteKind.GATT)

    /** iOS never hands an app Apple's AirPods or Beats adverts, so AirPods are not possible here. */
    const val LISTENS_FOR_ADVERTS: Boolean = false

    /** The sentence for a route iOS cannot open: "iPhone doesn't let apps use this kind of Bluetooth connection". */
    val CLASSIC_ONLY: Words = Words("problem_ios_classic_only")

    /** Null when [route] may be opened; otherwise the words to show, before anything is opened. */
    fun refusal(route: LinkRoute): Words? = if (route.kind in OFFERED) null else CLASSIC_ONLY

    /**
     * The GATT pipe of a non-Bose maker whose headphones are called [name], or null when the name
     * is Bose's (its LE route is [LinkRoute.BoseLe]), unknown, or a maker with no GATT route. Used to
     * decide which nearby Bluetooth LE devices are worth offering.
     */
    fun gattSpecFor(name: String, uuids: List<String> = emptyList()): GattLinkSpec? {
        val family = DeviceFamily.resolve(override = null, learned = null, name = name, uuids = uuids)
        if (family == DeviceFamily.BOSE || family == DeviceFamily.GENERIC) return null
        val vendor = VendorRegistry.forFamily(family)?.takeIf { it.sessionReady } ?: return null
        val candidate = HeadsetCandidate(key = "", name = name, connected = false, uuids = uuids, link = HeadsetLink.LE)
        return (vendor.route(candidate, OFFERED) as? LinkRoute.Gatt)?.spec
    }
}
