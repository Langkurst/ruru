// ruru-ble-win: Ruru's Bluetooth LE bridge on Windows (C++/WinRT). The JVM cannot call the WinRT
// Bluetooth LE API, so this small program does it and talks to Ruru over stdin/stdout, as the Mac
// helper does for RFCOMM. Built by platform/windows/helper/build.ps1 (CI bundles it with the app).
//
// Line protocol (one command per line on stdin, answers on stdout; diagnostics on stderr):
//   version                 -> "ruru-ble-win <n>"
//   list                    -> "DEV\t<key>\t<1|0 connected>\t<name>" per Bluetooth LE device paired
//                              with Windows, then "END\t<count>"
//   scan <seconds> [active] -> "ADV\t<key>\t<company id hex|->\t<data hex>\t<name>" per advertiser
//                              and manufacturer section, then "END\t<count>". Passive unless
//                              "active" (active also asks advertisers for their scan response).
//   gatt <key> <service> <write> <notify> [<notify>...] [--without-response]
//                           -> "OPEN\t<max pdu>\t<1|0 with response>" and then binary frames both
//                              ways, or "ERR\t<code>\t<message>"
//   close                   -> exits (so does the end of stdin)
//   selftest                -> checks this program's own parsing, prints "selftest OK"
//
// After OPEN every message is a frame: one type byte, a two-byte big-endian length, the payload.
//   to the helper:   'W' bytes to write to the write characteristic, 'Q' close
//   from the helper: 'N' one notification, 'K' write result (0x00, or 0x01 + reason),
//                    'M' new max PDU (two bytes), 'D' the link dropped (reason), then it exits
//
// Safety (CLAUDE.md): a <key> is a salted hash of the address (salt from RURU_BLE_SALT), so no
// Bluetooth address ever crosses stdout. The helper never pairs, unpairs or bonds, opens only the
// one service it is told, refuses firmware-update services (the same list as GattLinkSpec), and
// touches nothing but GATT (no classic Bluetooth, nothing on iAP2).

#include <windows.h>
#include <bcrypt.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Devices.Radios.h>
#include <winrt/Windows.Storage.Streams.h>

#pragma comment(lib, "windowsapp")
#pragma comment(lib, "bcrypt")

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Devices::Enumeration;
using namespace Windows::Devices::Radios;
using namespace Windows::Storage::Streams;

namespace {

constexpr int kProtocolVersion = 1;
constexpr size_t kMaxFrame = 0xFFFF;
constexpr size_t kMaxAdvertData = 64;

// True while a GATT session is open; the drop watcher's thread reads it after cmdGatt may have returned.
std::atomic<bool> g_linkOpen{false};

// Notifications can come as soon as they are switched on, before the OPEN line is out; frames must
// not overtake that line, so they wait here until it is written.
std::mutex g_early;
bool g_framesOn = false;
std::vector<std::vector<uint8_t>> g_earlyNotifications;

// ---- Output ---------------------------------------------------------------------------------

std::mutex g_out;

void writeAll(HANDLE h, const void* data, size_t size) {
    auto p = static_cast<const uint8_t*>(data);
    while (size > 0) {
        DWORD n = 0;
        if (!WriteFile(h, p, static_cast<DWORD>(size), &n, nullptr) || n == 0) ExitProcess(5); // Ruru went away
        p += n;
        size -= n;
    }
}

void outLine(const std::string& line) {
    std::lock_guard<std::mutex> lock(g_out);
    std::string s = line + "\n";
    writeAll(GetStdHandle(STD_OUTPUT_HANDLE), s.data(), s.size());
}

void logLine(const std::string& line) {
    std::string s = line + "\n";
    DWORD n = 0;
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), s.data(), static_cast<DWORD>(s.size()), &n, nullptr);
}

void outFrame(char type, const uint8_t* data, size_t size) {
    if (size > kMaxFrame) size = kMaxFrame;
    uint8_t head[3] = {static_cast<uint8_t>(type), static_cast<uint8_t>(size >> 8), static_cast<uint8_t>(size & 0xFF)};
    std::lock_guard<std::mutex> lock(g_out);
    HANDLE h = GetStdHandle(STD_OUTPUT_HANDLE);
    writeAll(h, head, 3);
    if (size > 0) writeAll(h, data, size);
}

void outFrame(char type, const std::string& text) {
    outFrame(type, reinterpret_cast<const uint8_t*>(text.data()), text.size());
}

// ---- Input ----------------------------------------------------------------------------------

bool readExact(uint8_t* buf, size_t size) {
    HANDLE h = GetStdHandle(STD_INPUT_HANDLE);
    while (size > 0) {
        DWORD n = 0;
        if (!ReadFile(h, buf, static_cast<DWORD>(size), &n, nullptr) || n == 0) return false;
        buf += n;
        size -= n;
    }
    return true;
}

// One command line, read a byte at a time so nothing after it (the binary frames) is swallowed.
std::optional<std::string> readLine() {
    std::string s;
    uint8_t c = 0;
    while (true) {
        if (!readExact(&c, 1)) {
            if (s.empty()) return std::nullopt;
            return s;
        }
        if (c == '\n') break;
        if (c != '\r') s.push_back(static_cast<char>(c));
        if (s.size() > 4096) return std::nullopt;
    }
    return s;
}

std::vector<std::string> split(const std::string& line) {
    std::vector<std::string> parts;
    std::string cur;
    for (char c : line) {
        if (c == ' ' || c == '\t') {
            if (!cur.empty()) parts.push_back(cur), cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    if (!cur.empty()) parts.push_back(cur);
    return parts;
}

// ---- Text helpers ---------------------------------------------------------------------------

std::string hex(const uint8_t* data, size_t size) {
    static const char* digits = "0123456789abcdef";
    std::string s;
    s.reserve(size * 2);
    for (size_t i = 0; i < size; i++) {
        s.push_back(digits[data[i] >> 4]);
        s.push_back(digits[data[i] & 0x0F]);
    }
    return s;
}

int hexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::vector<uint8_t> unhex(const std::string& s) {
    std::vector<uint8_t> out;
    if (s.size() % 2 != 0) return out;
    for (size_t i = 0; i < s.size(); i += 2) {
        int a = hexDigit(s[i]), b = hexDigit(s[i + 1]);
        if (a < 0 || b < 0) return {};
        out.push_back(static_cast<uint8_t>(a * 16 + b));
    }
    return out;
}

// A name on one line: tabs, line breaks and other control characters become spaces.
std::string cleanName(const hstring& name) {
    std::string s = to_string(name);
    for (auto& c : s) {
        if (static_cast<unsigned char>(c) < 0x20 || c == 0x7F) c = ' ';
    }
    return s;
}

// ---- UUIDs and the firmware-service guard ---------------------------------------------------

// Lower-case 8-4-4-4-12 UUID text, as GattLinkSpec writes it.
bool isUuidText(const std::string& s) {
    if (s.size() != 36) return false;
    for (size_t i = 0; i < s.size(); i++) {
        bool dash = i == 8 || i == 13 || i == 18 || i == 23;
        if (dash ? s[i] != '-' : !((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f'))) return false;
    }
    return true;
}

std::optional<guid> parseUuid(const std::string& s) {
    if (!isUuidText(s)) return std::nullopt;
    std::string digits;
    for (char c : s) if (c != '-') digits.push_back(c);
    auto b = unhex(digits);
    if (b.size() != 16) return std::nullopt;
    guid g{};
    g.Data1 = (uint32_t(b[0]) << 24) | (uint32_t(b[1]) << 16) | (uint32_t(b[2]) << 8) | b[3];
    g.Data2 = static_cast<uint16_t>((b[4] << 8) | b[5]);
    g.Data3 = static_cast<uint16_t>((b[6] << 8) | b[7]);
    for (int i = 0; i < 8; i++) g.Data4[i] = b[8 + i];
    return g;
}

// GattLinkSpec.FIRMWARE_PREFIXES, repeated here so a wrong command cannot reach one either.
const char* kFirmwarePrefixes[] = {
    "66666666-",
    "86868686-",
    "01000100-0000-1000-8000-",
    "03000300-0000-1000-8000-",
    "05000500-0000-1000-8000-",
    "06000600-0000-1000-8000-",
    "07000700-0000-1000-8000-",
    "65786365-6c70-6f69-6e74-2e636f82",
};

bool isFirmwareService(const std::string& uuid) {
    for (auto prefix : kFirmwarePrefixes) {
        if (uuid.rfind(prefix, 0) == 0) return true;
    }
    return false;
}

// ---- Opaque keys ----------------------------------------------------------------------------

std::vector<uint8_t> g_salt;

void loadSalt() {
    char buf[256] = {};
    DWORD n = GetEnvironmentVariableA("RURU_BLE_SALT", buf, sizeof(buf));
    if (n > 0 && n < sizeof(buf)) g_salt = unhex(buf);
    if (g_salt.size() < 8) {
        // No salt from Ruru: keys are good for this run of the helper only.
        g_salt.assign(16, 0);
        BCryptGenRandom(nullptr, g_salt.data(), static_cast<ULONG>(g_salt.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    }
}

// The first 8 bytes of SHA-256(salt + the 6 address bytes), as 16 hex digits.
std::string keyFor(uint64_t address) {
    std::vector<uint8_t> input(g_salt);
    for (int i = 5; i >= 0; i--) input.push_back(static_cast<uint8_t>((address >> (i * 8)) & 0xFF));
    uint8_t digest[32] = {};
    BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, input.data(), static_cast<ULONG>(input.size()), digest, sizeof(digest));
    return hex(digest, 8);
}

bool isKeyText(const std::string& s) {
    if (s.size() != 16) return false;
    for (char c : s) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    return true;
}

// ---- Bluetooth helpers ----------------------------------------------------------------------

template <typename Op>
auto within(Op const& op, int ms, const char* what) {
    if (op.wait_for(std::chrono::milliseconds(ms)) != AsyncStatus::Completed) {
        op.Cancel();
        throw std::runtime_error(std::string(what) + ": no answer in " + std::to_string(ms) + " ms");
    }
    return op.GetResults();
}

std::vector<uint8_t> bytesOf(IBuffer const& buffer) {
    std::vector<uint8_t> v(buffer ? buffer.Length() : 0);
    if (!v.empty()) DataReader::FromBuffer(buffer).ReadBytes(v);
    return v;
}

IBuffer bufferOf(const std::vector<uint8_t>& bytes) {
    DataWriter w;
    w.WriteBytes(array_view<const uint8_t>(bytes.data(), bytes.data() + bytes.size()));
    return w.DetachBuffer();
}

// Null when this computer can use Bluetooth LE now; otherwise an error code and words.
std::optional<std::pair<std::string, std::string>> radioProblem() {
    auto adapter = within(BluetoothAdapter::GetDefaultAsync(), 5000, "adapter");
    if (!adapter) return std::make_pair(std::string("no-adapter"), std::string("this computer has no Bluetooth adapter"));
    if (!adapter.IsLowEnergySupported()) return std::make_pair(std::string("no-le"), std::string("this Bluetooth adapter has no Bluetooth LE"));
    auto radio = within(adapter.GetRadioAsync(), 5000, "radio");
    if (radio && radio.State() != RadioState::On) return std::make_pair(std::string("bluetooth-off"), std::string("Bluetooth is off"));
    return std::nullopt;
}

struct Paired {
    uint64_t address;
    std::string name;
    bool connected;
};

// Bluetooth LE devices already paired with Windows. Reading the list sends nothing.
std::vector<Paired> pairedLe() {
    std::vector<Paired> out;
    auto infos = within(DeviceInformation::FindAllAsync(BluetoothLEDevice::GetDeviceSelectorFromPairingState(true)), 10000, "device list");
    for (auto const& info : infos) {
        try {
            auto dev = within(BluetoothLEDevice::FromIdAsync(info.Id()), 5000, "device");
            if (!dev) continue;
            out.push_back({dev.BluetoothAddress(), cleanName(info.Name()), dev.ConnectionStatus() == BluetoothConnectionStatus::Connected});
            dev.Close();
        } catch (...) {
            // A device Windows lists but cannot describe right now: left out.
        }
    }
    return out;
}

struct Heard {
    std::string name;
    std::map<uint16_t, std::vector<uint8_t>> sections;
};

// Listens for [ms]; with [stopAtKey] it stops as soon as that advertiser is heard.
std::map<uint64_t, Heard> listen(int ms, bool active, const std::string& stopAtKey = "") {
    std::map<uint64_t, Heard> heard;
    std::mutex m;
    std::atomic<bool> found{false};
    BluetoothLEAdvertisementWatcher watcher;
    watcher.ScanningMode(active ? BluetoothLEScanningMode::Active : BluetoothLEScanningMode::Passive);
    watcher.Received([&](auto const&, BluetoothLEAdvertisementReceivedEventArgs const& e) {
        std::lock_guard<std::mutex> lock(m);
        auto& h = heard[e.BluetoothAddress()];
        auto name = cleanName(e.Advertisement().LocalName());
        if (!name.empty()) h.name = name;
        for (auto const& section : e.Advertisement().ManufacturerData()) {
            auto data = bytesOf(section.Data());
            if (data.size() > kMaxAdvertData) data.resize(kMaxAdvertData);
            h.sections[section.CompanyId()] = data;
        }
        if (!stopAtKey.empty() && keyFor(e.BluetoothAddress()) == stopAtKey) found = true;
    });
    watcher.Start();
    for (int waited = 0; waited < ms && !found; waited += 100) {
        Sleep(100);
        if (watcher.Status() == BluetoothLEAdvertisementWatcherStatus::Aborted) break;
    }
    watcher.Stop();
    std::lock_guard<std::mutex> lock(m);
    return heard;
}

// ---- Commands -------------------------------------------------------------------------------

int cmdList() {
    if (auto problem = radioProblem()) {
        outLine("ERR\t" + problem->first + "\t" + problem->second);
        return 0;
    }
    auto devices = pairedLe();
    for (auto const& d : devices) {
        outLine("DEV\t" + keyFor(d.address) + "\t" + (d.connected ? "1" : "0") + "\t" + d.name);
    }
    outLine("END\t" + std::to_string(devices.size()));
    return 0;
}

int cmdScan(const std::vector<std::string>& args) {
    int seconds = args.size() > 1 ? std::atoi(args[1].c_str()) : 4;
    if (seconds < 1) seconds = 1;
    if (seconds > 30) seconds = 30;
    bool active = args.size() > 2 && args[2] == "active";
    if (auto problem = radioProblem()) {
        outLine("ERR\t" + problem->first + "\t" + problem->second);
        return 0;
    }
    auto heard = listen(seconds * 1000, active);
    size_t lines = 0;
    for (auto const& [address, h] : heard) {
        auto key = keyFor(address);
        if (h.sections.empty()) {
            outLine("ADV\t" + key + "\t-\t\t" + h.name);
            lines++;
        }
        for (auto const& [company, data] : h.sections) {
            char id[8];
            snprintf(id, sizeof(id), "%04x", company);
            outLine("ADV\t" + key + "\t" + id + "\t" + hex(data.data(), data.size()) + "\t" + h.name);
            lines++;
        }
    }
    outLine("END\t" + std::to_string(lines));
    return 0;
}

bool isAuthError(IReference<uint8_t> const& protocolError) {
    if (!protocolError) return false;
    auto e = protocolError.Value();
    return e == GattProtocolError::InsufficientAuthentication() || e == GattProtocolError::InsufficientEncryption() ||
           e == GattProtocolError::InsufficientAuthorization() || e == GattProtocolError::InsufficientEncryptionKeySize();
}

std::string statusText(GattCommunicationStatus status, IReference<uint8_t> const& protocolError) {
    std::string s;
    switch (status) {
    case GattCommunicationStatus::Success: s = "success"; break;
    case GattCommunicationStatus::Unreachable: s = "unreachable"; break;
    case GattCommunicationStatus::ProtocolError: s = "protocol error"; break;
    case GattCommunicationStatus::AccessDenied: s = "access denied"; break;
    default: s = "status " + std::to_string(static_cast<int>(status)); break;
    }
    if (protocolError) s += " " + std::to_string(protocolError.Value());
    return s;
}

int fail(const std::string& code, const std::string& words) {
    outLine("ERR\t" + code + "\t" + words);
    return 0;
}

int cmdGatt(const std::vector<std::string>& args) {
    // gatt <key> <service> <write> <notify> [<notify>...] [--without-response]
    std::vector<std::string> a(args);
    bool withResponse = true;
    if (!a.empty() && a.back() == "--without-response") {
        withResponse = false;
        a.pop_back();
    }
    if (a.size() < 5) return fail("bad-command", "gatt needs a key, a service, a write and a notify characteristic");
    const std::string& key = a[1];
    if (!isKeyText(key)) return fail("bad-command", "not a device key");
    for (size_t i = 2; i < a.size(); i++) {
        if (!isUuidText(a[i])) return fail("bad-command", "not a lower-case UUID: " + a[i]);
    }
    if (isFirmwareService(a[2])) return fail("firmware-service", "refused: " + a[2] + " is a firmware-update service");
    guid serviceId = *parseUuid(a[2]);
    guid writeId = *parseUuid(a[3]);
    std::vector<guid> notifyIds;
    for (size_t i = 4; i < a.size(); i++) notifyIds.push_back(*parseUuid(a[i]));

    if (auto problem = radioProblem()) return fail(problem->first, problem->second);

    // The key's device: paired ones first (headphones linked over LE stop advertising), then a
    // short passive listen for an advertiser with this key.
    std::optional<uint64_t> address;
    for (auto const& d : pairedLe()) {
        if (keyFor(d.address) == key) address = d.address;
    }
    if (!address) {
        for (auto const& [addr, h] : listen(6000, false, key)) {
            if (keyFor(addr) == key) address = addr;
        }
    }
    if (!address) return fail("no-device", "the headphones are not paired over Bluetooth LE and were not heard");

    auto dev = within(BluetoothLEDevice::FromBluetoothAddressAsync(*address), 10000, "device");
    if (!dev) return fail("no-device", "Windows could not open the headphones over Bluetooth LE");
    auto session = within(GattSession::FromDeviceIdAsync(dev.BluetoothDeviceId()), 10000, "session");
    session.MaintainConnection(true);

    auto services = within(dev.GetGattServicesForUuidAsync(serviceId, BluetoothCacheMode::Uncached), 15000, "services");
    if (services.Status() != GattCommunicationStatus::Success) {
        return fail("unreachable", "could not reach the service (" + statusText(services.Status(), services.ProtocolError()) + ")");
    }
    if (services.Services().Size() == 0) return fail("no-service", "the headphones do not offer service " + a[2]);
    auto service = services.Services().GetAt(0);
    auto chars = within(service.GetCharacteristicsAsync(BluetoothCacheMode::Uncached), 10000, "characteristics");
    if (chars.Status() != GattCommunicationStatus::Success) {
        return fail("unreachable", "could not read the characteristics (" + statusText(chars.Status(), chars.ProtocolError()) + ")");
    }
    auto find = [&](guid const& id) -> GattCharacteristic {
        for (auto const& c : chars.Characteristics()) if (c.Uuid() == id) return c;
        return nullptr;
    };

    GattCharacteristic writeChar = find(writeId);
    if (!writeChar) return fail("no-characteristic", "the write characteristic " + a[3] + " is missing");
    auto props = static_cast<uint32_t>(writeChar.CharacteristicProperties());
    bool canWith = props & static_cast<uint32_t>(GattCharacteristicProperties::Write);
    bool canWithout = props & static_cast<uint32_t>(GattCharacteristicProperties::WriteWithoutResponse);
    if (withResponse && !canWith && canWithout) withResponse = false;
    else if (!withResponse && !canWithout && canWith) withResponse = true;
    else if (!canWith && !canWithout) return fail("no-characteristic", "the characteristic " + a[3] + " cannot be written");
    auto writeOption = withResponse ? GattWriteOption::WriteWithResponse : GattWriteOption::WriteWithoutResponse;

    std::vector<GattCharacteristic> notifying;
    for (size_t i = 0; i < notifyIds.size(); i++) {
        GattCharacteristic c = find(notifyIds[i]);
        if (!c) return fail("no-characteristic", "the notify characteristic " + a[4 + i] + " is missing");
        auto p = static_cast<uint32_t>(c.CharacteristicProperties());
        GattClientCharacteristicConfigurationDescriptorValue mode;
        if (p & static_cast<uint32_t>(GattCharacteristicProperties::Notify)) mode = GattClientCharacteristicConfigurationDescriptorValue::Notify;
        else if (p & static_cast<uint32_t>(GattCharacteristicProperties::Indicate)) mode = GattClientCharacteristicConfigurationDescriptorValue::Indicate;
        else return fail("no-characteristic", a[4 + i] + " neither notifies nor indicates");
        c.ValueChanged([](GattCharacteristic const&, GattValueChangedEventArgs const& e) {
            auto v = bytesOf(e.CharacteristicValue());
            if (v.empty()) return;
            std::lock_guard<std::mutex> lock(g_early);
            if (g_framesOn) outFrame('N', v.data(), v.size());
            else g_earlyNotifications.push_back(std::move(v));
        });
        auto r = within(c.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(mode), 10000, "subscribe");
        if (r.Status() != GattCommunicationStatus::Success) {
            if (isAuthError(r.ProtocolError())) {
                return fail("not-paired", "the headphones want a paired, encrypted link (" + statusText(r.Status(), r.ProtocolError()) + ")");
            }
            return fail("subscribe-failed", "could not switch on notifications (" + statusText(r.Status(), r.ProtocolError()) + ")");
        }
        notifying.push_back(c);
    }

    g_linkOpen = true;
    session.MaxPduSizeChanged([&](GattSession const& s, auto&&) {
        std::lock_guard<std::mutex> lock(g_early);
        if (!g_linkOpen || !g_framesOn) return; // before OPEN, the OPEN line carries the size
        uint16_t pdu = s.MaxPduSize();
        uint8_t b[2] = {static_cast<uint8_t>(pdu >> 8), static_cast<uint8_t>(pdu & 0xFF)};
        outFrame('M', b, 2);
    });
    // A drop that lasts: Windows may reconnect by itself (MaintainConnection), so wait a moment first.
    dev.ConnectionStatusChanged([&](BluetoothLEDevice const& d, auto&&) {
        if (d.ConnectionStatus() != BluetoothConnectionStatus::Disconnected) return;
        std::thread([d] {
            Sleep(3000);
            if (g_linkOpen && d.ConnectionStatus() == BluetoothConnectionStatus::Disconnected) {
                g_linkOpen = false;
                outFrame('D', std::string("the headphones disconnected"));
                logLine("link dropped");
                ExitProcess(4);
            }
        }).detach();
    });

    {
        std::lock_guard<std::mutex> lock(g_early);
        outLine("OPEN\t" + std::to_string(session.MaxPduSize()) + "\t" + (withResponse ? "1" : "0"));
        g_framesOn = true;
        for (auto const& v : g_earlyNotifications) outFrame('N', v.data(), v.size());
        g_earlyNotifications.clear();
    }
    logLine("open: " + std::to_string(notifying.size()) + " notifying, write " + (withResponse ? "with" : "without") + " response");

    while (g_linkOpen) {
        uint8_t head[3];
        if (!readExact(head, 3)) break;
        size_t size = (size_t(head[1]) << 8) | head[2];
        std::vector<uint8_t> payload(size);
        if (size > 0 && !readExact(payload.data(), size)) break;
        if (head[0] == 'Q') break;
        if (head[0] != 'W') {
            outFrame('K', std::string("\x01") + "unknown frame type");
            continue;
        }
        try {
            auto r = within(writeChar.WriteValueWithResultAsync(bufferOf(payload), writeOption), 10000, "write");
            if (r.Status() == GattCommunicationStatus::Success) {
                uint8_t ok = 0;
                outFrame('K', &ok, 1);
            } else {
                outFrame('K', std::string("\x01") + "write refused (" + statusText(r.Status(), r.ProtocolError()) + ")");
            }
        } catch (std::exception const& e) {
            outFrame('K', std::string("\x01") + e.what());
        } catch (hresult_error const& e) {
            outFrame('K', std::string("\x01") + to_string(e.message()));
        }
    }
    g_linkOpen = false;
    // Notifications off again, as a polite client does; a link already gone just times out.
    for (auto& c : notifying) {
        try {
            within(c.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(GattClientCharacteristicConfigurationDescriptorValue::None), 1500, "unsubscribe");
        } catch (...) {
        }
    }
    session.Close();
    dev.Close();
    logLine("closed");
    return 0;
}

int selftest() {
    int failures = 0;
    auto expect = [&](bool ok, const char* what) {
        if (!ok) {
            logLine(std::string("selftest FAIL: ") + what);
            failures++;
        }
    };
    expect(isUuidText("0000febe-0000-1000-8000-00805f9b34fb"), "FEBE uuid text");
    expect(!isUuidText("0000FEBE-0000-1000-8000-00805F9B34FB"), "upper case refused");
    expect(!isUuidText("0000febe00001000800000805f9b34fb"), "no dashes refused");
    auto g = parseUuid("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8");
    expect(g && g->Data1 == 0xc65b8f2f && g->Data2 == 0xaee2 && g->Data3 == 0x4c89 && g->Data4[0] == 0xb7 && g->Data4[7] == 0xd8, "uuid parse");
    expect(isFirmwareService("66666666-0000-1000-8000-00805f9b34fb"), "BES OTA refused");
    expect(isFirmwareService("65786365-6c70-6f69-6e74-2e636f820000"), "JBL OTA refused");
    expect(!isFirmwareService("0000febe-0000-1000-8000-00805f9b34fb"), "FEBE allowed");
    g_salt = unhex("00112233445566778899aabbccddeeff");
    auto k1 = keyFor(0x112233445566ULL), k2 = keyFor(0x112233445566ULL), k3 = keyFor(0x112233445567ULL);
    expect(k1 == k2 && k1 != k3 && isKeyText(k1), "keys stable and distinct");
    expect(k1.find("112233") == std::string::npos, "key is not the address");
    g_salt = unhex("ffeeddccbbaa99887766554433221100");
    expect(keyFor(0x112233445566ULL) != k1, "key depends on the salt");
    expect(split("gatt  a\tb ").size() == 3, "split");
    expect(!isKeyText("0123456789ABCDEF") && isKeyText("0123456789abcdef"), "key text");
    if (failures == 0) outLine("selftest OK");
    return failures == 0 ? 0 : 1;
}

int run(const std::vector<std::string>& args) {
    if (args.empty()) return 0;
    const std::string& cmd = args[0];
    if (cmd == "version") {
        outLine("ruru-ble-win " + std::to_string(kProtocolVersion));
        return 0;
    }
    if (cmd == "selftest") return selftest();
    if (cmd == "list") return cmdList();
    if (cmd == "scan") return cmdScan(args);
    if (cmd == "gatt") return cmdGatt(args);
    outLine("ERR\tbad-command\tunknown command");
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    init_apartment(apartment_type::multi_threaded);
    loadSalt();
    // A command on the command line runs once; otherwise commands come one per line on stdin.
    std::vector<std::string> fromArgs;
    for (int i = 1; i < argc; i++) fromArgs.push_back(argv[i]);
    try {
        if (!fromArgs.empty()) return run(fromArgs);
        while (auto line = readLine()) {
            auto args = split(*line);
            if (args.empty()) continue;
            if (args[0] == "close") break;
            int code = run(args);
            if (args[0] == "gatt") return code; // a GATT session is the last thing a helper does
        }
    } catch (hresult_error const& e) {
        outLine("ERR\twindows\t" + to_string(e.message()));
        return 3;
    } catch (std::exception const& e) {
        outLine(std::string("ERR\tfailed\t") + e.what());
        return 3;
    }
    return 0;
}
