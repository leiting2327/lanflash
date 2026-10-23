import Foundation
import Security
import UIKit

struct PairInfo: Codable {
    var v: Int = 1
    var ips: [String]
    var port: UInt16
    var token: String
    var name: String

    static func decode(_ raw: String) -> PairInfo? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(PairInfo.self, from: data)
    }

    func encode() -> String {
        guard let data = try? JSONEncoder().encode(self) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
}

enum Proto {
    static let version = 1
    static let hello: UInt8 = 1
    static let manifest: UInt8 = 2
    static let data: UInt8 = 3
    static let done: UInt8 = 4
    static let complete: UInt8 = 5
    static let error: UInt8 = 6

    static let chunkSize = 4 * 1024 * 1024
    static let defaultStreams = 4
    static let maxFrame = 64 * 1024 * 1024

    static func frame(_ type: UInt8, _ payload: Data) -> Data {
        var out = Data(capacity: payload.count + 5)
        out.append(type)
        let len = UInt32(payload.count)
        out.append(UInt8((len >> 24) & 0xFF))
        out.append(UInt8((len >> 16) & 0xFF))
        out.append(UInt8((len >> 8) & 0xFF))
        out.append(UInt8(len & 0xFF))
        out.append(payload)
        return out
    }

    static func randomToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func json(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    static func formatBytes(_ v: Int64) -> String {
        if v >= 1_073_741_824 { return String(format: "%.2f GB", Double(v) / 1_073_741_824) }
        if v >= 1_048_576 { return String(format: "%.1f MB", Double(v) / 1_048_576) }
        if v >= 1024 { return String(format: "%.0f KB", Double(v) / 1024) }
        return "\(v) B"
    }

    static func formatSpeed(_ v: Int64) -> String {
        if v >= 1_073_741_824 { return String(format: "%.2f GB/s", Double(v) / 1_073_741_824) }
        if v >= 1_048_576 { return String(format: "%.1f MB/s", Double(v) / 1_048_576) }
        if v >= 1024 { return String(format: "%.0f KB/s", Double(v) / 1024) }
        return "\(v) B/s"
    }

    static func localIPv4() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return result }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let iface = p.pointee
            if let addr = iface.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: iface.ifa_name)
                if name != "lo0" {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: host)
                        if ip.hasPrefix("169.254.") == false {
                            if name.hasPrefix("en") || name.hasPrefix("wl") {
                                result.insert(ip, at: 0)
                            } else {
                                result.append(ip)
                            }
                        }
                    }
                }
            }
            ptr = iface.ifa_next
        }
        var seen = Set<String>()
        return result.filter { seen.insert($0).inserted }
    }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 {
            c = (c & 1 != 0) ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { raw in
            for byte in raw.bindMemory(to: UInt8.self) {
                crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

extension Data {
    func u32(at offset: Int) -> UInt32 {
        var v: UInt32 = 0
        for i in 0..<4 {
            v = (v << 8) | UInt32(self[offset + i])
        }
        return v
    }

    func u64(at offset: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<8 {
            v = (v << 8) | UInt64(self[offset + i])
        }
        return v
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
