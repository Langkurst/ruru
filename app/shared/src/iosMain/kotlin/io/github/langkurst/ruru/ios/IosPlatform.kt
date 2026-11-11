package io.github.langkurst.ruru.ios

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform

/**
 * The iPhone's way to the headphones: Bluetooth LE first ([IosLeHeadsetPlatform]), and Apple's
 * accessory channel ([IosHeadsetPlatform]) only if LE finds nothing, for the day Bose's name for
 * that channel is known. Each candidate's key carries its route, so [open] uses the same one.
 */
class IosPlatform(
    private val le: HeadsetPlatform = IosLeHeadsetPlatform(),
    private val accessory: HeadsetPlatform = IosHeadsetPlatform(),
) : HeadsetPlatform {
    override val name: String = "iPhone"
    override val supported: Boolean = true
    override val blocked: String? get() = le.blocked

    override suspend fun headsets(): List<HeadsetCandidate> =
        le.headsets().map { it.via(LE) }.ifEmpty { accessory.headsets().map { it.via(ACCESSORY) } }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
        val inner = HeadsetCandidate(candidate.key.substringAfter(':'), candidate.name, candidate.connected)
        return if (candidate.key.startsWith("$ACCESSORY:")) accessory.open(inner) else le.open(inner)
    }

    private fun HeadsetCandidate.via(route: String) = HeadsetCandidate("$route:$key", name, connected)

    private companion object {
        const val LE = "le"
        const val ACCESSORY = "ea"
    }
}
