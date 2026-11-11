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

    /** Paired headsets that look like Bose products, connected ones first. */
    suspend fun headsets(): List<HeadsetCandidate>

    /** Opens the control channel to [candidate]. Throws when it cannot. */
    suspend fun open(candidate: HeadsetCandidate): ByteTransport
}

/** A headset the platform can see. [key] identifies it to the platform and is never shown or logged. */
class HeadsetCandidate(val key: String, val name: String, val connected: Boolean) {
    override fun toString(): String = "HeadsetCandidate($name, connected=$connected)"
}
