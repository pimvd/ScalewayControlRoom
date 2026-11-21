import ControlRoomKit
import Foundation
import Security

/// Keeps the API keys in the keychain: the list of keys without their secrets
/// under one item, and each secret under its own.
struct KeyStore {
    private let service = "com.pvd.scwcontrolroom"

    private struct Stored: Codable {
        var id: String
        var label: String
        var organizationID: String
        var isDemo: Bool
    }

    struct KeychainError: Error {
        var status: OSStatus
    }

    func load() throws -> [APIKey] {
        guard let data = read("keys") else { return [] }
        return try JSONDecoder().decode([Stored].self, from: data).map { stored in
            if stored.isDemo { return .demo }
            let secret = read("secret.\(stored.id)").flatMap { String(data: $0, encoding: .utf8) } ?? ""
            return APIKey(id: stored.id, label: stored.label, secretKey: secret, organizationID: stored.organizationID)
        }
    }

    func save(_ keys: [APIKey], removed: [APIKey] = []) throws {
        for key in keys where !key.isDemo {
            try write("secret.\(key.id)", Data(key.secretKey.utf8))
        }
        for key in removed { delete("secret.\(key.id)") }
        let stored = keys.map { Stored(id: $0.id, label: $0.label, organizationID: $0.organizationID, isDemo: $0.isDemo) }
        try write("keys", try JSONEncoder().encode(stored))
    }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private func read(_ account: String) -> Data? {
        var query = query(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private func write(_ account: String, _ data: Data) throws {
        delete(account)
        var item = query(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    private func delete(_ account: String) {
        _ = SecItemDelete(query(account) as CFDictionary)
    }
}
