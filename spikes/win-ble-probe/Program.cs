// Read-only Bluetooth LE probe: does the headset offer the Bose control service (0xFEBE) over
// Bluetooth LE, the route an iPhone app could use? Lists services and characteristics and reads
// only what is marked readable. Writes nothing to the headset; prints no addresses.
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Security.Cryptography;

var febe = Guid.Parse("0000febe-0000-1000-8000-00805f9b34fb");
var names = new Dictionary<Guid, string>
{
    [Guid.Parse("089ca084-6721-4419-9003-5e14d5ab587d")] = "PSM for BMAP over LE channel",
    [Guid.Parse("d417c028-0000-0000-0000-000000000000")] = "",
};

var paired = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
var info = paired.FirstOrDefault(d => d.Name.Contains("Bose", StringComparison.OrdinalIgnoreCase));
if (info is null) { Console.WriteLine("RESULT: no paired Bose device"); return 2; }
using var classic = await BluetoothDevice.FromIdAsync(info.Id);

BluetoothLEDevice? le = await BluetoothLEDevice.FromBluetoothAddressAsync(classic.BluetoothAddress);
if (le is null)
{
    var lePaired = await DeviceInformation.FindAllAsync(BluetoothLEDevice.GetDeviceSelectorFromPairingState(true));
    var leInfo = lePaired.FirstOrDefault(d => d.Name.Contains("Bose", StringComparison.OrdinalIgnoreCase));
    if (leInfo != null) le = await BluetoothLEDevice.FromIdAsync(leInfo.Id);
}
if (le is null) { Console.WriteLine("RESULT: Windows could not open a Bluetooth LE link to the headset"); return 3; }
Console.WriteLine($"LE device: {le.Name}");

var all = await le.GetGattServicesAsync(BluetoothCacheMode.Uncached);
Console.WriteLine($"GATT lookup: {all.Status}, {all.Services.Count} services");
foreach (var s in all.Services)
{
    var mark = s.Uuid == febe ? "   <-- Bose control service" : "";
    Console.WriteLine($"service {s.Uuid}{mark}");
    if (s.Uuid != febe) continue;
    var chars = await s.GetCharacteristicsAsync(BluetoothCacheMode.Uncached);
    Console.WriteLine($"  characteristics: {chars.Status}, {chars.Characteristics.Count}");
    foreach (var c in chars.Characteristics)
    {
        string label = names.TryGetValue(c.Uuid, out var n) ? n : "";
        Console.WriteLine($"  char {c.Uuid} props={c.CharacteristicProperties} prot={c.ProtectionLevel} {label}");
        if (c.CharacteristicProperties.HasFlag(GattCharacteristicProperties.Read))
        {
            var r = await c.ReadValueAsync(BluetoothCacheMode.Uncached);
            string val = r.Status == GattCommunicationStatus.Success && r.Value != null
                ? Hex(r.Value)
                : $"{r.Status} {r.ProtocolError}";
            Console.WriteLine($"     read: {val}");
        }
    }
}
Console.WriteLine("RESULT: done");
return 0;

static string Hex(Windows.Storage.Streams.IBuffer b) { CryptographicBuffer.CopyToByteArray(b, out byte[] a); return Convert.ToHexString(a ?? Array.Empty<byte>()); }
