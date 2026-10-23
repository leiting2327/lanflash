import CryptoKit
import Foundation
import UIKit

struct LicenseInfo {
    let plan: String
    let expiresAt: Date
}

struct LicenseError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Online activation plus local signature verification.
///
/// Token: LF1.<base64url(payload)>.<base64url(hmac-sha256)>
final class LicenseStore {
    private let tokenKey = "lanflash.license.token"
    private let defaults = UserDefaults.standard

    var secret: String {
        (Bundle.main.object(forInfoDictionaryKey: "LICENSE_SECRET") as? String) ?? ""
    }

    var apiBase: String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String) ?? ""
        return raw.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    var configured: Bool { !secret.isEmpty }

    var deviceId: String {
        let vendor = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        let bundle = Bundle.main.bundleIdentifier ?? "com.lanflash.app"
        let digest = SHA256.hash(data: Data("\(vendor)|\(bundle)".utf8))
        return String(digest.map { String(format: "%02x", $0) }.joined().prefix(32))
    }

    func current() -> LicenseInfo? {
        guard let token = defaults.string(forKey: tokenKey) else { return nil }
        return verify(token)
    }

    func verify(_ token: String) -> LicenseInfo? {
        guard configured else { return nil }
        let parts = token.split(separator: ".")
        guard parts.count == 3, parts[0] == "LF1" else { return nil }
        guard let payloadData = Self.base64urlDecode(String(parts[1])),
              let signature = Self.base64urlDecode(String(parts[2])) else { return nil }

        let key = SymmetricKey(data: Data(secret.utf8))
        let mac = Data(HMAC<SHA256>.authenticationCode(for: Data("LF1.\(parts[1])".utf8), using: key))
        guard mac == signature else { return nil }
        guard let obj = (try? JSONSerialization.jsonObject(with: payloadData)) as? [String: Any] else { return nil }
        guard (obj["did"] as? String) == deviceId else { return nil }
        guard let exp = (obj["exp"] as? NSNumber)?.doubleValue else { return nil }
        guard exp * 1000 > Date().timeIntervalSince1970 * 1000 else { return nil }

        let plan = (obj["plan"] as? String) ?? "pro"
        return LicenseInfo(plan: plan, expiresAt: Date(timeIntervalSince1970: exp))
    }

    func activate(code: String) async throws -> LicenseInfo {
        guard configured else { throw LicenseError(message: "当前版本未配置授权服务") }
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !trimmed.isEmpty else { throw LicenseError(message: "请输入激活码") }
        guard let url = URL(string: "\(apiBase)/api/v1/activate") else {
            throw LicenseError(message: "授权服务地址无效")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("LanFlash-iOS/\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")", forHTTPHeaderField: "User-Agent")
        let body: [String: Any] = [
            "code": trimmed,
            "deviceId": deviceId,
            "platform": "ios",
            "version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0",
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]) ?? [:]

        guard (200..<300).contains(status) else {
            throw LicenseError(message: (json["error"] as? String) ?? "激活失败（HTTP \(status)）")
        }
        guard let token = json["token"] as? String else {
            throw LicenseError(message: "服务端未返回授权令牌")
        }
        guard let info = verify(token) else {
            throw LicenseError(message: "授权令牌校验失败，请联系客服")
        }
        defaults.set(token, forKey: tokenKey)
        return info
    }

    func clear() {
        defaults.removeObject(forKey: tokenKey)
    }

    private static func base64urlDecode(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}
