// The helper's own rules that need no Bluetooth: device keys, UUID text, the answer lines and the
// frames. Kept apart from main.swift so `ruru-bt-mac selftest` (run by CI on the Mac runner) can
// check every one of them without a headset.
import Foundation
import CryptoKit

struct ProtoError: Error {
    let code: String
    let words: String
}

struct GattArgs {
    let key: String
    let service: String
    let write: String
    let notify: [String]
    let withResponse: Bool
}

enum Proto {
    /// GATT services that update firmware, by prefix: the same list as GattLinkSpec.isFirmwareService
    /// in core/session. Recognising one is fine; opening one never is.
    static let firmwarePrefixes = [
        "66666666-", "86868686-",
        "01000100-0000-1000-8000-", "03000300-0000-1000-8000-", "05000500-0000-1000-8000-",
        "06000600-0000-1000-8000-", "07000700-0000-1000-8000-",
        "65786365-6c70-6f69-6e74-2e636f82",
    ]

    static let maxPayload = 0xFFFF

    static func isFirmwareService(_ uuid: String) -> Bool {
        let s = uuid.lowercased()
        return firmwarePrefixes.contains { s.hasPrefix($0) }
    }

    /// A lower-case 128-bit UUID in 8-4-4-4-12 form.
    static func isUUID128(_ s: String) -> Bool {
        let c = Array(s.utf8)
        guard c.count == 36 else { return false }
        for (i, b) in c.enumerated() {
            if i == 8 || i == 13 || i == 18 || i == 23 {
                if b != 0x2d { return false }
            } else if !((b >= 0x30 && b <= 0x39) || (b >= 0x61 && b <= 0x66)) {
                return false
            }
        }
        return true
    }

    /// The 16 bytes of a UUID that passed isUUID128.
    static func uuidBytes(_ s: String) -> [UInt8]? {
        guard isUUID128(s) else { return nil }
        let hex = Array(s.replacingOccurrences(of: "-", with: "").utf8)
        var out = [UInt8]()
        var i = 0
        while i + 1 < hex.count {
            guard let hi = nibble(hex[i]), let lo = nibble(hex[i + 1]) else { return nil }
            out.append(UInt8(hi << 4 | lo))
            i += 2
        }
        return out
    }

    private static func nibble(_ b: UInt8) -> Int? {
        if b >= 0x30 && b <= 0x39 { return Int(b) - 0x30 }
        if b >= 0x61 && b <= 0x66 { return Int(b) - 0x61 + 10 }
        return nil
    }

    /// A UUID as the system keeps it (2, 4 or 16 bytes) in full lower-case form on the Bluetooth base UUID.
    static func uuidString(bytes: [UInt8]) -> String? {
        let base: [UInt8] = [0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb]
        var full: [UInt8]
        switch bytes.count {
        case 2: full = [0, 0] + bytes + base
        case 4: full = bytes + base
        case 16: full = bytes
        default: return nil
        }
        let h = full.map { String(format: "%02x", $0) }
        return h[0..<4].joined() + "-" + h[4..<6].joined() + "-" + h[6..<8].joined() + "-" + h[8..<10].joined() + "-" + h[10..<16].joined()
    }

    /// An address in the one form the helper hashes: lower case with dashes.
    static func normaliseAddress(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: ":", with: "-")
    }

    /// The opaque name Ruru sees for a device: 16 hex characters of a salted hash of its address (or,
    /// for an LE peripheral, its CoreBluetooth identifier). Addresses never leave this program.
    static func deviceKey(salt: String, id: String) -> String {
        let digest = SHA256.hash(data: Data((salt + "|" + id).utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    static func isKey(_ s: String) -> Bool {
        let c = Array(s.utf8)
        return c.count == 16 && c.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
    }

    /// A name safe for a tab-separated line.
    static func cleanName(_ s: String) -> String {
        s.replacingOccurrences(of: "\t", with: " ").replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ").trimmingCharacters(in: .whitespaces)
    }

    /// A name for comparing a classic device with its LE twin ("LE-" prefix, case).
    static func bareName(_ s: String) -> String {
        var n = cleanName(s)
        for p in ["LE-", "LE_"] where n.uppercased().hasPrefix(p) { n = String(n.dropFirst(p.count)) }
        return n.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// `DEV <key> <1|0 connected> <classic|le> <class of device, hex> <cached service UUIDs, comma separated> <name>`
    static func devLine(key: String, connected: Bool, link: String, classOfDevice: UInt32, uuids: [String], name: String) -> String {
        ["DEV", key, connected ? "1" : "0", link, String(classOfDevice, radix: 16), uuids.joined(separator: ","), cleanName(name)]
            .joined(separator: "\t")
    }

    /// `ERR <code> <words>` with no tab or newline in the words.
    static func errLine(_ code: String, _ words: String) -> String {
        "ERR\t\(code)\t\(cleanName(words))"
    }

    // ---- Frames: one type byte, a two-byte big-endian length, the payload (as ruru-ble-win) ----

    static func encodeFrame(_ type: UInt8, _ payload: Data = Data()) -> Data {
        precondition(payload.count <= maxPayload, "a frame holds at most \(maxPayload) bytes")
        var out = Data([type, UInt8(payload.count >> 8), UInt8(payload.count & 0xFF)])
        out.append(payload)
        return out
    }

    /// The first frame in `bytes` and how many bytes it used, or nil when it is not all there yet.
    static func decodeFrame(_ bytes: Data) -> (type: UInt8, payload: Data, used: Int)? {
        let b = [UInt8](bytes)
        guard b.count >= 3 else { return nil }
        let size = Int(b[1]) << 8 | Int(b[2])
        guard b.count >= 3 + size else { return nil }
        return (b[0], Data(b[3..<(3 + size)]), 3 + size)
    }

    /// `gatt <key> <service> <write> <notify...> [--without-response]`, checked before anything opens.
    static func parseGatt(_ rest: [String]) -> Result<GattArgs, ProtoError> {
        var a = rest
        var withResponse = true
        if let i = a.firstIndex(of: "--without-response") {
            withResponse = false
            a.remove(at: i)
        }
        guard a.count >= 4 else {
            return .failure(ProtoError(code: "usage", words: "usage: gatt <key> <service> <write> <notify...> [--without-response]"))
        }
        guard isKey(a[0]) else { return .failure(ProtoError(code: "usage", words: "not a device key")) }
        guard a[1...].allSatisfy(isUUID128) else {
            return .failure(ProtoError(code: "usage", words: "UUIDs must be lower-case 128-bit"))
        }
        if isFirmwareService(a[1]) {
            return .failure(ProtoError(code: "firmware", words: "that is a firmware-update service; Ruru never opens one"))
        }
        return .success(GattArgs(key: a[0], service: a[1], write: a[2], notify: Array(a[3...]), withResponse: withResponse))
    }

    // ---- selftest ----

    /// What is wrong with the rules above, empty when nothing is.
    static func selfTest() -> [String] {
        var bad = [String]()
        func check(_ ok: Bool, _ what: String) { if !ok { bad.append(what) } }

        let febe = "0000febe-0000-1000-8000-00805f9b34fb"
        let chr = "c65b8f2f-aee2-4c89-b758-bc4892d6f2d8"

        let k = deviceKey(salt: "s", id: "aa-bb-cc-dd-ee-ff")
        check(isKey(k), "key shape")
        check(k == deviceKey(salt: "s", id: "aa-bb-cc-dd-ee-ff"), "key stable")
        check(k != deviceKey(salt: "t", id: "aa-bb-cc-dd-ee-ff"), "key follows the salt")
        check(normaliseAddress("AA:BB:CC:DD:EE:FF") == "aa-bb-cc-dd-ee-ff", "address form")

        check(isUUID128(febe), "uuid ok")
        check(!isUUID128("0000FEBE-0000-1000-8000-00805f9b34fb"), "upper case refused")
        check(!isUUID128("febe"), "short refused")
        check(uuidBytes(febe)?.count == 16, "uuid bytes")
        check(uuidString(bytes: [0xfe, 0xbe]) == febe, "16-bit expands")
        check(uuidString(bytes: [0x12, 0x34, 0x56, 0x78]) == "12345678-0000-1000-8000-00805f9b34fb", "32-bit expands")
        check(uuidString(bytes: uuidBytes("66666666-0000-1000-8000-00805f9b34fb") ?? []) == "66666666-0000-1000-8000-00805f9b34fb", "128-bit kept")
        check(uuidString(bytes: [1, 2, 3]) == nil, "odd length refused")

        check(isFirmwareService("66666666-aaaa-bbbb-cccc-000000000000"), "BES OTA refused")
        check(isFirmwareService("01000100-0000-1000-8000-00805f9b34fb"), "OTA prefix refused")
        check(!isFirmwareService(febe), "FEBE is not firmware")

        check(bareName("LE-Bose QC Ultra") == "bose qc ultra", "bare name")
        let dev = devLine(key: k, connected: true, link: "classic", classOfDevice: 0x240404, uuids: ["a", "b"], name: "My\tPhones\n")
        check(dev == "DEV\t\(k)\t1\tclassic\t240404\ta,b\tMy Phones", "dev line: \(dev)")
        check(errLine("x", "a\tb") == "ERR\tx\ta b", "err line")

        let frame = encodeFrame(0x57, Data([1, 2, 3]))
        let back = decodeFrame(frame)
        check(frame == Data([0x57, 0, 3, 1, 2, 3]), "frame bytes")
        check(back?.type == 0x57 && back?.payload == Data([1, 2, 3]) && back?.used == 6, "frame round trip")
        check(decodeFrame(frame.dropLast()) == nil, "short frame waits")
        check(decodeFrame(Data()) == nil, "empty waits")

        switch parseGatt([k, febe, chr, chr]) {
        case .success(let g): check(g.withResponse && g.notify.count == 1, "gatt args")
        case .failure: check(false, "gatt args parse")
        }
        switch parseGatt([k, febe, chr, chr, "--without-response"]) {
        case .success(let g): check(!g.withResponse, "without response")
        case .failure: check(false, "without response parse")
        }
        switch parseGatt([k, "66666666-0000-1000-8000-00805f9b34fb", chr, chr]) {
        case .success: check(false, "firmware service must fail")
        case .failure(let e): check(e.code == "firmware", "firmware service refused")
        }
        if case .success = parseGatt(["AA:BB:CC:DD:EE:FF", febe, chr, chr]) { check(false, "an address is not a key") }
        if case .success = parseGatt([k, febe]) { check(false, "too few args") }
        return bad
    }
}
