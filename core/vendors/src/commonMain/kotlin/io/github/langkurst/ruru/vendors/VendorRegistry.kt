package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.vendors.apple.AppleAdvertVendor
import io.github.langkurst.ruru.vendors.bose.BoseVendor
import io.github.langkurst.ruru.vendors.bragi.BragiVendor
import io.github.langkurst.ruru.vendors.jbl.JblVendor
import io.github.langkurst.ruru.vendors.sennheiser.SennheiserVendor
import io.github.langkurst.ruru.vendors.sony.SonyVendor
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreVendor

/**
 * Every maker Ruru knows, one [Vendor] per [DeviceFamily] except [DeviceFamily.GENERIC], which has
 * none: a headset nobody claims is never opened.
 */
object VendorRegistry {
    /**
     * In the order [DeviceFamily.forName] asks them (Earshot's): Bragi, Soundcore, Sennheiser before
     * Bose, Sony, JBL, Bose, then AirPods and Beats.
     */
    val vendors: List<Vendor> = listOf(
        BragiVendor,
        SoundcoreVendor,
        SennheiserVendor,
        SonyVendor,
        JblVendor,
        BoseVendor,
        AppleAdvertVendor,
    )

    /** The vendor that drives [family], or null for [DeviceFamily.GENERIC]. */
    fun forFamily(family: DeviceFamily): Vendor? = vendors.firstOrNull { it.family == family }
}
