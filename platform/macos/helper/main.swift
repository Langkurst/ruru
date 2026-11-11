// ruru-bt-mac: Ruru's Bluetooth helper for macOS.
//
//   ruru-bt-mac list                       paired devices: "<address>\t<1|0 connected>\t<name>"
//   ruru-bt-mac connect <address> [channel] bridges the Serial Port (RFCOMM) channel:
//                                           headset bytes -> stdout, stdin -> headset,
//                                           status lines on stderr ("OPEN <channel>", "ERROR ...")
//
// The channel comes from the device's SDP record for the Serial Port service when it has one,
// else from the argument (Ruru passes the model's known channel, usually 2).
import Foundation
import IOBluetooth

setvbuf(stdout, nil, _IONBF, 0)

func status(_ line: String) {
    FileHandle.standardError.write((line + "\n").data(using: .utf8)!)
}

let args = CommandLine.arguments

if args.count >= 2 && args[1] == "list" {
    let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
    for d in devices {
        let name = (d.name ?? "").replacingOccurrences(of: "\t", with: " ")
        print("\(d.addressString ?? "")\t\(d.isConnected() ? 1 : 0)\t\(name)")
    }
    exit(0)
}

guard args.count >= 3, args[1] == "connect", let device = IOBluetoothDevice(addressString: args[2]) else {
    status("ERROR usage: ruru-bt-mac list | connect <address> [channel]")
    exit(2)
}

final class Bridge: NSObject, IOBluetoothRFCOMMChannelDelegate {
    func rfcommChannelData(_ channel: IOBluetoothRFCOMMChannel!, data: UnsafeMutableRawPointer!, length: Int) {
        FileHandle.standardOutput.write(Data(bytes: data, count: length))
    }

    func rfcommChannelClosed(_ channel: IOBluetoothRFCOMMChannel!) {
        status("CLOSED")
        exit(0)
    }
}

var channelID = BluetoothRFCOMMChannelID(args.count >= 4 ? (UInt8(args[3]) ?? 2) : 2)
if let record = device.getServiceRecord(for: IOBluetoothSDPUUID(uuid16: 0x1101)) {
    var found: BluetoothRFCOMMChannelID = 0
    if record.getRFCOMMChannelID(&found) == kIOReturnSuccess && found != 0 {
        channelID = found
    }
}

let bridge = Bridge()
var channel: IOBluetoothRFCOMMChannel?
let result = device.openRFCOMMChannelSync(&channel, withChannelID: channelID, delegate: bridge)
guard result == kIOReturnSuccess, let open = channel else {
    status("ERROR could not open channel \(channelID) (\(result))")
    exit(3)
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
