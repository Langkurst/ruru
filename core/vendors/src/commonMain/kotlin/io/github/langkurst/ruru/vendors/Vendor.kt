package io.github.langkurst.ruru.vendors

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import kotlinx.coroutines.CoroutineScope

/**
 * One headphone maker as the controller sees it (Earshot's VendorDriver plus its LinkFactory):
 * how its headsets are recognised, which link reaches them and the session that drives them.
 * Each lives in its own file under `vendors/<brand>/` and is listed once in [VendorRegistry].
 */
interface Vendor {
    val family: DeviceFamily

    /** True when the Bluetooth [name] is one of this maker's headsets that Ruru may drive. */
    fun detect(name: String?): Boolean

    /**
     * Where this headset's control link is, among the route kinds the platform can open
     * ([available]), or null when none fits (then nothing is opened). Decided from the name and the
     * cached service UUIDs only; never by address, never a bare RFCOMM channel except Bose's.
     */
    fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute?

    /**
     * False while [session] is a stub: the controller then never opens a link to this maker's
     * headsets. A vendor's builder sets it true together with a real [session].
     */
    val sessionReady: Boolean

    /** The session over an open [transport]. Called only when [sessionReady]. */
    fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession

    /** A simulated headset of this maker for demo mode and tests, or null when there is none yet. */
    fun virtual(): VirtualHeadset? = null
}

/** A simulated headset: its Bluetooth [name] and a fresh byte pipe to it per connection ([open]). */
class VirtualHeadset(val name: String, val open: () -> ByteTransport)

/** For a vendor whose session isn't written yet (later milestones): fails loudly, sends nothing. */
internal fun notYet(family: DeviceFamily): Nothing =
    throw NotImplementedError("The ${family.name} session isn't written yet; Ruru never opens these headphones")
