package io.github.langkurst.ruru.ios

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LogRing
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iPhone: Bose headphones over Bluetooth LE, which iOS allows any app to use (no Apple approval,
 * no private accessory name). Bose's control service is FEBE; its encrypted characteristic
 * answers BMAP once every message carries the one-byte segment header ([LeTransport]). The
 * QC Ultra Headphones (1st Gen) answered this way from a Windows PC on 11 November 2026; the
 * plain characteristic stayed silent, so it is only the fallback.
 *
 * Not yet run on a real iPhone: the simulator has no Bluetooth.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLeHeadsetPlatform : HeadsetPlatform {
    override val name: String = "iPhone"
    override val supported: Boolean = true

    private val events = Events()
    private val known = HashMap<String, CBPeripheral>()

    // Made on first use: creating it makes iOS ask for Bluetooth permission, which demo mode
    // (and CI's simulator screenshot) should never trigger.
    private var created: CBCentralManager? = null
    private val central: CBCentralManager
        get() = created ?: CBCentralManager(delegate = events, queue = null).also { created = it }

    override val blocked: String?
        get() = when (created?.state) {
            CBManagerStatePoweredOff -> "Bluetooth is off. Turn it on in Control Centre, then come back."
            CBManagerStateUnauthorized -> "Ruru needs Bluetooth to reach your headphones. Allow it in Settings, Ruru, then come back."
            else -> null
        }

    override suspend fun headsets(): List<HeadsetCandidate> {
        if (!awaitPoweredOn()) return emptyList()
        val found = LinkedHashMap<String, Pair<CBPeripheral, String>>()
        // Headphones already linked to the iPhone over LE don't broadcast, so ask iOS for those first.
        central.retrieveConnectedPeripheralsWithServices(listOf(BOSE_SERVICE)).filterIsInstance<CBPeripheral>().forEach {
            found[it.identifier.UUIDString] = it to (it.name ?: "Bose headphones")
        }
        if (found.isEmpty()) {
            events.discovered.clear()
            central.scanForPeripheralsWithServices(null, null)
            delay(SCAN_MS)
            central.stopScan()
            events.discovered.values.forEach { (p, name) ->
                if (name.contains("Bose", ignoreCase = true)) found[p.identifier.UUIDString] = p to name
            }
        }
        LogRing.add('I', "iOS-LE", "found ${found.size} Bose device(s) over Bluetooth LE")
        found.forEach { (key, value) -> known[key] = value.first }
        return found.map { (key, value) -> HeadsetCandidate(key = key, name = value.second.removePrefix("LE-"), connected = true) }
    }

    override suspend fun open(candidate: HeadsetCandidate): ByteTransport {
        val p = known[candidate.key] ?: throw IOException("${candidate.name} is no longer nearby")
        events.session?.transport?.close()
        val s = Session(p)
        events.session = s
        p.delegate = events
        central.connectPeripheral(p, null)
        withTimeout(CONNECT_MS) { s.connected.await() }
        p.discoverServices(listOf(BOSE_SERVICE))
        withTimeout(STEP_MS) { s.servicesFound.await() }
        val service = p.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID == BOSE_SERVICE }
            ?: throw IOException("${candidate.name} has no Bose control service over Bluetooth LE")
        p.discoverCharacteristics(listOf(ENCRYPTED, PLAIN), service)
        withTimeout(STEP_MS) { s.characteristicsFound.await() }
        val characteristics = service.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty()
        val choices = listOfNotNull(characteristics.firstOrNull { it.UUID == ENCRYPTED }, characteristics.firstOrNull { it.UUID == PLAIN })
        if (choices.isEmpty()) throw IOException("${candidate.name} offers no Bose control characteristic")
        var subscribed: CBCharacteristic? = null
        for (c in choices) {
            // The encrypted one makes iOS ask to pair the first time; the person's answer can take a while.
            for (attempt in 1..2) {
                s.subscribed = CompletableDeferred()
                p.setNotifyValue(true, c)
                val ok = withTimeoutOrNull(if (c.UUID == ENCRYPTED) PAIRING_MS else STEP_MS) { runCatching { s.subscribed.await() }.isSuccess } ?: false
                if (ok) {
                    subscribed = c
                    break
                }
            }
            if (subscribed != null) break
        }
        val c = subscribed ?: throw IOException("${candidate.name} didn't let Ruru listen. If iPhone asked to pair, choose Pair, then try again.")
        s.characteristic = c
        val transport = LeTransport(
            maxWrite = { p.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt() },
            writePiece = { piece -> s.write(p, c, piece) },
            onClose = { central.cancelPeripheralConnection(p) },
        )
        s.transport = transport
        LogRing.add('I', "iOS-LE", "listening on ${if (c.UUID == ENCRYPTED) "the encrypted" else "the plain"} characteristic, writes up to ${p.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)} bytes")
        return transport
    }

    private suspend fun awaitPoweredOn(): Boolean {
        if (central.state == CBManagerStatePoweredOn) return true
        return withTimeoutOrNull(POWER_MS) {
            while (central.state != CBManagerStatePoweredOn) delay(100)
            true
        } ?: false
    }

    /** One connection: the steps of opening it, and the writes in flight. */
    private class Session(val peripheral: CBPeripheral) {
        val connected = CompletableDeferred<Unit>()
        val servicesFound = CompletableDeferred<Unit>()
        val characteristicsFound = CompletableDeferred<Unit>()
        var subscribed = CompletableDeferred<Unit>()
        var characteristic: CBCharacteristic? = null
        var transport: LeTransport? = null
        private val writing = Mutex()
        private var written: CompletableDeferred<Unit>? = null

        suspend fun write(p: CBPeripheral, c: CBCharacteristic, piece: ByteArray) = writing.withLock {
            val done = CompletableDeferred<Unit>()
            written = done
            p.writeValue(piece.toNSData(), c, CBCharacteristicWriteWithResponse)
            withTimeout(STEP_MS) { done.await() }
        }

        fun wrote(error: NSError?) {
            val done = written ?: return
            written = null
            if (error == null) done.complete(Unit) else done.completeExceptionally(IOException("write refused: ${error.localizedDescription}"))
        }

        fun failAll(reason: String) {
            val e = IOException(reason)
            listOf(connected, servicesFound, characteristicsFound, subscribed).forEach { it.completeExceptionally(e) }
            written?.completeExceptionally(e)
            transport?.dropped(reason)
        }
    }

    /** CoreBluetooth calls back here on the main queue, for the central and for the peripheral. */
    private class Events : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        var session: Session? = null
        val discovered = HashMap<String, Pair<CBPeripheral, String>>()

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state != CBManagerStatePoweredOn) session?.failAll("Bluetooth is no longer available")
        }

        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            val name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?: didDiscoverPeripheral.name ?: return
            discovered[didDiscoverPeripheral.identifier.UUIDString] = didDiscoverPeripheral to name
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            session?.takeIf { it.peripheral == didConnectPeripheral }?.connected?.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            session?.takeIf { it.peripheral == didFailToConnectPeripheral }?.failAll("Couldn't connect over Bluetooth LE: ${error?.localizedDescription ?: "no reason given"}")
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            session?.takeIf { it.peripheral == didDisconnectPeripheral }?.failAll("The headphones disconnected")
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val s = session ?: return
            if (didDiscoverServices == null) s.servicesFound.complete(Unit) else s.failAll("Couldn't read the headphones' services")
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val s = session ?: return
            if (error == null) s.characteristicsFound.complete(Unit) else s.failAll("Couldn't read the headphones' control channel")
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            val s = session ?: return
            if (error == null) s.subscribed.complete(Unit) else s.subscribed.completeExceptionally(IOException(error.localizedDescription))
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val s = session ?: return
            if (error != null || didUpdateValueForCharacteristic != s.characteristic) return
            didUpdateValueForCharacteristic.value?.toByteArray()?.let { s.transport?.deliver(it) }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            session?.wrote(error)
        }
    }

    private companion object {
        val BOSE_SERVICE: CBUUID = CBUUID.UUIDWithString("FEBE")
        val ENCRYPTED: CBUUID = CBUUID.UUIDWithString("C65B8F2F-AEE2-4C89-B758-BC4892D6F2D8")
        val PLAIN: CBUUID = CBUUID.UUIDWithString("D417C028-9818-4354-99D1-2AC09D074591")
        const val POWER_MS = 3_000L
        const val SCAN_MS = 4_000L
        const val CONNECT_MS = 10_000L
        const val STEP_MS = 5_000L
        const val PAIRING_MS = 30_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
