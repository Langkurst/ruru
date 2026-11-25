package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.link.HeadsetAdvert

/**
 * A maker whose headsets Ruru only listens to: what they say in their Bluetooth LE adverts (battery,
 * charging) and nothing else. No link is ever opened and nothing is sent, so [Vendor.route] is null
 * and [Vendor.sessionReady] false; the controller reads [AdvertReader]s instead of running a session.
 */
interface AdvertVendor : Vendor {
    /** A reader for the paired headset called [name]; one per connection, it remembers which advertiser is the person's. */
    fun reader(name: String): AdvertReader

    /** Words for the person on a system that cannot hear these adverts (Mac, iPhone), as a string key. */
    val notPossibleKey: String
}

interface AdvertReader {
    /**
     * [state] with what [heard] (one listening window, possibly empty) says folded in. Always
     * carries the capabilities (battery only) so the screens hide every control; the battery
     * itself is kept between windows and dropped when nothing has been heard for a long while.
     */
    fun fold(heard: List<HeadsetAdvert>, state: HeadphoneState): HeadphoneState
}
