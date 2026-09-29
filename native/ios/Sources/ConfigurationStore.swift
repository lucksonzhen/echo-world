import Foundation
import Security

struct ConnectionConfiguration: Codable, Sendable {
    var serviceURL = ""
    var accessToken = ""
    var allowsScreenshotUpload = false

    func validatedURL() throws -> URL {
        let input = serviceURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: input),
              let url = components.url,
              let scheme = components.scheme?.lowercased(),
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil else {
            throw ScreenDescriptionError.message("请输入完整的服务地址，不要包含账号、查询参数或井号。")
        }
        if scheme == "https" { return url }
        #if DEBUG
        if scheme == "http", Self.isPrivateHost(host) { return url }
        throw ScreenDescriptionError.message("请使用 HTTPS 地址。开发版本也支持同一局域网内的 HTTP 地址。")
        #else
        throw ScreenDescriptionError.message("请使用 HTTPS 服务地址，以保护截图和连接口令。")
        #endif
    }

    static func isPrivateHost(_ rawHost: String) -> Bool {
        let host = rawHost.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if host == "localhost" || host == "::1" || host.hasSuffix(".local") { return true }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count == 4, labels.allSatisfy({
            !$0.isEmpty && $0.count <= 3 && $0.allSatisfy { $0 >= "0" && $0 <= "9" }
        }) else { return false }
        let parts = labels.compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && (16...31).contains(parts[1]))
    }
}

enum ScreenDescriptionError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

/// The AppIntent is compiled into the same application target. It therefore uses
/// the same default Keychain access group; an App Group or extension is unnecessary.
enum ConfigurationStore {
    private static let service = "com.tingjian.screen.connection.v1"
    private static let account = "configuration"

    static func read() throws -> ConnectionConfiguration {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return ConnectionConfiguration() }
        guard status == errSecSuccess, let data = item as? Data else {
            throw ScreenDescriptionError.message("无法读取连接设置，请先解锁 iPhone 并打开听见世界。")
        }
        guard let configuration = try? JSONDecoder().decode(ConnectionConfiguration.self, from: data) else {
            throw ScreenDescriptionError.message("连接设置无法读取，请在听见世界中重新保存设置。")
        }
        return configuration
    }

    static func save(_ configuration: ConnectionConfiguration) throws {
        let data = try JSONEncoder().encode(configuration)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var record = baseQuery
            attributes.forEach { record[$0.key] = $0.value }
            guard SecItemAdd(record as CFDictionary, nil) == errSecSuccess else { throw saveError }
        } else if status != errSecSuccess {
            throw saveError
        }
    }

    static func clear() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw saveError }
    }

    private static var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }
    private static var saveError: ScreenDescriptionError {
        .message("无法保存连接设置，请解锁 iPhone 后重试。")
    }
}
