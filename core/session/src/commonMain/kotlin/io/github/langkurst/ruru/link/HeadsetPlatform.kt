package io.github.langkurst.ruru.link

/**
 * What each platform (Windows, macOS, Linux, Android, iPhone, demo) supplies to the shared
 * controller: the headsets it can see and a byte pipe to one of them. Everything above this is
 * common code.
 */
interface HeadsetPlatform {
    /** Shown in the connection report, e.g. "Windows". */
    val name: String

    /** False when this system cannot open the control channel at all. */
    val supported: Boolean

    /**
     * Why the system shows Ruru no headphones right now, in words for the user (Android without
     * the Nearby devices permission, say), or null when nothing is in the way.
     */
    val blocked: String? get() = null

    /**
     * True when [confirm] must be called after [open] to prove the headphones' identity before
     * Ruru sends anything but the address read.
     */
    val checksIdentity: Boolean get() = false

    /** Paired headsets that look like Bose products, connected ones first. */
    suspend fun headsets(): List<HeadsetCandidate>

    /** Opens the control channel to [candidate]. Throws when it cannot. */
    suspend fun open(candidate: HeadsetCandidate): ByteTransport

    /**
     * Called after [open()] and before Ruru sends anything but the address read. Returns null when
     * [candidate] is the person's headphones or cannot be checked, or the words to show when they
     * are someone else's.
     */
    suspend fun confirm(candidate: HeadsetCandidate, address: String?): String? = null
}

/** A headset the platform can see. [key] identifies it to the platform and is never shown or logged. */
class HeadsetCandidate(val key: String, val name: String, val connected: Boolean) {
    override fun toString(): String = "HeadsetCandidate($name, connected=$connected)"
}
