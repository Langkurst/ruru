// Read-only BMAP probe for Windows.
//
// Finds a paired Bose headset, opens the Serial Port (SPP) Bluetooth service that the Bose app
// itself uses, and sends GET requests only. It never changes a setting on the headset, and it
// prints no Bluetooth address or serial number.
//
// BMAP frame: [block, function, flags, payloadLength, ...payload]; flags low nibble = operator
// (1 = GET, 3 = STATUS reply, 4 = ERROR reply). Layout from the MIT-licensed Earshot and bosectl
// projects.
using System.Diagnostics;
using System.Text;
using System.Threading.Channels;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

static string Hex(byte[] b) => b.Length == 0 ? "(empty)" : Convert.ToHexString(b).ToLowerInvariant();

static string Text(byte[] b)
{
    int end = Array.IndexOf(b, (byte)0);
    if (end < 0) end = b.Length;
    return Encoding.UTF8.GetString(b, 0, end).Trim();
}

var clock = Stopwatch.StartNew();

var paired = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
var info = paired.FirstOrDefault(d => d.Name.Contains("Bose", StringComparison.OrdinalIgnoreCase));
if (info is null)
{
    Console.WriteLine("RESULT: no paired Bose device found");
    return 2;
}

using var device = await BluetoothDevice.FromIdAsync(info.Id);
Console.WriteLine($"Device: {device.Name} (Bluetooth link: {device.ConnectionStatus})");

var lookup = await device.GetRfcommServicesAsync(BluetoothCacheMode.Uncached);
Console.WriteLine($"Service lookup: {lookup.Error}, {lookup.Services.Count} RFCOMM services [{clock.ElapsedMilliseconds} ms]");
foreach (var s in lookup.Services) Console.WriteLine($"  service {s.ServiceId.Uuid}");

var spp = lookup.Services.FirstOrDefault(s => s.ServiceId.Uuid == RfcommServiceId.SerialPort.Uuid);
if (spp is null)
{
    Console.WriteLine("RESULT: the headset did not offer the Serial Port service (is it switched on and connected to this PC?)");
    return 3;
}

using var socket = new StreamSocket();
long connectStart = clock.ElapsedMilliseconds;
try
{
    await socket.ConnectAsync(spp.ConnectionHostName, spp.ConnectionServiceName,
        SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
}
catch (Exception e)
{
    Console.WriteLine($"RESULT: could not open the control channel: {e.Message.Trim()}");
    return 4;
}
Console.WriteLine($"Control channel open in {clock.ElapsedMilliseconds - connectStart} ms");

var reader = new DataReader(socket.InputStream) { InputStreamOptions = InputStreamOptions.Partial };
var writer = new DataWriter(socket.OutputStream);
var frames = Channel.CreateUnbounded<Frame>();

// One reader for the life of the socket: bytes arrive in arbitrary chunks, so frames are cut
// out of a running buffer.
var readLoop = Task.Run(async () =>
{
    var buffer = new List<byte>();
    try
    {
        while (true)
        {
            uint n = await reader.LoadAsync(512);
            if (n == 0) break;
            var chunk = new byte[n];
            reader.ReadBytes(chunk);
            buffer.AddRange(chunk);
            while (buffer.Count >= 4 && buffer.Count >= 4 + buffer[3])
            {
                int len = buffer[3];
                frames.Writer.TryWrite(new Frame(buffer[0], buffer[1], buffer[2] & 0x0F, buffer.GetRange(4, len).ToArray()));
                buffer.RemoveRange(0, 4 + len);
            }
        }
    }
    catch
    {
        // Socket closed underneath the read: expected at the end of the probe.
    }
    frames.Writer.TryComplete();
});

async Task<Frame?> NextFrame(int timeoutMs)
{
    using var cts = new CancellationTokenSource(timeoutMs);
    try { return await frames.Reader.ReadAsync(cts.Token); }
    catch (OperationCanceledException) { return null; }
    catch (ChannelClosedException) { return null; }
}

// Anything the headset says by itself straight after the channel opens.
int greeting = 0;
while (await NextFrame(400) is not null) greeting++;
Console.WriteLine($"Greeting frames: {greeting}");

async Task<Frame?> Get(int block, int function)
{
    writer.WriteBytes(new byte[] { (byte)block, (byte)function, 0x01, 0x00 });
    await writer.StoreAsync();
    var deadline = clock.ElapsedMilliseconds + 2000;
    while (clock.ElapsedMilliseconds < deadline)
    {
        var f = await NextFrame((int)Math.Max(1, deadline - clock.ElapsedMilliseconds));
        if (f is null) break;
        if (f.Block == block && f.Function == function && (f.Operator == 3 || f.Operator == 4)) return f;
    }
    return null;
}

var requests = new (int Block, int Function, string Label, Func<byte[], string> Decode)[]
{
    (0, 1, "BMAP version", Text),
    (0, 3, "Product id", p => p.Length >= 3 ? $"0x{(p[0] << 8) | p[1]:X4} variant {p[2]}" : Hex(p)),
    (0, 5, "Firmware", Text),
    (0, 15, "Factory product name", Text),
    (0, 2, "Function blocks", Hex),
    (2, 2, "Battery", p => p.Length >= 1 && p[0] <= 100 ? $"{p[0]} %  (raw {Hex(p)})" : Hex(p)),
    (31, 3, "Current listening mode", Hex),
    (31, 2, "Mode capabilities", Hex),
    (1, 5, "Noise cancelling level", Hex),
    (1, 7, "Headset EQ", Hex),
    (1, 10, "Multipoint", Hex),
    (1, 3, "Voice prompts", Hex),
};

int answered = 0;
foreach (var (block, function, label, decode) in requests)
{
    long t0 = clock.ElapsedMilliseconds;
    var reply = await Get(block, function);
    long ms = clock.ElapsedMilliseconds - t0;
    string shown = reply switch
    {
        null => "no reply",
        { Operator: 4 } => $"error {(reply.Payload.Length > 0 ? reply.Payload[0] : -1)}",
        _ => decode(reply.Payload),
    };
    if (reply is { Operator: 3 }) answered++;
    Console.WriteLine($"[{block}.{function}] {label,-24} {shown}  ({ms} ms)");
}

socket.Dispose();
await Task.WhenAny(readLoop, Task.Delay(1000));
Console.WriteLine($"RESULT: {answered} of {requests.Length} reads answered, total {clock.ElapsedMilliseconds} ms");
return answered > 0 ? 0 : 5;

record Frame(int Block, int Function, int Operator, byte[] Payload);
