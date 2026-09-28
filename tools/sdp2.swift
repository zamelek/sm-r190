import Foundation
import IOBluetooth
let dev = IOBluetoothDevice(addressString: "60-3A-AF-FE-40-E4")!
for case let rec as IOBluetoothSDPServiceRecord in dev.services ?? [] {
    var ch: BluetoothRFCOMMChannelID = 0
    guard rec.getRFCOMMChannelID(&ch) == kIOReturnSuccess else { continue }
    let u = rec.getAttributeDataElement(1)?.getUUIDValue()
    let s = u.map { Data(bytes: $0.bytes, count: $0.length).map{String(format:"%02x",$0)}.joined() } ?? "nil"
    print(rec.getServiceName() ?? "-", "ch", ch, "uuid", s)
}
