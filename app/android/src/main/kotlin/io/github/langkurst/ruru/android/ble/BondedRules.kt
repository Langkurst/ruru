package io.github.langkurst.ruru.android.ble

/**
 * The pure rules for which bonded devices Ruru lists and whether a service can be there, kept free
 * of Android types so they are unit-tested. Nothing here talks to a device.
 */
internal object BondedRules {
    /** Audio sink (A2DP), headset (HSP) and hands-free (HFP) service UUIDs: what a pair of headphones caches. */
    private val AUDIO_UUIDS = setOf(
        "0000110b-0000-1000-8000-00805f9b34fb",
        "0000110a-0000-1000-8000-00805f9b34fb",
        "00001108-0000-1000-8000-00805f9b34fb",
        "0000111e-0000-1000-8000-00805f9b34fb",
        "0000111f-0000-1000-8000-00805f9b34fb",
    )

    /** BluetoothClass.Device.Major.AUDIO_VIDEO. */
    const val MAJOR_AUDIO_VIDEO = 0x0400

    /** A headset, headphones, earbuds or speaker: Bluetooth's audio device class, or an audio service in the cache. */
    fun isAudio(majorDeviceClass: Int?, uuids: List<String>): Boolean =
        majorDeviceClass == MAJOR_AUDIO_VIDEO || uuids.any { it.lowercase() in AUDIO_UUIDS }

    /**
     * True when the system has a cached service list for the device and [uuid] is not in it, so
     * opening that service by UUID would find nothing. An empty cache says nothing (the system never
     * looked), so the open is tried and the system's own lookup decides.
     */
    fun serviceMissing(cached: List<String>, uuid: String): Boolean =
        cached.isNotEmpty() && cached.none { it.equals(uuid, ignoreCase = true) }

    /**
     * Bonded names with the LE Audio twins dropped: "LE-X" next to a classic "X" is the same
     * headphones under another address, and the control channel is on the classic one. An "LE-"
     * name with no classic twin stays (an LE-only device).
     */
    fun dropLeTwins(names: List<String>): List<String> {
        val classic = names.filterNot { LeAdvertMatch.isLeName(it) }.map { it.trim().lowercase() }.toSet()
        return names.filterNot { LeAdvertMatch.isLeName(it) && LeAdvertMatch.baseName(it)?.lowercase() in classic }
    }
}
