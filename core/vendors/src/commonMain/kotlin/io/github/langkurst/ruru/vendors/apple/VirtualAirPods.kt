package io.github.langkurst.ruru.vendors.apple

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind

/**
 * Simulated AirPods for demo mode, snapshots and tests: a paired "AirPods Pro" and the adverts a
 * Windows PC would hear while the buds are out, plus a stranger's AirPods Max and someone else's
 * AirPods nearby that must not be shown. A [HeadsetPlatform] with no routes at all: [open] is never
 * valid, so the demo proves nothing is ever opened. The numbers are made up.
 */
class VirtualAirPods(
    private val left: Int? = 80,
    private val right: Int? = 90,
    private val case: Int? = 60,
    private val leftCharging: Boolean = false,
    private val rightCharging: Boolean = false,
    private val caseCharging: Boolean = true,
) : HeadsetPlatform {
    override val name: String = "Demo"
    override val supported: Boolean = true
    override val routes: Set<RouteKind> = emptySet()
    override val listensForAdverts: Boolean = true

    /** Every [open] call, so a test can assert there were none. */
    var opens = 0
        private set

    private val candidate = HeadsetCandidate(key = "demo-airpods", name = NAME, connected = true)

    override suspend fun headsets(): List<HeadsetCandidate> = listOf(candidate)

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
        opens++
        error("AirPods are never opened: Ruru only listens to their adverts")
    }

    override suspend fun adverts(): List<HeadsetAdvert> = listOf(
        HeadsetAdvert(ContinuityAdvert.APPLE_COMPANY_ID, advert(MODEL_PRO_2, left, right, case, leftCharging, rightCharging, caseCharging), null, KEY_OURS, rssi = -48),
        // A stranger's AirPods Max (wrong model for "AirPods Pro") and someone else's AirPods Pro, far away.
        HeadsetAdvert(ContinuityAdvert.APPLE_COMPANY_ID, advert(MODEL_MAX, 30, null, null, false, false, false), null, KEY_STRANGER_MAX, rssi = -55),
        HeadsetAdvert(ContinuityAdvert.APPLE_COMPANY_ID, advert(MODEL_PRO_2, 10, 20, 30, false, false, false), null, KEY_STRANGER_PRO, rssi = -91),
        // Not an AirPods advert at all.
        HeadsetAdvert(0x0006, byteArrayOf(1, 9, 32, 2), null, "demo-other", rssi = -40),
    )

    companion object {
        const val NAME = "AirPods Pro"
        const val KEY_OURS = "demo-ours"
        const val KEY_STRANGER_MAX = "demo-stranger-max"
        const val KEY_STRANGER_PRO = "demo-stranger-pro"
        const val MODEL_PRO_2 = 0x1420
        const val MODEL_MAX = 0x0A20

        /**
         * The 11 bytes of manufacturer data (company id stripped) for the layout in [ContinuityAdvert],
         * in the "status 0x20 set: low nibble is the left bud" form. A part with no reading is nibble 15.
         * Used by demo mode and by the tests to build adverts; a real advert is longer (25 bytes).
         */
        fun advert(model: Int, left: Int?, right: Int?, case: Int?, leftCharging: Boolean, rightCharging: Boolean, caseCharging: Boolean): ByteArray {
            fun nibble(percent: Int?) = if (percent == null) 0xF else (percent / 10).coerceIn(0, 10)
            val buds = (nibble(right) shl 4) or nibble(left)
            val charge = (if (leftCharging) 0x1 else 0) or (if (rightCharging) 0x2 else 0) or (if (caseCharging) 0x4 else 0)
            return byteArrayOf(
                0x07, 0x19, 0x01, (model shr 8).toByte(), model.toByte(), 0x2B,
                buds.toByte(), ((charge shl 4) or nibble(case)).toByte(), 0, 0, 0,
            )
        }
    }
}
