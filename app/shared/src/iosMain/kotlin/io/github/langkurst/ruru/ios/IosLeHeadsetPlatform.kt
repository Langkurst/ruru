package io.github.langkurst.ruru.ios

import io.github.langkurst.ruru.link.ByteTransport
import io.github.langkurst.ruru.link.ExplainedConnectException
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.link.HeadsetCandidate
import io.github.langkurst.ruru.link.HeadsetPlatform
import io.github.langkurst.ruru.link.HeadsetLink
import io.github.langkurst.ruru.link.LinkRoute
import io.github.langkurst.ruru.link.RouteKind
import io.github.langkurst.ruru.link.RouteNotOfferedException
import io.github.langkurst.ruru.link.LeTransport
import io.github.langkurst.ruru.link.LogRing
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.CoreBluetooth.CBATTErrorDomain
import platform.CoreBluetooth.CBATTErrorInsufficientAuthentication
import platform.CoreBluetooth.CBATTErrorInsufficientEncryption
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBErrorDomain
import platform.CoreBluetooth.CBErrorEncryptionTimedOut
import platform.CoreBluetooth.CBErrorPeerRemovedPairingInformation
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
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
    override val checksIdentity: Boolean = true

    /** Bose over LE (FEBE). Other makers' GATT links come in 0.3's B6. */
    override val routes: Set<RouteKind> = setOf(RouteKind.BOSE_LE)

    private val events = Events()
    private val known = HashMap<String, CBPeripheral>()
    // Devices that turned out not to be Bose during this run; never offered again.
    private val notBose = HashSet<String>()
    // Identifiers iOS reported as already connected over LE: their identity is iOS's to prove.
    private val retrievedIds = HashSet<String>()
    private var lastScanLine: String? = null
    private var nameClash = false

    // Made on first use: creating it makes iOS ask for Bluetooth permission, which demo mode
    // (and CI's simulator screenshot) should never trigger.
    private var created: CBCentralManager? = null
    private val central: CBCentralManager
        get() = created ?: CBCentralManager(delegate = events, queue = null).also { created = it }

    override val blocked: Words?
        get() {
            val permissionOrPower = when (CBCentralManager.authorization) {
                CBManagerAuthorizationNotDetermined -> Words("problem_ios_allow_bluetooth")
                CBManagerAuthorizationDenied -> Words("problem_ios_bluetooth_denied")
                CBManagerAuthorizationRestricted -> Words("problem_ios_bluetooth_restricted")
                else -> when (created?.state) {
                    CBManagerStatePoweredOff -> Words("problem_ios_bluetooth_off")
                    else -> null
                }
            }
            return permissionOrPower ?: if (nameClash) Words("problem_ios_name_clash") else null
        }

    override suspend fun headsets(): List<HeadsetCandidate> {
        if (!awaitPoweredOn()) return emptyList()
        val saved = savedPeripheralId()
        val seen = LinkedHashMap<String, Seen>()
        var outputs: List<AVAudioSessionPortDescription> = emptyList()
        var boseAdvertisers = 0
        var matched = 0
        var leftOut = 0
        var savedHeard = false
        nameClash = false
        retrievedIds.clear()
        // Headphones already linked to the iPhone over LE don't broadcast, so ask iOS for those first.
        central.retrieveConnectedPeripheralsWithServices(listOf(BOSE_SERVICE)).filterIsInstance<CBPeripheral>().forEach {
            val id = it.identifier.UUIDString
            if (id in notBose) return@forEach
            retrievedIds += id
            seen[id] = Seen(it, it.name ?: "Bose headphones", connected = true, rssi = Int.MIN_VALUE, saved = id == saved)
        }
        val alreadyConnected = seen.size
        if (seen.isEmpty()) {
            events.discovered.clear()
            try {
                central.scanForPeripheralsWithServices(null, null)
                delay(SCAN_MS)
            } finally {
                central.stopScan()
            }
            outputs = bluetoothAudioOutputs()
            val found = events.discovered.values.filter { it.peripheral.identifier.UUIDString !in notBose }
            leftOut = events.discovered.size - found.size
            val candidates = ArrayList<Seen>()
            found.forEach { discovered ->
                val p = discovered.peripheral
                val id = p.identifier.UUIDString
                val name = discovered.name.removePrefix("LE-")
                val isAudioOutput = matchesAudioOutput(name, outputs)
                val isSaved = id == saved
                val looksBose = name.contains("Bose", ignoreCase = true)
                if (looksBose) boseAdvertisers++
                if (isAudioOutput) matched++
                if (!looksBose && !isAudioOutput && !isSaved) return@forEach
                val candidate = Seen(p, discovered.name, connected = false, rssi = normaliseRssi(discovered.rssi), saved = isSaved)
                candidates += candidate
                seen[id] = candidate
            }
            savedHeard = candidates.any { it.saved }
            val audioMatches = candidates.filter { matchesAudioOutput(it.name.removePrefix("LE-"), outputs) }
            when {
                savedHeard -> candidates.forEach { if (it.saved) it.connected = true }
                audioMatches.size == 1 -> audioMatches.single().connected = true
                audioMatches.size > 1 -> nameClash = true
            }
        }
        savedHeard = seen.values.any { it.saved }
        val ordered = seen.values.sortedWith(
            compareByDescending<Seen> { it.saved }
                .thenByDescending { it.connected }
                .thenByDescending { it.name.contains("Bose", ignoreCase = true) }
                .thenByDescending { it.rssi },
        )
        val savedText = when {
            saved == null -> "none"
            savedHeard -> "heard"
            else -> "not heard"
        }
        val scanLine = "heard $boseAdvertisers Bose advertisers; $alreadyConnected already connected to iOS; " +
            "${outputs.size} Bluetooth audio output; $matched matched; saved headphones: $savedText; " +
            "$leftOut left out; ${if (nameClash) "name clash" else "no name clash"}"
        if (scanLine != lastScanLine) {
            lastScanLine = scanLine
            LogRing.add('I', "iOS-LE", scanLine)
        }
        ordered.forEach { known[it.peripheral.identifier.UUIDString] = it.peripheral }
        return ordered.map {
            HeadsetCandidate(
                key = it.peripheral.identifier.UUIDString,
                name = it.name.removePrefix("LE-"),
                connected = it.connected,
                link = HeadsetLink.LE,
            )
        }
    }

    override suspend fun open(candidate: HeadsetCandidate, route: LinkRoute): ByteTransport {
        if (route != LinkRoute.BoseLe) throw RouteNotOfferedException(name, route)
        val p = known[candidate.key]
            ?: throw ExplainedConnectException(otherOpenFailure(candidate.name), retry = true)
        events.session?.transport?.close()
        val s = Session(p)
        events.session = s
        p.delegate = events
        // Always connect through this app's central: a retrieved peripheral can be held by the
        // system or another app, and iOS calls didConnect at once when this app already has it.
        central.connectPeripheral(p, null)
        try {
            withTimeoutOrNull(CONNECT_MS) { s.connected.await() }
                ?: throw ExplainedConnectException(notResponding(candidate.name), retry = true)
            p.discoverServices(listOf(BOSE_SERVICE))
            withTimeoutOrNull(STEP_MS) { s.servicesFound.await() }
                ?: throw ExplainedConnectException(otherOpenFailure(candidate.name), retry = true)
            // A drop just after discovery clears the peripheral's services; that is not "not Bose".
            if (s.failed || p.state != CBPeripheralStateConnected) {
                throw ExplainedConnectException(disconnectedWhileOpening(candidate.name), retry = true)
            }
            val service = p.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID == BOSE_SERVICE }
                ?: run {
                    if (!s.failed && p.state == CBPeripheralStateConnected) notBose += p.identifier.UUIDString
                    throw ExplainedConnectException(noBoseControls(candidate.name), retry = true)
                }
            p.discoverCharacteristics(listOf(ENCRYPTED, PLAIN), service)
            withTimeoutOrNull(STEP_MS) { s.characteristicsFound.await() }
                ?: throw ExplainedConnectException(otherOpenFailure(candidate.name), retry = true)
            val characteristics = service.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty()
            val encrypted = characteristics.firstOrNull { it.UUID == ENCRYPTED }
            val plain = characteristics.firstOrNull { it.UUID == PLAIN }
            // The plain characteristic is only used when the encrypted one does not exist: the
            // QC Ultra (1st Gen) never answers BMAP on the plain one.
            val choices = if (encrypted != null) listOf(encrypted) else listOfNotNull(plain)
            if (choices.isEmpty()) throw ExplainedConnectException(otherOpenFailure(candidate.name), retry = true)
            var subscribed: CBCharacteristic? = null
            for (c in choices) {
                val needsPairing = c.UUID == ENCRYPTED
                // The encrypted one makes iOS ask to pair the first time; the person's answer can take a while.
                for (attempt in 1..2) {
                    if (s.failed || p.state != CBPeripheralStateConnected) throw disconnectWhileOpening(c, p, s, candidate.name)
                    s.subscribing = c
                    s.subscribed = CompletableDeferred()
                    s.lastError = null
                    p.setNotifyValue(true, c)
                    val result = withTimeoutOrNull(if (needsPairing) PAIRING_MS else STEP_MS) {
                        runCatching { s.subscribed.await() }
                    }
                    if (s.failed || p.state != CBPeripheralStateConnected) throw disconnectWhileOpening(c, p, s, candidate.name)
                    if (result != null && result.isSuccess) {
                        subscribed = c
                        break
                    }
                    if (needsPairing) {
                        // A timeout is a prompt the person ignored; only iOS's "still pairing"
                        // errors are worth a second try.
                        val retriable = result?.exceptionOrNull() != null && s.lastError.isRetriablePairingError()
                        if (retriable && attempt == 1) continue
                        throw ExplainedConnectException(notPaired(candidate.name), retry = false)
                    }
                }
                if (subscribed != null) break
            }
            val c = subscribed ?: throw ExplainedConnectException(otherOpenFailure(candidate.name), retry = true)
            s.characteristic = c
            s.subscribing = null
            val writeLimit = p.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()
            val transport = LeTransport(
                maxWrite = { writeLimit },
                writePiece = { piece -> s.write(p, c, piece) },
                onClose = { central.cancelPeripheralConnection(p) },
            )
            s.transport = transport
            LogRing.add('I', "iOS-LE", "listening on ${if (c.UUID == ENCRYPTED) "the encrypted" else "the plain"} characteristic, writes up to $writeLimit bytes")
            return transport
        } catch (e: Throwable) {
            central.cancelPeripheralConnection(p)
            if (e is ExplainedConnectException || e is CancellationException) throw e
            if (s.linkError.isStaleBondError()) {
                clearSavedPeripheralId()
                throw ExplainedConnectException(notPaired(candidate.name), retry = false)
            }
            throw ExplainedConnectException(
                if (s.failed) disconnectedWhileOpening(candidate.name) else otherOpenFailure(candidate.name),
                retry = true,
            )
        }
    }

    /**
     * The person's own set, proven by the address iOS gives for its audio output. Runs after
     * [open] and before Ruru sends anything but the address read; the addresses never leave here.
     */
    override suspend fun confirm(candidate: HeadsetCandidate, address: String?): Words? {
        val id = candidate.key
        val saved = savedPeripheralId()
        // iOS reported it over LE, or Ruru already saved it: no name match was involved.
        if (id in retrievedIds || id == saved) {
            if (id != saved) savePeripheralId(id)
            return null
        }
        // Picked by name: only its own address proves it is the set this iPhone plays through.
        if (address == null) return null
        val wanted = hexDigits(address)
        var sawOutputAddress = false
        var same = false
        for (output in bluetoothAudioOutputs()) {
            val outputAddress = addressFromUid(output.UID) ?: continue
            sawOutputAddress = true
            if (hexDigits(outputAddress) == wanted) {
                same = true
                break
            }
        }
        // Nothing to compare against: leave it unsaved so a later scan can still prove it.
        if (!sawOutputAddress) return null
        if (!same) {
            notBose += id
            return Words("problem_ios_other_set", candidate.name)
        }
        savePeripheralId(id)
        return null
    }

    private suspend fun awaitPoweredOn(): Boolean {
        if (central.state == CBManagerStatePoweredOn) return true
        return withTimeoutOrNull(POWER_MS) {
            while (central.state != CBManagerStatePoweredOn) delay(100)
            true
        } ?: false
    }

    private fun notPaired(name: String) = Words("problem_ios_not_paired", name)

    private fun notResponding(name: String) = Words("problem_ios_not_responding", name)

    private fun noBoseControls(name: String) = Words("problem_ios_no_bose_controls", name)

    private fun otherOpenFailure(name: String) = Words("problem_ios_no_answer", name)

    private fun disconnectedWhileOpening(name: String) = Words("problem_ios_disconnected", name)

    private fun disconnectWhileOpening(c: CBCharacteristic, p: CBPeripheral, s: Session, name: String): ExplainedConnectException {
        // The headphones were reset or dropped this iPhone: the old bond cannot be used again.
        if (s.linkError.isStaleBondError()) {
            clearSavedPeripheralId()
            return ExplainedConnectException(notPaired(name), retry = false)
        }
        // Declining the pairing alert can end the link before these headphones ever answered.
        val neverAnswered = p.identifier.UUIDString != savedPeripheralId()
        return if (c.UUID == ENCRYPTED && neverAnswered) {
            ExplainedConnectException(notPaired(name), retry = false)
        } else {
            ExplainedConnectException(disconnectedWhileOpening(name), retry = true)
        }
    }

    /** One connection: the steps of opening it, and the writes in flight. */
    private class Session(val peripheral: CBPeripheral) {
        val connected = CompletableDeferred<Unit>()
        val servicesFound = CompletableDeferred<Unit>()
        val characteristicsFound = CompletableDeferred<Unit>()
        var subscribed = CompletableDeferred<Unit>()
        var subscribing: CBCharacteristic? = null
        var lastError: NSError? = null
        /** The NSError of the failed connect or the disconnect, so a stale bond can be recognised. */
        var linkError: NSError? = null
        var failed = false
        var characteristic: CBCharacteristic? = null
        var transport: LeTransport? = null
        private val writing = Mutex()
        private var written: CompletableDeferred<Unit>? = null

        suspend fun write(p: CBPeripheral, c: CBCharacteristic, piece: ByteArray) = writing.withLock {
            val done = CompletableDeferred<Unit>()
            written = done
            p.writeValue(piece.toNSData(), c, CBCharacteristicWriteWithResponse)
            withTimeoutOrNull(STEP_MS) { done.await() }
                ?: throw IOException("The headphones didn't accept a write over Bluetooth LE")
        }

        fun wrote(error: NSError?) {
            val done = written ?: return
            written = null
            if (error == null) done.complete(Unit) else done.completeExceptionally(IOException("write refused: ${error.localizedDescription}"))
        }

        fun failAll(reason: String) {
            failed = true
            val e = IOException(reason)
            listOf(connected, servicesFound, characteristicsFound, subscribed).forEach { it.completeExceptionally(e) }
            written?.completeExceptionally(e)
            transport?.dropped(reason)
        }
    }

    /** CoreBluetooth calls back here on the main queue, for the central and for the peripheral. */
    private class Events : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        var session: Session? = null
        val discovered = HashMap<String, Discovered>()

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state != CBManagerStatePoweredOn) session?.failAll("Bluetooth is no longer available")
        }

        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            val name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?: didDiscoverPeripheral.name ?: return
            discovered[didDiscoverPeripheral.identifier.UUIDString] = Discovered(didDiscoverPeripheral, name, RSSI.intValue)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            session?.takeIf { it.peripheral == didConnectPeripheral }?.connected?.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            session?.takeIf { it.peripheral == didFailToConnectPeripheral }?.let {
                it.linkError = error
                it.failAll("Couldn't connect over Bluetooth LE: ${error?.localizedDescription ?: "no reason given"}")
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            session?.takeIf { it.peripheral == didDisconnectPeripheral }?.let {
                it.linkError = error
                it.failAll(DISCONNECTED)
            }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val s = session?.takeIf { it.peripheral == peripheral } ?: return
            if (didDiscoverServices == null) s.servicesFound.complete(Unit) else s.failAll("Couldn't read the headphones' services")
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val s = session?.takeIf { it.peripheral == peripheral } ?: return
            if (error == null) s.characteristicsFound.complete(Unit) else s.failAll("Couldn't read the headphones' control channel")
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            val s = session?.takeIf { it.peripheral == peripheral } ?: return
            if (didUpdateNotificationStateForCharacteristic != s.subscribing) return
            if (error == null) {
                s.subscribed.complete(Unit)
            } else {
                s.lastError = error
                s.subscribed.completeExceptionally(IOException(error.localizedDescription))
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val s = session?.takeIf { it.peripheral == peripheral } ?: return
            if (error != null || didUpdateValueForCharacteristic != s.characteristic) return
            didUpdateValueForCharacteristic.value?.toByteArray()?.let { s.transport?.deliver(it) }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            session?.takeIf { it.peripheral == peripheral }?.wrote(error)
        }
    }

    private companion object {
        val BOSE_SERVICE: CBUUID = CBUUID.UUIDWithString("FEBE")
        val ENCRYPTED: CBUUID = CBUUID.UUIDWithString("C65B8F2F-AEE2-4C89-B758-BC4892D6F2D8")
        val PLAIN: CBUUID = CBUUID.UUIDWithString("D417C028-9818-4354-99D1-2AC09D074591")
        const val DISCONNECTED = "The headphones disconnected"
        const val POWER_MS = 3_000L
        const val SCAN_MS = 4_000L
        const val CONNECT_MS = 10_000L
        const val STEP_MS = 5_000L
        const val PAIRING_MS = 30_000L
    }
}

private class Seen(
    val peripheral: CBPeripheral,
    val name: String,
    var connected: Boolean,
    val rssi: Int,
    val saved: Boolean = false,
)

private class Discovered(val peripheral: CBPeripheral, val name: String, val rssi: Int)

/** CoreBluetooth reports 127 when the signal strength is unavailable; such values are weakest. */
private fun normaliseRssi(rssi: Int): Int = if (rssi > 20) Int.MIN_VALUE else rssi

/** iOS reports these while pairing finishes; a second subscribe then works. */
private fun NSError?.isRetriablePairingError(): Boolean =
    this != null && domain == CBATTErrorDomain &&
        (code.toInt() == CBATTErrorInsufficientAuthentication.toInt() ||
            code.toInt() == CBATTErrorInsufficientEncryption.toInt())

/** The headphones were reset or dropped this iPhone: iOS still holds the old pairing keys. */
private fun NSError?.isStaleBondError(): Boolean =
    this != null && domain == CBErrorDomain &&
        (code.toInt() == CBErrorPeerRemovedPairingInformation.toInt() ||
            code.toInt() == CBErrorEncryptionTimedOut.toInt())

/** The six colon-separated hex pairs at the start of an output UID, e.g. `aa:bb:cc:dd:ee:ff-tacl`. */
private val UID_ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

private fun addressFromUid(uid: String): String? = UID_ADDRESS.find(uid)?.value

/** Hex digits only, so case and separators do not matter. */
private fun hexDigits(address: String): String = address.filter { it.isLetterOrDigit() }.lowercase()

private const val SAVED_PERIPHERAL_KEY = "RuruLePeripheralId"

private fun savedPeripheralId(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(SAVED_PERIPHERAL_KEY)?.takeIf { it.isNotBlank() }

private fun savePeripheralId(id: String) {
    NSUserDefaults.standardUserDefaults.setObject(id, SAVED_PERIPHERAL_KEY)
}

private fun clearSavedPeripheralId() {
    NSUserDefaults.standardUserDefaults.removeObjectForKey(SAVED_PERIPHERAL_KEY)
}

@OptIn(ExperimentalForeignApi::class)
private fun bluetoothAudioOutputs(): List<AVAudioSessionPortDescription> =
    AVAudioSession.sharedInstance().currentRoute.outputs
        .filterIsInstance<AVAudioSessionPortDescription>()
        .filter {
            it.portType == AVAudioSessionPortBluetoothA2DP ||
                it.portType == AVAudioSessionPortBluetoothHFP ||
                it.portType == AVAudioSessionPortBluetoothLE
        }

@OptIn(ExperimentalForeignApi::class)
private fun matchesAudioOutput(advertisedName: String, outputs: List<AVAudioSessionPortDescription>): Boolean =
    outputs.any { output ->
        output.portName.equals(advertisedName, ignoreCase = true) ||
            (advertisedName.length >= 20 && output.portName.startsWith(advertisedName, ignoreCase = true))
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
