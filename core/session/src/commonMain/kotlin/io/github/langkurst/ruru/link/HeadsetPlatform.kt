package io.github.langkurst.ruru.link

/**
 * What each platform (Windows, macOS, Linux, Android, iPhone, demo) supplies to the shared
 * controller: the headsets it can see and a byte pipe to one of them. Everything above this is
 * common code. Version 2 (Ruru 0.3): the controller asks for a [LinkRoute], chosen by the
 * headset's maker, and the platform opens that route or refuses it before sending a byte.
 *
 * Bluetooth addresses stay in here: a [HeadsetCandidate] or [HeadsetAdvert] carries an opaque
 * [HeadsetCandidate.key] the platform alone understands.
 */
interface HeadsetPlatform {
    /** Shown in the connection report, e.g. "Windows". */
    val name: String

    /** False when this system cannot open the control channel at all. */
    val supported: Boolean

    /** The kinds of [LinkRoute] [open] can take on this system. Anything else is refused before any byte. */
    val routes: Set<RouteKind>

    /**
     * Why the system shows Ruru no headphones right now, in words for the user (Android without
     * the Nearby devices permission, say), or null when nothing is in the way.
     */
    val blocked: Words? get() = null

    /**
     * True when [confirm] must be called after [open] to prove the headphones' identity before
     * Ruru sends anything but the address read.
     */
    val checksIdentity: Boolean get() = false

    /** Paired headsets Ruru may offer, connected ones first. */
    suspend fun headsets(): List<HeadsetCandidate>

    /**
     * Opens [route] to [candidate]. Throws when it cannot: [ExplainedConnectException] with words
     * for the user, [RouteNotOfferedException] for a route outside [routes] (nothing was sent).
     */
    suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport

    /**
     * Called after [open()] and before Ruru sends anything but the address read. Returns null when
     * [candidate] is the person's headphones or cannot be checked, or the words to show when they
     * are someone else's.
     */
    suspend fun confirm(candidate: HeadsetCandidate, address: String?): Words? = null

    /**
     * Bluetooth LE adverts heard in one short listening window (AirPods and Beats battery), or an
     * empty list on a system that cannot listen. Listening sends nothing.
     */
    suspend fun adverts(): List<HeadsetAdvert> = emptyList()
}

/** How a headset is reachable: classic Bluetooth (RFCOMM) or Bluetooth LE only. */
enum class HeadsetLink { CLASSIC, LE }

/**
 * A headset the platform can see. [key] identifies it to the platform and is never shown or logged.
 * [uuids] are the service UUIDs the system already cached for it (lower case, empty when the system
 * keeps none or Ruru cannot read them); reading them sends nothing to the headset.
 */
class HeadsetCandidate(
    val key: String,
    val name: String,
    val connected: Boolean,
    val uuids: List<String> = emptyList(),
    val link: HeadsetLink = HeadsetLink.CLASSIC,
) {
    override fun toString(): String = "HeadsetCandidate($name, connected=$connected, ${uuids.size} uuids, $link)"
}

/**
 * One Bluetooth LE advert: [manufacturerId] (0x004C for Apple) and its manufacturer-specific
 * [data] with the company id stripped, the advertised [name] if any, and an opaque [key] for the
 * advertiser that is never an address and never shown or logged.
 */
class HeadsetAdvert(val manufacturerId: Int, val data: ByteArray, val name: String?, val key: String) {
    override fun toString(): String = "HeadsetAdvert(0x${manufacturerId.toString(16)}, ${data.size} bytes, $name)"
}
