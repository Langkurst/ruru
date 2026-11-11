package io.github.langkurst.ruru.platform.windows

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Windows: Bose headsets from the paired-device list, opened on their Serial Port channel. Only
 * devices named like a Bose product are offered, so Ruru never writes to another maker's headset.
 */
class WindowsHeadsetPlatform : HeadsetPlatform {
    override val name: String = "Windows"

    override val supported: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    override suspend fun headsets(): List<HeadsetCandidate> = withContext(Dispatchers.IO) {
        WinBluetooth.pairedDevices()
            .filter { looksLikeBose(it.name) }
            .sortedByDescending { it.connected }
            .map { HeadsetCandidate(key = it.address.toString(16), name = it.name, connected = it.connected) }
    }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport = withContext(Dispatchers.IO) {
        val device = WinBluetooth.pairedDevices().firstOrNull { it.address.toString(16) == candidate.key }
            ?: throw IOException("${candidate.name} is no longer paired with this computer")
        WinRfcommTransport.open(device)
    }

    companion object {
        /** "Bose QC Ultra Headphones", "Bose QC45"; not the "LE-" twin Windows lists for LE Audio. */
        fun looksLikeBose(name: String): Boolean =
            name.contains("Bose", ignoreCase = true) && !name.startsWith("LE-", ignoreCase = true)
    }
}
