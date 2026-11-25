package io.github.langkurst.ruru.vendors.bragi

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.HeadsetSession
import io.github.langkurst.ruru.vendors.Vendor
import io.github.langkurst.ruru.vendors.VirtualHeadset
import kotlinx.coroutines.CoroutineScope

/**
 * Bragi BOS-RPC: the Skullcandy models "Sound by Bose" (Earshot's BragiModel, BragiLinkFactory and
 * session, MIT; the protocol comes from Earshot's own interoperability analysis of the maker's app,
 * so there is no outside project to credit). Asked first, so a Crusher whose name mentions Bose
 * never gets BMAP. The Skull-iQ and Airoha RACE Skullcandy models are another protocol and stay
 * generic. Opened by UUID only, on the data service; the third-party service carries Airoha's RACE
 * protocol and is never opened.
 *
 * The session needs the JVM's crypto (the secure session's ECDH, signatures and AEAD), so it lives
 * in `jvmMain`, shared by the desktop and Android apps. A platform without it (iPhone, which can't
 * open an RFCOMM service anyway) reports [sessionReady] false: nothing is opened and nothing crashes.
 */
object BragiVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.BRAGI

    /** The Bragi data service of the Crusher's device config, where BOS-RPC frames go. */
    const val SERVICE_DATA = "54c5bf4c-c52f-4635-98dc-eebf97c6726f"

    /** Airoha's native RACE socket in the official app: never opened or written by Ruru. */
    const val SERVICE_THIRD_PARTY = "a4602b24-f9f1-4ac3-988c-228d764b97a6"

    /** Crusher 1080 ANC, Method 360 ANC, Method 540 ANC ("ANC" is part of the Method hints: a plain "Method" is not enough). */
    override fun detect(name: String?): Boolean = BragiModel.isBragiName(name)

    /** The data service by UUID; the third-party (RACE) service is never a route. */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? =
        if (RouteKind.RFCOMM_SERVICE in available) LinkRoute.RfcommService(SERVICE_DATA) else null

    /** True where the crypto the secure session needs exists (the JVM: desktop and Android). */
    override val sessionReady: Boolean get() = bragiSupported()

    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession = bragiSession(transport, scope)

    /** The Crusher 1080, one shared unit so the demo's settings survive a reconnect; null where there is no session. */
    override fun virtual(): VirtualHeadset? = bragiVirtual()
}
