package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.notYet
import kotlinx.coroutines.CoroutineScope

/**
 * Bragi BOS-RPC: the Skullcandy models "Sound by Bose" (Earshot's BragiModel and BragiLinkFactory,
 * MIT). Asked first, so a Crusher whose name mentions Bose never gets BMAP. The Skull-iQ and Airoha
 * RACE Skullcandy models are another protocol and stay generic. Session: milestone C5 (jvmMain,
 * for the JVM's crypto).
 */
object BragiVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.BRAGI

    /** The Bragi data service of the Crusher's device config, where BOS-RPC frames go. */
    const val SERVICE_DATA = "54c5bf4c-c52f-4635-98dc-eebf97c6726f"

    /** Airoha's native RACE socket in the official app: never written by Ruru. */
    const val SERVICE_THIRD_PARTY = "a4602b24-f9f1-4ac3-988c-228d764b97a6"

    /** Crusher 1080 ANC, Method 360 ANC, Method 540 ANC ("ANC" is part of the Method hints: a plain "Method" is not enough). */
    private val NAME_HINTS = listOf("crusher 1080", "crusher1080", "method 360 anc", "method 540 anc")

    /** Letters and digits only, lower case: "Method-360_ANC" and "method 360 anc" compare equal. */
    private fun compact(s: String) = s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    override fun detect(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        val c = compact(n)
        return NAME_HINTS.any { it in n || compact(it) in c }
    }

    /** The data service by UUID; the third-party (RACE) service is never a route. */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? =
        if (RouteKind.RFCOMM_SERVICE in available) LinkRoute.RfcommService(SERVICE_DATA) else null

    override val sessionReady: Boolean get() = false

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = notYet(family)
}
