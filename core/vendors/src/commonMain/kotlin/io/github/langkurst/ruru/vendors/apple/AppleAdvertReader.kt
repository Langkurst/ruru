package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.core.HeadphoneState
import io.github.langkurst.ruru.core.currentTimeMillis
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.vendors.AdvertReader

/**
 * Finds the person's AirPods or Beats among every advert one listening window heard, and keeps the
 * last reading between windows (the earbuds only advertise while out of the case or with the lid
 * open). Adverts come from random addresses, so ours is told apart by:
 *  1. its model fitting the Bluetooth [name] ([AppleModels.fitsName]);
 *  2. being heard at all: a platform that reports signal strength drops anything weaker than
 *     [MIN_RSSI], roughly "on you or on the table next to you";
 *  3. the advertiser already followed ([followed]: the advert key is opaque, never an address), then
 *     the only one that fits, then the strongest. Several that fit and nothing to tell them apart
 *     reads as nothing: showing a stranger's battery is worse than showing none.
 * Nothing is sent and no link is opened: the only input is [HeadsetAdvert]s from
 * [io.github.langkurst.ruru.link.HeadsetPlatform.adverts].
 */
class AppleAdvertReader(
    private val name: String,
    private val clock: () -> Long = ::currentTimeMillis,
) : AdvertReader {
    private var followed: String? = null
    private var last: ContinuityAdvert? = null
    private var heardAt = 0L

    override fun fold(heard: List<HeadsetAdvert>, state: HeadphoneState): HeadphoneState {
        val now = clock()
        pick(heard)?.let { (key, advert) ->
            followed = key
            last = advert
            heardAt = now
        }
        if (last != null && now - heardAt > STALE_MS) last = null
        return AppleStateMapping.apply(state, last, name, now)
    }

    /** The advertiser and its newest advert that is the person's, or null. */
    private fun pick(heard: List<HeadsetAdvert>): Pair<String, ContinuityAdvert>? {
        val ours = heard.asSequence()
            .filter { it.manufacturerId == ContinuityAdvert.APPLE_COMPANY_ID }
            .filter { a -> a.rssi?.let { it >= MIN_RSSI } ?: true }
            .mapNotNull { a -> ContinuityAdvert.parse(a.data)?.takeIf { AppleModels.fitsName(it.model, name) }?.let { a to it } }
            .toList()
        if (ours.isEmpty()) return null
        val keys = ours.map { it.first.key }.distinct()
        val chosen = when {
            followed in keys -> followed!!
            keys.size == 1 -> keys.single()
            else -> {
                // Several fit: the strongest signal, if one is clearly stronger than the rest.
                val strongest = ours.mapNotNull { it.first.rssi }.maxOrNull() ?: return null
                ours.filter { it.first.rssi == strongest }.map { it.first.key }.distinct().singleOrNull() ?: return null
            }
        }
        // The last one heard from this advertiser in the window is the newest.
        return chosen to ours.last { it.first.key == chosen }.second
    }

    companion object {
        /** Roughly "on you or on the table next to you". */
        const val MIN_RSSI = -70

        /** A reading older than this is dropped: the buds are in the case, or the person left. */
        const val STALE_MS = 5 * 60_000L
    }
}
