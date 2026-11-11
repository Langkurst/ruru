// BMAP over Bluetooth LE GATT (service 0xFEBE), the route an iPhone, Mac or Linux app can use.
// Sends GET requests only (operator 1), which read and never change a setting. No addresses printed.
using System.Diagnostics;
using System.Text;
using System.Threading.Channels;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Security.Cryptography;

var febe = Guid.Parse("0000febe-0000-1000-8000-00805f9b34fb");
var targets = new (string Name, Guid Uuid)[] {
    ("unsecure d417c028", Guid.Parse("d417c028-9818-4354-99d1-2ac09d074591")),
    ("secure c65b8f2f", Guid.Parse("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8")),
};

ulong found = 0;
var w = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
w.Received += (_, e) => { if (e.Advertisement.LocalName.Contains("Bose", StringComparison.OrdinalIgnoreCase)) found = e.BluetoothAddress; };
w.Start();
for (int i = 0; i < 100 && found == 0; i++) await Task.Delay(100);
w.Stop();
if (found == 0) { Console.WriteLine("RESULT: no LE broadcast seen"); return 2; }

using var dev = await BluetoothLEDevice.FromBluetoothAddressAsync(found);
var svcs = await dev.GetGattServicesForUuidAsync(febe, BluetoothCacheMode.Uncached);
var svc = svcs.Services.Single();
var chars = (await svc.GetCharacteristicsAsync(BluetoothCacheMode.Uncached)).Characteristics;
var clock = Stopwatch.StartNew();

static string Text(byte[] b) { int e = Array.IndexOf(b, (byte)0); if (e < 0) e = b.Length; return Encoding.UTF8.GetString(b, 0, e).Trim(); }

foreach (var (label, uuid) in targets)
{
    var c = chars.FirstOrDefault(x => x.Uuid == uuid);
    if (c is null) { Console.WriteLine($"{label}: missing"); continue; }
    var inbox = Channel.CreateUnbounded<byte[]>();
    c.ValueChanged += (_, a) => { CryptographicBuffer.CopyToByteArray(a.CharacteristicValue, out byte[] b); inbox.Writer.TryWrite(b ?? Array.Empty<byte>()); };
    var sub = await c.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify);
    Console.WriteLine($"== {label}: subscribe {sub.Status} {sub.ProtocolError}");
    if (sub.Status != GattCommunicationStatus.Success) continue;

    async Task<byte[]?> Get(int block, int fn)
    {
        while (inbox.Reader.TryRead(out _)) { }
        var req = CryptographicBuffer.CreateFromByteArray(new byte[] { (byte)block, (byte)fn, 0x01, 0x00 });
        var wr = await c.WriteValueWithResultAsync(req, GattWriteOption.WriteWithResponse);
        if (wr.Status != GattCommunicationStatus.Success) { Console.WriteLine($"   write {wr.Status} protoErr={wr.ProtocolError}"); return null; }
        using var cts = new CancellationTokenSource(2500);
        try
        {
            while (true)
            {
                var f = await inbox.Reader.ReadAsync(cts.Token);
                if (f.Length >= 4 && f[0] == block && f[1] == fn) return f;
                Console.WriteLine($"   (other frame {Convert.ToHexString(f)})");
            }
        }
        catch (OperationCanceledException) { return null; }
    }

    foreach (var (b, f, name, text) in new[] { (0,1,"BMAP version",true), (0,3,"Product id",false), (0,5,"Firmware",true), (2,2,"Battery",false), (31,3,"Current mode",false), (1,5,"Noise level",false) })
    {
        long t0 = clock.ElapsedMilliseconds;
        var r = await Get(b, f);
        string shown = r is null ? "no reply" : (r[2] & 0x0F) == 4 ? $"error {(r.Length > 4 ? r[4] : -1)}" :
            text ? Text(r[4..]) : Convert.ToHexString(r[4..]).ToLowerInvariant();
        Console.WriteLine($"   [{b}.{f}] {name,-14} {shown} ({clock.ElapsedMilliseconds - t0} ms)");
        if (r is null && b == 0 && f == 1) break;
    }
    await c.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(GattClientCharacteristicConfigurationDescriptorValue.None);
}
Console.WriteLine("RESULT: done");
return 0;
