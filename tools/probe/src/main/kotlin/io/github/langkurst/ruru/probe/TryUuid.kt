package io.github.langkurst.ruru.probe

import io.github.langkurst.ruru.platform.windows.ServiceNotFoundException
import io.github.langkurst.ruru.platform.windows.WinBluetooth
import kotlin.system.exitProcess

/**
 * `--try-uuid=<uuid>`: asks Windows to open that RFCOMM service on the paired Bose headset and
 * sends nothing, ever. Hardware check H2: with Sony's service UUID the Bose has no such service, so
 * Windows must answer "service not found" and 0 bytes are written. If the service does exist, the
 * channel is closed at once without a byte. Prints no address.
 */
object TryUuid {
    private val UUID_TEXT = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    /** The UUID of a `--try-uuid=` argument, lower case, or null when there is none; throws on a malformed one. */
    fun parse(args: Array<String>): String? {
        val raw = args.firstOrNull { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val uuid = raw.trim().lowercase()
        require(UUID_TEXT.matches(uuid)) { "--try-uuid wants a UUID like 96cc203e-5068-46ad-b32d-e316f5e069ba" }
        return uuid
    }

    fun run(uuid: String) {
        val bose = WinBluetooth.pairedDevices().filter { it.name.contains("Bose", ignoreCase = true) }.sortedByDescending { it.connected }
        val device = bose.firstOrNull() ?: run {
            println("RESULT: no paired Bose headset found in Windows' Bluetooth list")
            exitProcess(2)
        }
        println("Headset: ${device.name} (${if (device.connected) "connected" else "paired, not connected"})")
        println("Asking Windows for RFCOMM service $uuid (port 0, SDP lookup); sending nothing")
        try {
            WinBluetooth.openService(device, uuid).close()
            println("RESULT: UNEXPECTED: the headset has that service. Closed at once, 0 bytes written")
            exitProcess(4)
        } catch (e: ServiceNotFoundException) {
            println("RESULT: service not found (Winsock ${e.code}), 0 bytes written")
        } catch (e: Exception) {
            println("RESULT: not opened, 0 bytes written: ${e.message}")
            exitProcess(3)
        }
    }

    private const val PREFIX = "--try-uuid="
}
