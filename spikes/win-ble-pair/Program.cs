// iPhone-route test, step 2: pair the headset's Bluetooth LE side (as an iPhone would when asked),
// then retry read-only BMAP GET requests over the Bose GATT service 0xFEBE on both characteristics.
// Also tries both write types and reading back after a write. Prints no addresses.
using System.Diagnostics;
using System.Text;
using System.Threading.Channels;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Security.Cryptography;

var febe = Guid.Parse("0000febe-0000-1000-8000-00805f9b34fb");
var charIds = new (string Name, Guid Uuid)[] {
    ("unsecure d417c028", Guid.Parse("d417c028-9818-4354-99d1-2ac09d074591")),
    ("secure c65b8f2f", Guid.Parse("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8")),
};

ulong found = 0; BluetoothAddressType addrType = BluetoothAddressType.Public;
var w = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
w.Received += (_, e) => { if (e.Advertisement.LocalName.Contains("Bose", StringComparison.OrdinalIgnoreCase)) { found = e.BluetoothAddress; addrType = e.BluetoothAddressType; } };
w.Start();
for (int i = 0; i < 150 && found == 0; i++) await Task.Delay(100);
w.Stop();
if (found == 0) { Console.WriteLine("RESULT: no LE broadcast seen"); return 2; }

using var dev = await BluetoothLEDevice.FromBluetoothAddressAsync(found, addrType);
var di = dev.DeviceInformation;
Console.WriteLine($"LE device: {dev.Name}; paired={di.Pairing.IsPaired} canPair={di.Pairing.CanPair}");
if (!di.Pairing.IsPaired)
{
    var custom = di.Pairing.Custom;
    custom.PairingRequested += (_, a) => { Console.WriteLine($"  pairing kind: {a.PairingKind}"); a.Accept(); };
    var kinds = DevicePairingKinds.ConfirmOnly | DevicePairingKinds.ConfirmPinMatch | DevicePairingKinds.DisplayPin | DevicePairingKinds.ProvidePin;
    var res = await custom.PairAsync(kinds, DevicePairingProtectionLevel.Encryption);
    Console.WriteLine($"Pair result: {res.Status} (protection {res.ProtectionLevelUsed})");
    if (res.Status != DevicePairingResultStatus.Paired && res.Status != DevicePairingResultStatus.AlreadyPaired)
    {
        var res2 = await custom.PairAsync(kinds, DevicePairingProtectionLevel.None);
        Console.WriteLine($"Pair retry (no protection): {res2.Status}");
    }
}

var svc = (await dev.GetGattServicesForUuidAsync(febe, BluetoothCacheMode.Uncached)).Services.FirstOrDefault();
if (svc is null) { Console.WriteLine("RESULT: 0xFEBE not found after pairing"); return 3; }
var access = await svc.RequestAccessAsync();
Console.WriteLine($"Service access: {access}");
var chars = (await svc.GetCharacteristicsAsync(BluetoothCacheMode.Uncached)).Characteristics;
var clock = Stopwatch.StartNew();
static string Text(byte[] b) { int e = Array.IndexOf(b, (byte)0); if (e < 0) e = b.Length; return Encoding.UTF8.GetString(b, 0, e).Trim(); }
static string HexOf(Windows.Storage.Streams.IBuffer b) { CryptographicBuffer.CopyToByteArray(b, out byte[] a); return Convert.ToHexString(a ?? Array.Empty<byte>()).ToLowerInvariant(); }

int answered = 0;
foreach (var (label, uuid) in charIds)
{
    var c = chars.FirstOrDefault(x => x.Uuid == uuid);
    if (c is null) { Console.WriteLine($"{label}: missing"); continue; }
    var inbox = Channel.CreateUnbounded<byte[]>();
    c.ValueChanged += (_, a) => { CryptographicBuffer.CopyToByteArray(a.CharacteristicValue, out byte[] b); inbox.Writer.TryWrite(b ?? Array.Empty<byte>()); };
    foreach (var mode in new[] { GattClientCharacteristicConfigurationDescriptorValue.Notify, GattClientCharacteristicConfigurationDescriptorValue.Indicate })
    {
        var sub = await c.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(mode);
        Console.WriteLine($"== {label}: subscribe {mode} -> {sub.Status} {sub.ProtocolError}");
        if (sub.Status == GattCommunicationStatus.Success) break;
    }
    foreach (var opt in new[] { GattWriteOption.WriteWithResponse, GattWriteOption.WriteWithoutResponse })
    {
        while (inbox.Reader.TryRead(out _)) { }
        var req = CryptographicBuffer.CreateFromByteArray(new byte[] { 0x00, 0x01, 0x01, 0x00 }); // GET [0.1] BMAP version
        var wr = await c.WriteValueWithResultAsync(req, opt);
        Console.WriteLine($"   write {opt}: {wr.Status} {wr.ProtocolError}");
        using var cts = new CancellationTokenSource(2500);
        byte[]? got = null;
        try { got = await inbox.Reader.ReadAsync(cts.Token); } catch (OperationCanceledException) { }
        if (got != null) { answered++; Console.WriteLine($"   notification: {Convert.ToHexString(got).ToLowerInvariant()}  text='{(got.Length > 4 ? Text(got[4..]) : "")}'"); }
        else
        {
            var rd = await c.ReadValueAsync(BluetoothCacheMode.Uncached);
            Console.WriteLine($"   no notification; read-back: {rd.Status} {(rd.Status == GattCommunicationStatus.Success ? HexOf(rd.Value) : rd.ProtocolError?.ToString())}");
        }
    }
    await c.WriteClientCharacteristicConfigurationDescriptorWithResultAsync(GattClientCharacteristicConfigurationDescriptorValue.None);
}
Console.WriteLine($"RESULT: {answered} replies over Bluetooth LE ({clock.ElapsedMilliseconds} ms)");
return 0;
