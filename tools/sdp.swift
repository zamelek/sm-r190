import Foundation
import IOBluetooth

func uuidStr(_ u: IOBluetoothSDPUUID) -> String {
    let full = u.getWithLength(16) ?? u
    let d = Data(bytes: full.bytes, count: full.length)
    return d.map { String(format: "%02x", $0) }.joined()
}
let addr = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "60-3A-AF-FE-40-E4"
guard let dev = IOBluetoothDevice(addressString: addr) else { print("no device"); exit(1) }
for case let rec as IOBluetoothSDPServiceRecord in dev.services ?? [] {
    var ch: BluetoothRFCOMMChannelID = 0
    let hasRf = rec.getRFCOMMChannelID(&ch) == kIOReturnSuccess
    var uuids: [String] = []
    if let attr = rec.getAttributeDataElement(1), let arr = attr.getArrayValue() {
        for case let e as IOBluetoothSDPDataElement in arr { if let u = e.getUUIDValue() { uuids.append(uuidStr(u)) } }
    }
    print("service:", rec.getServiceName() ?? "-", "rfcomm:", hasRf ? String(ch) : "-", "uuids:", uuids)
}
