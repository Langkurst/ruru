package io.github.langkurst.ruru.vendors.soundcore

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
 * Anker Soundcore on its vendor RFCOMM service, `0cf12d31-fac3-4553-bd80-d6832e7` + five hex digits
 * that follow the model code (Earshot's SoundcoreModel and SoundcoreLinkFactory, MIT; research from
 * soundcorebridge). Opened by UUID only: the same headsets carry a firmware-update service on
 * another channel, and JBL publishes a service on the same base that is not Soundcore at all. The
 * model table, the names it is told apart by and the layouts are in [SoundcoreModel].
 */
object SoundcoreVendor : Vendor {
    override val family: DeviceFamily get() = DeviceFamily.SOUNDCORE

    const val SERVICE_UUID_PREFIX = SoundcoreModel.SERVICE_UUID_PREFIX

    override fun detect(name: String?): Boolean = SoundcoreModel.fromName(name) != null

    /**
     * The first cached service UUID a driven model publishes, lower case, unless [name] is another
     * brand's; null when there is none (Ruru then opens nothing).
     */
    fun serviceFor(uuids: List<String>, name: String?): String? = SoundcoreModel.identify(uuids, name)?.second

    /** Only by a UUID the system cached: the suffix names the model, and there is no channel fallback. */
    override fun route(candidate: HeadsetCandidate, available: Set<RouteKind>): LinkRoute? {
        if (RouteKind.RFCOMM_SERVICE !in available) return null
        return serviceFor(candidate.uuids, candidate.name)?.let { LinkRoute.RfcommService(it) }
    }

    override val sessionReady: Boolean get() = true

    /**
     * The session learns the model from the first state read (its serial names it); a transport that
     * knows its model (the demo's) says so up front.
     */
    override fun session(transport: ByteTransport, scope: CoroutineScope): HeadsetSession =
        SoundcoreSession(SoundcoreConnection(transport, scope), scope)

    /** The Sleep A30, one shared unit so the demo's settings survive a reconnect. */
    override fun virtual(): VirtualHeadset = virtual(SoundcoreModel.SLEEP_A30)

    /** A simulated Soundcore of any model that has a layout (or the Sleep A30), under the name its detection rule matches. */
    fun virtual(model: SoundcoreModel): VirtualHeadset {
        val unit = VirtualSoundcore(model)
        return VirtualHeadset(model.displayName) { VirtualSoundcoreTransport(unit) }
    }
}
