package io.github.langkurst.ruru.ios

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LogRing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.ExternalAccessory.EAAccessory
import platform.ExternalAccessory.EAAccessoryManager
import platform.ExternalAccessory.EASession
import platform.Foundation.NSBundle
import kotlin.concurrent.Volatile

/**
 * iPhone: Bose headphones reached through Apple's accessory channel (External Accessory, iAP2).
 * iOS has no Serial Port access, and the 1st Gen QC Ultra does not answer BMAP over Bluetooth
 * LE (checked on a real unit), so this is the remaining route. The protocol name Bose uses is not
 * public yet. Since iOS 10 an app only sees accessories that offer a protocol named in its own
 * Info.plist, so Ruru declares the likely candidates; if none matches, iOS shows it nothing at
 * all. The confirmed name would come from the UISupportedExternalAccessoryProtocols entry in the
 * Bose app's Info.plist.
 */
class IosHeadsetPlatform : HeadsetPlatform {
    override val name: String = "iPhone"
    override val supported: Boolean = true

    private fun declared(): List<String> =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("UISupportedExternalAccessoryProtocols") as? List<*>)
            ?.filterIsInstance<String>().orEmpty()

    private fun accessories(): List<EAAccessory> =
        EAAccessoryManager.sharedAccessoryManager().connectedAccessories.filterIsInstance<EAAccessory>()

    override suspend fun headsets(): List<HeadsetCandidate> {
        val all = accessories()
        // What iOS shows Ruru goes into the connection report (only accessories with a declared name).
        all.forEach { LogRing.add('I', "iOS", "accessory '${it.name}' by '${it.manufacturer}' offers ${it.protocolStrings}") }
        return all.filter { it.manufacturer.contains("Bose", ignoreCase = true) || it.name.contains("Bose", ignoreCase = true) }
            .map { HeadsetCandidate(key = it.connectionID.toString(), name = it.name, connected = it.connected) }
    }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
        val accessory = accessories().firstOrNull { it.connectionID.toString() == candidate.key }
            ?: throw IOException("${candidate.name} is no longer connected")
        val offered = accessory.protocolStrings.filterIsInstance<String>()
        val protocol = offered.firstOrNull { it in declared() }
            ?: throw IOException("${candidate.name} offers ${offered.ifEmpty { listOf("no protocols") }.joinToString()}, none of which Ruru knows yet")
        val session = EASession(accessory, protocol)
        return EaTransport(session)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class EaTransport(private val session: EASession) : ByteTransport {
    @Volatile private var open = true
    private val input = session.inputStream ?: throw IOException("no input stream")
    private val output = session.outputStream ?: throw IOException("no output stream")

    init {
        input.open()
        output.open()
    }

    override val isConnected: Boolean get() = open

    override val incoming: Flow<ByteArray> = flow {
        val buf = ByteArray(1024)
        while (open) {
            if (input.hasBytesAvailable) {
                val n = buf.usePinned { input.read(it.addressOf(0).reinterpret(), buf.size.convert()) }.toInt()
                if (n < 0) {
                    open = false
                    throw IOException("the headset closed the accessory channel")
                }
                if (n > 0) emit(buf.copyOf(n))
            } else {
                delay(15)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        var off = 0
        while (off < bytes.size && open) {
            if (!output.hasSpaceAvailable) {
                delay(5)
                continue
            }
            val n = bytes.usePinned { output.write(it.addressOf(off).reinterpret(), (bytes.size - off).convert()) }.toInt()
            if (n < 0) throw IOException("write failed")
            off += n
        }
    }

    override fun close() {
        open = false
        input.close()
        output.close()
    }
}
