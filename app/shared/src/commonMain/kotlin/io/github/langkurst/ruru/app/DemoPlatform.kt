package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.protocol.bose.ModePrompt
import io.github.langkurst.ruru.virtual.VirtualProfile
import io.github.langkurst.ruru.virtual.VirtualQcUltra2
import io.github.langkurst.ruru.virtual.VirtualSlot
import io.github.langkurst.ruru.virtual.VirtualTransport

/**
 * A simulated QC Ultra Headphones (1st Gen), built from what Ruru read on a real unit, so Ruru can
 * be tried without headphones. It runs the same session code as a real headset.
 */
class DemoPlatform(profile: VirtualProfile = PROFILE) : HeadsetPlatform {
    override val name: String = "Demo"
    override val supported: Boolean = true
    private val device = VirtualQcUltra2(profile = profile)
    private val candidate = HeadsetCandidate(key = "demo", name = profile.customName, connected = true)

    override suspend fun headsets(): List<HeadsetCandidate> = listOf(candidate)

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
        device.powerOn()
        return VirtualTransport(device)
    }

    companion object {
        val PROFILE: VirtualProfile = VirtualProfile.QC_ULTRA_1.copy(
            customName = "Demo QC Ultra Headphones",
            savedModes = mapOf(
                3 to VirtualSlot(ModePrompt.COMMUTE, "Commute", cncWire = 3),
                4 to VirtualSlot(ModePrompt.FOCUS, "Focus", cncWire = 0),
            ),
        )
    }
}
