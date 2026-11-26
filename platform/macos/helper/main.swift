// ruru-bt-mac: Ruru's Bluetooth helper for macOS. The JVM cannot call IOBluetooth or CoreBluetooth,
// so this small program does, one job per run, and talks to Ruru over its command line, stdout and
// stdin (the Windows helper ruru-ble-win does the same, with the same frames).
//
//   ruru-bt-mac list
//       "DEV\t<key>\t<1|0 connected>\t<classic|le>\t<class of device, hex>\t<cached service UUIDs, comma
//       separated>\t<name>" per paired device, then "END\t<count>". Classic devices come from
//       IOBluetooth. LE-only devices that are connected right now are added only when the person has
//       already allowed Bluetooth for Ruru (a list never opens the permission question).
//   ruru-bt-mac connect <key> [channel]
//       Bose: bridges the Serial Port (RFCOMM) channel, taken from the device's SDP record for the
//       Serial Port service when it has one, else the argument. Raw bytes: headset -> stdout,
//       stdin -> headset. Status on stderr: "OPEN <channel>" or "ERR\t<code>\t<words>".
//   ruru-bt-mac connect-uuid <key> <uuid128>
//       Any other maker's RFCOMM service: an SDP query for that service UUID, then the RFCOMM channel
//       its record names. If the headset has no such record, "ERR\tservice-not-found" and nothing was
//       opened. A channel number is never guessed. Same bytes and status lines as connect.
//   ruru-bt-mac gatt <key> <service> <write> <notify...> [--without-response]
//       A GATT link through CoreBluetooth to the one service named. stdout: "OPEN\t<max pdu>\t<1|0
//       with response>" or "ERR\t<code>\t<words>", then frames (type byte, two-byte big-endian length,
//       payload): to the helper 'W' bytes for the write characteristic, 'Q' close; from the helper
//       'N' a notification, 'K' a write result (0x00, or 0x01 and the reason), 'D' the link dropped.
//       Firmware-update services are refused.
//   ruru-bt-mac scan
//       Not supported: macOS never hands an app Apple's adverts (AirPods, Beats), which is all
//       Ruru would listen for. Answers "ERR\tunsupported".
//   ruru-bt-mac selftest
//       Checks the parsing and rules in Protocol.swift, prints "selftest OK" (CI runs it).
//
// Safety (CLAUDE.md): a <key> is a salted hash of the address (salt from RURU_BT_SALT), so no
// Bluetooth address crosses stdout. The helper never pairs, unpairs or bonds, opens only the one
// service it is told, refuses firmware-update GATT services, and sends nothing on the Apple
// accessory (iAP2) channel.
import Foundation
import IOBluetooth
import CoreBluetooth

setvbuf(stdout, nil, _IONBF, 0)

let salt = ProcessInfo.processInfo.environment["RURU_BT_SALT"] ?? ""
let outLock = NSLock()

func writeOut(_ data: Data) {
    outLock.lock()
    FileHandle.standardOutput.write(data)
    outLock.unlock()
}

func outLine(_ line: String) {
    writeOut((line + "\n").data(using: .utf8)!)
}

func status(_ line: String) {
    FileHandle.standardError.write((line + "\n").data(using: .utf8)!)
}

/// Stops an RFCOMM job: the reason goes on stderr (stdout carries the headset's bytes).
func failRfcomm(_ code: String, _ words: String, _ exitCode: Int32) -> Never {
    status(Proto.errLine(code, words))
    exit(exitCode)
}

/// Runs the main run loop until `done` or `seconds` pass.
func pump(until done: () -> Bool, seconds: Double) -> Bool {
    let end = Date(timeIntervalSinceNow: seconds)
    while !done() && Date() < end {
        RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.05))
    }
    return done()
}

// ---- Paired classic devices ---------------------------------------------------------------------

struct Paired {
    let device: IOBluetoothDevice
    let key: String
}

func pairedDevices() -> [Paired] {
    let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
    return devices.map {
        Paired(device: $0, key: Proto.deviceKey(salt: salt, id: Proto.normaliseAddress($0.addressString ?? "")))
    }
}

/// The service class UUIDs of one SDP record (attribute 0x0001), in full lower-case form.
func classUUIDs(_ record: IOBluetoothSDPServiceRecord) -> [String] {
    guard let element = record.getAttributeDataElement(0x0001),
          let items = element.getArrayValue() as? [IOBluetoothSDPDataElement] else { return [] }
    var out = [String]()
    for item in items {
        guard let u = item.getUUIDValue() else { continue }
        if let s = Proto.uuidString(bytes: [UInt8](Data(referencing: u))) { out.append(s) }
    }
    return out
}

/// The service UUIDs the system already holds for a device (no query, nothing sent to it).
func cachedUUIDs(_ device: IOBluetoothDevice) -> [String] {
    var seen = Set<String>()
    var out = [String]()
    for case let record as IOBluetoothSDPServiceRecord in (device.services ?? []) {
        for u in classUUIDs(record) where seen.insert(u).inserted { out.append(u) }
    }
    return out
}

final class SdpWaiter: NSObject {
    var done = false
    var result: IOReturn = kIOReturnSuccess

    @objc func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        result = status
        done = true
    }
}

/// Asks the device for its SDP records (a read; no service is opened), then looks for the record
/// whose service class is `uuid`. `queryOK` is false when the query itself failed, so "the headset
/// has no such service" is only ever said after a query that worked.
func findServiceRecord(_ device: IOBluetoothDevice, uuid: String) -> (record: IOBluetoothSDPServiceRecord?, queryOK: Bool) {
    let waiter = SdpWaiter()
    var ok = device.performSDPQuery(waiter) == kIOReturnSuccess
    if ok { ok = pump(until: { waiter.done }, seconds: 12) && waiter.result == kIOReturnSuccess }
    for case let record as IOBluetoothSDPServiceRecord in (device.services ?? []) where classUUIDs(record).contains(uuid) {
        return (record, ok)
    }
    return (nil, ok)
}

// ---- RFCOMM bridge ------------------------------------------------------------------------------

final class Bridge: NSObject, IOBluetoothRFCOMMChannelDelegate {
    func rfcommChannelData(_ channel: IOBluetoothRFCOMMChannel!, data: UnsafeMutableRawPointer!, length: Int) {
        writeOut(Data(bytes: data, count: length))
    }

    func rfcommChannelClosed(_ channel: IOBluetoothRFCOMMChannel!) {
        status("CLOSED")
        exit(0)
    }
}

/// Opens `channelID` on `device` and bridges it to stdin/stdout until either side closes.
func bridgeRfcomm(_ device: IOBluetoothDevice, channelID: BluetoothRFCOMMChannelID) -> Never {
    let bridge = Bridge()
    var rfcomm: IOBluetoothRFCOMMChannel?
    let result = device.openRFCOMMChannelSync(&rfcomm, withChannelID: channelID, delegate: bridge)
    guard result == kIOReturnSuccess, let open = rfcomm else {
        failRfcomm("open-failed", "could not open channel \(channelID) (\(result))", 3)
    }
    status("OPEN \(channelID)")

    Thread.detachNewThread {
        let input = FileHandle.standardInput
        while true {
            let data = input.availableData
            if data.isEmpty {
                DispatchQueue.main.async {
                    open.close()
                    exit(0)
                }
                return
            }
            DispatchQueue.main.async {
                var bytes = [UInt8](data)
                _ = open.writeSync(&bytes, length: UInt16(bytes.count))
            }
        }
    }
    RunLoop.main.run()
    exit(0)
}

func cmdConnect(_ rest: [String]) -> Never {
    guard rest.count >= 1, Proto.isKey(rest[0]) else {
        failRfcomm("usage", "usage: connect <device key> [channel]", 2)
    }
    guard let paired = pairedDevices().first(where: { $0.key == rest[0] }) else {
        failRfcomm("not-paired", "that device is no longer paired with this Mac", 3)
    }
    var channelID = BluetoothRFCOMMChannelID(rest.count >= 2 ? (UInt8(rest[1]) ?? 2) : 2)
    if let record = paired.device.getServiceRecord(for: IOBluetoothSDPUUID(uuid16: 0x1101)) {
        var found: BluetoothRFCOMMChannelID = 0
        if record.getRFCOMMChannelID(&found) == kIOReturnSuccess && found != 0 {
            channelID = found
        }
    }
    bridgeRfcomm(paired.device, channelID: channelID)
}

func cmdConnectUuid(_ rest: [String]) -> Never {
    guard rest.count == 2, Proto.isKey(rest[0]), Proto.isUUID128(rest[1]) else {
        failRfcomm("usage", "usage: connect-uuid <device key> <lower-case 128-bit uuid>", 2)
    }
    guard let paired = pairedDevices().first(where: { $0.key == rest[0] }) else {
        failRfcomm("not-paired", "that device is no longer paired with this Mac", 3)
    }
    let found = findServiceRecord(paired.device, uuid: rest[1])
    guard let record = found.record else {
        if found.queryOK {
            failRfcomm("service-not-found", "the headphones do not offer that service", 4)
        }
        failRfcomm("sdp-failed", "could not ask the headphones which services they offer", 5)
    }
    var channel: BluetoothRFCOMMChannelID = 0
    guard record.getRFCOMMChannelID(&channel) == kIOReturnSuccess, channel != 0 else {
        // A record that names no RFCOMM channel cannot be opened as one, and no channel is guessed.
        failRfcomm("service-not-found", "the headphones do not offer that service over RFCOMM", 4)
    }
    bridgeRfcomm(paired.device, channelID: channel)
}

// ---- list ---------------------------------------------------------------------------------------

final class LeCentral: NSObject, CBCentralManagerDelegate {
    var manager: CBCentralManager!
    var state: CBManagerState = .unknown

    override init() {
        super.init()
        manager = CBCentralManager(delegate: self, queue: nil)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        state = central.state
    }
}

func cmdList() -> Never {
    var count = 0
    var classicNames = Set<String>()
    for p in pairedDevices() {
        let name = p.device.name ?? ""
        classicNames.insert(Proto.bareName(name))
        outLine(Proto.devLine(
            key: p.key, connected: p.device.isConnected(), link: "classic",
            classOfDevice: UInt32(p.device.classOfDevice), uuids: cachedUUIDs(p.device), name: name))
        count += 1
    }
    // LE-only devices: only once Bluetooth is already allowed, so listing never raises the question.
    if CBManager.authorization == .allowedAlways {
        let central = LeCentral()
        let ready = pump(until: { central.state != .unknown && central.state != .resetting }, seconds: 3)
        if ready && central.state == .poweredOn {
            for p in central.manager.retrieveConnectedPeripherals(withServices: [CBUUID(string: "1800")]) {
                let name = p.name ?? ""
                if name.isEmpty || classicNames.contains(Proto.bareName(name)) { continue }
                outLine(Proto.devLine(
                    key: Proto.deviceKey(salt: salt, id: p.identifier.uuidString.lowercased()),
                    connected: true, link: "le", classOfDevice: 0, uuids: [], name: name))
                count += 1
            }
        }
    }
    outLine("END\t\(count)")
    exit(0)
}

// ---- GATT ---------------------------------------------------------------------------------------

/// Reads exactly `count` bytes from `input`, or fewer at the end of the stream.
func readExactly(_ input: FileHandle, _ count: Int) -> Data {
    var data = Data()
    while data.count < count {
        let piece = input.readData(ofLength: count - data.count)
        if piece.isEmpty { break }
        data.append(piece)
    }
    return data
}

func failGatt(_ code: String, _ words: String) -> Never {
    outLine(Proto.errLine(code, words))
    exit(4)
}

final class GattBridge: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    let args: GattArgs
    let serviceID: CBUUID
    let writeID: CBUUID
    let notifyIDs: [CBUUID]
    /// The paired classic device's name when the key is a classic one: its LE side is found by name.
    let wantedName: String?
    var manager: CBCentralManager!
    var peripheral: CBPeripheral?
    var writeChar: CBCharacteristic?
    var notifyChars: [CBCharacteristic] = []
    var notifyIndex = 0
    var opened = false
    var closing = false
    var early: [Data] = []
    var pendingWithoutResponse: Data?
    var timeout: Timer?

    init(args: GattArgs, wantedName: String?) {
        self.args = args
        self.serviceID = CBUUID(string: args.service)
        self.writeID = CBUUID(string: args.write)
        self.notifyIDs = args.notify.map { CBUUID(string: $0) }
        self.wantedName = wantedName
        super.init()
        manager = CBCentralManager(delegate: self, queue: nil)
        timeout = Timer.scheduledTimer(withTimeInterval: 35, repeats: false) { _ in
            failGatt("timeout", "the headphones did not answer over Bluetooth LE in time")
        }
    }

    func matches(_ p: CBPeripheral, advertisedName: String?) -> Bool {
        if Proto.deviceKey(salt: salt, id: p.identifier.uuidString.lowercased()) == args.key { return true }
        guard let wanted = wantedName, !wanted.isEmpty else { return false }
        return Proto.bareName(p.name ?? advertisedName ?? "") == wanted
    }

    // Central

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: findPeripheral()
        case .poweredOff: failGatt("bluetooth-off", "Bluetooth is turned off on this Mac")
        case .unauthorized:
            failGatt("unauthorised", "Ruru is not allowed to use Bluetooth. Allow it in System Settings, Privacy and Security, Bluetooth")
        case .unsupported: failGatt("unsupported", "this Mac cannot use Bluetooth LE")
        default: break
        }
    }

    func findPeripheral() {
        for p in manager.retrieveConnectedPeripherals(withServices: [serviceID]) where matches(p, advertisedName: nil) {
            connect(p)
            return
        }
        // Not connected to the Mac as LE right now: listen for it. Nothing is sent while listening.
        manager.scanForPeripherals(withServices: nil, options: nil)
    }

    func connect(_ p: CBPeripheral) {
        manager.stopScan()
        peripheral = p
        p.delegate = self
        manager.connect(p, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if self.peripheral == nil, matches(peripheral, advertisedName: advertisementData[CBAdvertisementDataLocalNameKey] as? String) {
            connect(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        failGatt("connect-failed", "could not connect over Bluetooth LE")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if closing { exit(0) }
        if opened {
            writeOut(Proto.encodeFrame(UInt8(ascii: "D"), "the headphones disconnected".data(using: .utf8)!))
            exit(0)
        }
        failGatt("connect-failed", "the headphones disconnected while opening the link")
    }

    // Peripheral

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else {
            failGatt("service-not-found", "the headphones do not offer that service")
        }
        var wanted = [writeID]
        for id in notifyIDs where !wanted.contains(id) { wanted.append(id) }
        peripheral.discoverCharacteristics(wanted, for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let found = service.characteristics ?? []
        guard error == nil, let write = found.first(where: { $0.uuid == writeID }) else {
            failGatt("characteristic-not-found", "the headphones do not offer the characteristic Ruru writes to")
        }
        writeChar = write
        notifyChars = notifyIDs.compactMap { id in found.first(where: { $0.uuid == id }) }
        guard notifyChars.count == notifyIDs.count else {
            failGatt("characteristic-not-found", "the headphones do not offer a characteristic Ruru listens to")
        }
        enableNextNotification()
    }

    func enableNextNotification() {
        guard let p = peripheral else { return }
        if notifyIndex < notifyChars.count {
            p.setNotifyValue(true, for: notifyChars[notifyIndex])
        } else {
            open(p)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if error != nil { failGatt("notify-failed", "the headphones refused notifications") }
        notifyIndex += 1
        enableNextNotification()
    }

    func open(_ p: CBPeripheral) {
        timeout?.invalidate()
        let type: CBCharacteristicWriteType = args.withResponse ? .withResponse : .withoutResponse
        // What one write carries, plus the three bytes ATT adds (the Windows helper reports the PDU).
        outLine("OPEN\t\(p.maximumWriteValueLength(for: type) + 3)\t\(args.withResponse ? 1 : 0)")
        opened = true
        for v in early { writeOut(Proto.encodeFrame(UInt8(ascii: "N"), v)) }
        early.removeAll()
        startReadingStdin()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        if opened {
            writeOut(Proto.encodeFrame(UInt8(ascii: "N"), value))
        } else {
            early.append(value)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        sendWritten(error.map { $0.localizedDescription })
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        if let data = pendingWithoutResponse, let c = writeChar {
            pendingWithoutResponse = nil
            peripheral.writeValue(data, for: c, type: .withoutResponse)
            sendWritten(nil)
        }
    }

    // Frames from Ruru

    func sendWritten(_ failure: String?) {
        let payload = failure.map { Data([1]) + ($0.data(using: .utf8) ?? Data()) } ?? Data([0])
        writeOut(Proto.encodeFrame(UInt8(ascii: "K"), payload))
    }

    func startReadingStdin() {
        Thread.detachNewThread { [self] in
            let input = FileHandle.standardInput
            while true {
                let head = [UInt8](readExactly(input, 3))
                guard head.count == 3 else { break }
                let size = Int(head[1]) << 8 | Int(head[2])
                let payload = size > 0 ? readExactly(input, size) : Data()
                guard payload.count == size else { break }
                DispatchQueue.main.async { self.handle(head[0], payload) }
            }
            DispatchQueue.main.async { self.close() }
        }
    }

    func handle(_ type: UInt8, _ payload: Data) {
        switch type {
        case UInt8(ascii: "W"): write(payload)
        case UInt8(ascii: "Q"): close()
        default: break
        }
    }

    func write(_ data: Data) {
        guard opened, let p = peripheral, let c = writeChar else {
            sendWritten("the Bluetooth LE link is not open")
            return
        }
        if args.withResponse {
            p.writeValue(data, for: c, type: .withResponse)
        } else if p.canSendWriteWithoutResponse {
            p.writeValue(data, for: c, type: .withoutResponse)
            sendWritten(nil)
        } else {
            pendingWithoutResponse = data
        }
    }

    func close() {
        if closing { return }
        closing = true
        if let p = peripheral { manager.cancelPeripheralConnection(p) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { exit(0) }
    }
}

func cmdGatt(_ rest: [String]) -> Never {
    switch Proto.parseGatt(rest) {
    case .failure(let e):
        failGatt(e.code, e.words)
    case .success(let a):
        let name = pairedDevices().first(where: { $0.key == a.key }).map { Proto.bareName($0.device.name ?? "") }
        let bridge = GattBridge(args: a, wantedName: name)
        withExtendedLifetime(bridge) { RunLoop.main.run() }
        exit(0)
    }
}

// ---- Dispatch -----------------------------------------------------------------------------------

let argv = Array(CommandLine.arguments.dropFirst())
switch argv.first ?? "" {
case "list":
    cmdList()
case "connect":
    cmdConnect(Array(argv.dropFirst()))
case "connect-uuid":
    cmdConnectUuid(Array(argv.dropFirst()))
case "gatt":
    cmdGatt(Array(argv.dropFirst()))
case "scan":
    outLine(Proto.errLine("unsupported", "macOS does not let apps hear AirPods or Beats adverts"))
    exit(5)
case "selftest":
    let bad = Proto.selfTest()
    if bad.isEmpty {
        print("selftest OK")
        exit(0)
    }
    for b in bad { print("selftest FAILED: \(b)") }
    exit(1)
default:
    status("ERR\tusage\tusage: ruru-bt-mac list | connect <key> [channel] | connect-uuid <key> <uuid> | gatt <key> <service> <write> <notify...> | scan | selftest")
    exit(2)
}
