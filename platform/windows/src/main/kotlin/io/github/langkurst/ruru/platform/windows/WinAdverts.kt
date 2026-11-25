package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.HeadsetAdvert

/**
 * Bluetooth LE adverts on Windows, through the helper's BluetoothLEAdvertisementWatcher. Only
 * adverts with manufacturer data become [HeadsetAdvert]s (AirPods and Beats battery read those);
 * keys are the helper's opaque ones, never addresses.
 */
object WinAdverts {
    /** The helper's adverts as the shared code sees them. */
    fun toHeadsetAdverts(adverts: List<WinBleProtocol.Advert>): List<HeadsetAdvert> =
        adverts.mapNotNull { a -> a.companyId?.let { HeadsetAdvert(it, a.data, a.name, a.key) } }

    /** What a check may print about a listen: how many advertisers, and whether one was named like a Bose product. Names stay out. */
    fun summary(adverts: List<WinBleProtocol.Advert>): String {
        val advertisers = adverts.map { it.key }.distinct().size
        val bose = adverts.any { it.name?.contains("Bose", ignoreCase = true) == true }
        return "$advertisers Bluetooth LE advertisers heard; ${if (bose) "one named Bose" else "none named Bose"}"
    }
}
