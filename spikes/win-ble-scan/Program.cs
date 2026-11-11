// Listens to Bluetooth LE broadcasts for 15 s and reports any that look like Bose: Bose's
// company id (0x009E) in manufacturer data, or the Bose service 0xFEBE. Then tries a GATT
// connection to each and lists the services. Read-only. Prints no addresses.
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Security.Cryptography;

var febe = Guid.Parse("0000febe-0000-1000-8000-00805f9b34fb");
var hits = new Dictionary<ulong, string>();
var w = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
w.Received += (_, e) =>
{
    var a = e.Advertisement;
    bool bose = a.ManufacturerData.Any(m => m.CompanyId == 0x009E) || a.ServiceUuids.Contains(febe)
        || a.LocalName.Contains("Bose", StringComparison.OrdinalIgnoreCase);
    if (!bose) return;
    var md = string.Join(",", a.ManufacturerData.Select(m => $"0x{m.CompanyId:X4}:{m.Data.Length}B"));
    var svc = string.Join(",", a.ServiceUuids.Select(u => u.ToString()[..8]));
    var line = $"name='{a.LocalName}' type={e.AdvertisementType} rssi={e.RawSignalStrengthInDBm} addrType={e.BluetoothAddressType} mfr=[{md}] svc=[{svc}]";
    lock (hits) { if (!hits.ContainsKey(e.BluetoothAddress) || hits[e.BluetoothAddress].Length < line.Length) hits[e.BluetoothAddress] = line; }
};
w.Start();
await Task.Delay(15000);
w.Stop();
Console.WriteLine($"Bose-looking LE broadcasters: {hits.Count}");
int i = 0;
foreach (var (addr, line) in hits)
{
    i++;
    Console.WriteLine($"#{i} {line}");
    using var dev = await BluetoothLEDevice.FromBluetoothAddressAsync(addr);
    if (dev is null) { Console.WriteLine("   could not open"); continue; }
    var r = await dev.GetGattServicesAsync(BluetoothCacheMode.Uncached);
    Console.WriteLine($"   GATT: {r.Status}, {r.Services.Count} services");
    foreach (var s in r.Services)
    {
        Console.WriteLine($"   service {s.Uuid}{(s.Uuid == febe ? "  <-- Bose control" : "")}");
        if (s.Uuid != febe) continue;
        var cs = await s.GetCharacteristicsAsync(BluetoothCacheMode.Uncached);
        foreach (var c in cs.Characteristics)
        {
            string val = "";
            if (c.CharacteristicProperties.HasFlag(GattCharacteristicProperties.Read))
            {
                var rv = await c.ReadValueAsync(BluetoothCacheMode.Uncached);
                if (rv.Status == GattCommunicationStatus.Success) { CryptographicBuffer.CopyToByteArray(rv.Value, out byte[] b); val = "read=" + Convert.ToHexString(b ?? Array.Empty<byte>()); }
                else val = $"read failed: {rv.Status}";
            }
            Console.WriteLine($"     char {c.Uuid} {c.CharacteristicProperties} {val}");
        }
    }
}
Console.WriteLine("RESULT: done");
