import Foundation
import Security

class AuthService {
    static let shared = AuthService()
    private let keychainKey = "com.popoto.popotoDiscoverMobile.secretKey"

    // DEVELOPMENT: Hardcoded secret matching Flutter app
    private let hardcodedSecret = "5a21884927730b6388938cd1d2f2937e1e801dae2e4c4561f951fd3974be9de8"
    private var authEnabled = true

    private init() {}

    // MARK: - Keychain Operations

    func getSecret() -> String? {
        // DEVELOPMENT: Return hardcoded secret
        return hardcodedSecret
    }

    func setSecret(_ secret: String) -> Bool {
        guard isValidSecret(secret) else { return false }

        deleteSecret()

        let data = secret.data(using: .utf8)!
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keychainKey,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }

    func deleteSecret() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keychainKey
        ]
        SecItemDelete(query as CFDictionary)
    }

    func hasSecret() -> Bool {
        return true // DEVELOPMENT: Always have secret
    }

    // MARK: - Validation

    func isValidSecret(_ secret: String) -> Bool {
        PortableCoreBridge.shared.isValidSecret(secret)
    }

    func requestSecret() -> String? {
        authEnabled ? getSecret() : nil
    }
}
