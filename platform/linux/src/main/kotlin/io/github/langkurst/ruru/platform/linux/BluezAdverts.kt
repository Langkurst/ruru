package io.github.langkurst.ruru.platform.linux

import io.github.langkurst.ruru.link.HeadsetAdvert
import java.security.MessageDigest

/** BlueZ's LE discovery results as [HeadsetAdvert]s. Listening sends nothing to any device. */
internal object BluezAdverts {
    /** How long one listening window lasts: long enough to hear AirPods' adverts (about every 0.2 to 2 s), short enough not to bother music. */
    const val WINDOW_MS = 3_000L

    /**
     * One advert per manufacturer entry of every device that has any. A device that BlueZ lists
     * twice (classic and LE) keeps its entries from the LE side only because only those carry
     * manufacturer data. The [HeadsetAdvert.key] is a hash of the object path, never an address.
     */
    fun toAdverts(devices: List<BluezDevice>): List<HeadsetAdvert> = devices.flatMap { d ->
        d.manufacturerData.map { (company, data) ->
            HeadsetAdvert(company, data, d.name.takeUnless { it.replace('-', ':').equals(d.address, ignoreCase = true) }, key(d.path), d.rssi)
        }
    }

    /** A short opaque key for one advertiser: the same device gives the same key, and nothing in it is an address. */
    fun key(devicePath: String): String =
        MessageDigest.getInstance("SHA-256").digest(devicePath.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
