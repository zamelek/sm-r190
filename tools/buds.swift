import Foundation
import IOBluetooth

// usage: buds <channel> <listenSeconds> [hexmsg-id:hexpayload ...]
func crc16(_ data: [UInt8]) -> UInt16 {
    var crc: UInt16 = 0
    for b in data {
        crc ^= UInt16(b) << 8
        for _ in 0..<8 { crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
    }
    return crc
}
func hex(_ a: [UInt8]) -> String { a.map { String(format: "%02x", $0) }.joined(separator: " ") }
func encode(id: UInt8, payload: [UInt8]) -> [UInt8] {
    let size = payload.count + 3
    var m: [UInt8] = [0xFD, UInt8(size & 0xFF), UInt8((size >> 8) & 0x03), id] + payload
    let c = crc16([id] + payload)
    m += [UInt8(c & 0xFF), UInt8(c >> 8), 0xDD]   // LE, verified against received packets
    return m
}

class D: NSObject, IOBluetoothRFCOMMChannelDelegate {
    var buf: [UInt8] = []
    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!, data p: UnsafeMutableRawPointer!, length n: Int) {
        buf += Array(UnsafeBufferPointer(start: p.assumingMemoryBound(to: UInt8.self), count: n))
        parse()
    }
    func rfcommChannelOpenComplete(_ ch: IOBluetoothRFCOMMChannel!, status: IOReturn) { print("open complete", status) }
    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) { print("closed") }
    func parse() {
        while let s = buf.firstIndex(of: 0xFD) {
            if s > 0 { print("junk:", hex(Array(buf[0..<s]))); buf.removeFirst(s) }
            guard buf.count >= 4 else { return }
            let hdr = Int(buf[1]) | (Int(buf[2]) << 8)
            let size = hdr & 0x3FF
            let total = size + 4
            guard buf.count >= total else { return }
            let m = Array(buf[0..<total]); buf.removeFirst(total)
            let id = m[3]; let payload = Array(m[4..<(4 + size - 3)])
            let c = crc16([id] + payload)
            let le = UInt16(m[total - 3]) | (UInt16(m[total - 2]) << 8)
            let be = (UInt16(m[total - 3]) << 8) | UInt16(m[total - 2])
            print(String(format: "%6.1fs ", Date().timeIntervalSince(t0)) + String(format: "RX id=0x%02x hdr=0x%04x len=%d crcLE=%@ crcBE=%@ eom=%02x", id, hdr, payload.count,
                         c == le ? "ok" : "no", c == be ? "ok" : "no", m[total - 1]))
            print("   payload:", hex(payload))
        }
    }
}

let t0 = Date()
let args = CommandLine.arguments
let chId = BluetoothRFCOMMChannelID(args.count > 1 ? UInt8(args[1])! : 1)
let secs = args.count > 2 ? Double(args[2])! : 8
let dev = IOBluetoothDevice(addressString: "60-3A-AF-FE-40-E4")!
let d = D()
var ch: IOBluetoothRFCOMMChannel?
let r = dev.openRFCOMMChannelSync(&ch, withChannelID: chId, delegate: d)
print("open result:", r)
guard r == kIOReturnSuccess, let chan = ch else { exit(2) }
RunLoop.current.run(until: Date().addingTimeInterval(2))
for spec in args.dropFirst(3) {
    let parts = spec.split(separator: ":", omittingEmptySubsequences: false)
    let id = UInt8(parts[0], radix: 16)!
    var payload: [UInt8] = []
    if parts.count > 1 { var s = Substring(parts[1]); while s.count >= 2 { payload.append(UInt8(s.prefix(2), radix: 16)!); s = s.dropFirst(2) } }
    var msg = encode(id: id, payload: payload)
    print("TX:", hex(msg))
    let wr = chan.writeSync(&msg, length: UInt16(msg.count))
    print("write:", wr)
    RunLoop.current.run(until: Date().addingTimeInterval(1.5))
}
RunLoop.current.run(until: Date().addingTimeInterval(secs))
chan.close()
RunLoop.current.run(until: Date().addingTimeInterval(0.5))
