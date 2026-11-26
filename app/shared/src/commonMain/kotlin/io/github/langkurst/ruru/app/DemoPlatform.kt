package io.github.langkurst.ruru.app

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import io.github.langkurst.ruru.protocol.bose.ModePrompt
import io.github.langkurst.ruru.protocol.bose.QcUltra2
import io.github.langkurst.ruru.vendors.DeviceFamily
import io.github.langkurst.ruru.vendors.VendorRegistry
import io.github.langkurst.ruru.vendors.VirtualHeadset
import io.github.langkurst.ruru.vendors.apple.VirtualAirPods
import io.github.langkurst.ruru.vendors.soundcore.SoundcoreModel
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
    override val routes: Set<RouteKind> = setOf(RouteKind.BOSE_RFCOMM)
    private val device = VirtualQcUltra2(profile = profile).also { d ->
        // Two made-up paired devices, never anyone's real ones. The first is "this computer" (the one running the app).
        d.pairedDevices.clear()
        d.pairedDevices += VirtualQcUltra2.Peer("AA:BB:CC:00:00:01", "Laptop")
        d.pairedDevices += VirtualQcUltra2.Peer("AA:BB:CC:00:00:02", "Phone")
        d.activeSourceMac = d.pairedDevices.first().mac
    }
    private val candidate = HeadsetCandidate(key = "demo", name = profile.customName, connected = true)

    override suspend fun headsets(): List<HeadsetCandidate> = listOf(candidate)

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
        if (route !is LinkRoute.BoseRfcomm) throw RouteNotOfferedException(name, route)
        device.powerOn()
        return VirtualTransport(device)
    }

    companion object {
        val PROFILE: VirtualProfile = VirtualProfile.QC_ULTRA_1.copy(
            customName = "Demo QC Ultra Headphones",
            // The 1st Gen reports no time left and answers "not supported" to touch controls; the demo shows the
            // time left and the touch switch to show where they go, and keeps the 1st Gen's missing mic mute.
            batteryMinutes = true,
            absent = VirtualProfile.QC_ULTRA_1.absent - QcUltra2.DISABLE_CAPTOUCH + QcUltra2.MIC_MUTE,
            savedModes = mapOf(
                3 to VirtualSlot(ModePrompt.COMMUTE, "Commute", cncWire = 3),
                4 to VirtualSlot(ModePrompt.FOCUS, "Focus", cncWire = 0),
            ),
        )
    }
}

/**
 * A simulated headset of another maker, from its [Vendor.virtual][io.github.langkurst.ruru.vendors.Vendor.virtual],
 * offered over every kind of link so the maker's own route is taken exactly as on a real system. Nothing real is touched.
 */
class VendorDemoPlatform(
    private val headset: VirtualHeadset,
    private val uuids: List<String> = emptyList(),
) : HeadsetPlatform {
    override val name: String = "Demo"
    override val supported: Boolean = true
    override val routes: Set<RouteKind> = RouteKind.entries.toSet()
    private val candidate = HeadsetCandidate(key = "demo", name = headset.name, connected = true, uuids = uuids, link = HeadsetLink.CLASSIC)

    override suspend fun headsets(): List<HeadsetCandidate> = listOf(candidate)

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport = headset.open()
}

/** The simulated headsets demo mode can show, one per maker. */
object DemoHeadsets {
    /** Settings key of the hidden choice of maker for demo mode ("sony", "jbl", ...; anything else is Bose). */
    const val SETTING_KEY = "demo.brand"

    /** The maker named by [text] (a family name, or "skullcandy", "airpods", "beats"), or Bose for anything else. */
    fun familyOf(text: String?): DeviceFamily {
        val t = text?.trim()?.lowercase().orEmpty()
        return when (t) {
            "skullcandy", "bragi" -> DeviceFamily.BRAGI
            "airpods", "beats", "apple", "apple_advert" -> DeviceFamily.APPLE_ADVERT
            else -> DeviceFamily.entries.firstOrNull { it.name.lowercase() == t && it != DeviceFamily.GENERIC } ?: DeviceFamily.BOSE
        }
    }

    /** The demo platform for [family]: Bose's the 1st Gen built from a real unit, the others their maker's virtual headset. */
    fun platform(family: DeviceFamily): HeadsetPlatform = when (family) {
        DeviceFamily.BOSE, DeviceFamily.GENERIC -> DemoPlatform()
        DeviceFamily.APPLE_ADVERT -> VirtualAirPods()
        DeviceFamily.SOUNDCORE -> {
            val model = SoundcoreModel.SLEEP_A30
            VendorDemoPlatform(io.github.langkurst.ruru.vendors.soundcore.SoundcoreVendor.virtual(model), listOf(SoundcoreModel.SERVICE_UUID_PREFIX + model.uuidSuffix))
        }
        else -> VendorRegistry.forFamily(family)?.virtual()?.let { VendorDemoPlatform(it) } ?: DemoPlatform()
    }
}
