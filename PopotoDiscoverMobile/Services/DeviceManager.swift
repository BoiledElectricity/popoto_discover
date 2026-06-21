import Foundation
import Combine

enum ConnectionStatus: String {
    case disconnected
    case connecting
    case connected
}

@MainActor
class DeviceManager: ObservableObject {
    static let shared = DeviceManager()

    @Published var devices: [String: HydrophoneDevice] = [:]
    @Published var selectedDevice: HydrophoneDevice?
    @Published var logs: [LogEntry] = []
    @Published var status: ConnectionStatus = .disconnected
    @Published var isDiscovering: Bool = false

    private let maxLogEntries = 100
    private var hasInitialized = false
    private var sortedDeviceKeys: [String] = []

    private init() {
        UDPService.shared.onSnapshotChanged = { [weak self] snapshot in
            Task { @MainActor in
                self?.applySnapshot(snapshot)
            }
        }

        if let snapshot = PortableCoreBridge.shared.currentSessionSnapshot() {
            applySnapshot(snapshot)
        }
    }

    var sortedDevices: [HydrophoneDevice] {
        let orderedDevices = sortedDeviceKeys.compactMap { key in
            devices[key]
        }

        if !orderedDevices.isEmpty {
            return orderedDevices
        }

        return devices.values.sorted { lhs, rhs in
            lhs.uniqueKey < rhs.uniqueKey
        }
    }

    func initialize() {
        guard !hasInitialized else {
            if let snapshot = PortableCoreBridge.shared.currentSessionSnapshot() {
                applySnapshot(snapshot)
            }
            return
        }

        do {
            try UDPService.shared.initialize()
            hasInitialized = true
        } catch {
            status = .disconnected
            appendLocalLog("Failed to initialize: \(error.localizedDescription)", level: .error)
        }
    }

    func shutdown() {
        guard hasInitialized || status != .disconnected else { return }
        hasInitialized = false
        UDPService.shared.shutdown()
    }

    func selectDevice(_ device: HydrophoneDevice?) {
        UDPService.shared.selectDevice(uniqueKey: device?.uniqueKey)
    }

    func discoverDevices(timeout: TimeInterval = 5.0) async {
        guard hasInitialized else {
            appendLocalLog("Service is not connected", level: .warning)
            return
        }

        await UDPService.shared.discoverDevices(timeout: timeout)
    }

    func setIpAddress(
        newIp: String,
        netmask: String,
        gateway: String,
        timeout: TimeInterval = 5.0
    ) async -> (success: Bool, message: String) {
        await UDPService.shared.setIpAddress(
            newIp: newIp,
            netmask: netmask,
            gateway: gateway,
            timeout: timeout
        )
    }

    func setRtc(_ rtcString: String, timeout: TimeInterval = 5.0) async -> (success: Bool, message: String) {
        await UDPService.shared.setRtc(rtcString: rtcString, timeout: timeout)
    }

    func getRtc(timeout: TimeInterval = 5.0) async -> (success: Bool, rtc: String?, message: String) {
        await UDPService.shared.getRtc(timeout: timeout)
    }

    func setParameter(
        paramName: String,
        paramValue: Any,
        timeout: TimeInterval = 5.0
    ) async -> (success: Bool, message: String) {
        await UDPService.shared.setParameter(
            paramName: paramName,
            paramValue: paramValue,
            timeout: timeout
        )
    }

    func shellExec(_ command: String, timeout: TimeInterval = 10.0) async -> (success: Bool, message: String) {
        await UDPService.shared.shellExec(command: command, timeout: timeout)
    }

    func hasSecret() -> Bool {
        AuthService.shared.hasSecret()
    }

    func setSecret(_ secret: String) -> Bool {
        let success = AuthService.shared.setSecret(secret)

        if success {
            appendLocalLog("Secret key saved", level: .success)
        } else {
            appendLocalLog("Failed to save secret key", level: .error)
        }

        return success
    }

    func clearLogs() {
        guard hasInitialized else {
            logs.removeAll()
            return
        }

        UDPService.shared.clearLogs()
    }

    private func applySnapshot(_ snapshot: PortableCoreBridge.SessionSnapshot) {
        devices = snapshot.devicesByKey
        sortedDeviceKeys = snapshot.sortedDeviceKeys
        selectedDevice = snapshot.selectedDeviceKey.flatMap { key in
            snapshot.devicesByKey[key]
        }
        logs = snapshot.logs.map { log in
            LogEntry(
                message: log.message,
                level: LogLevel(rawValue: log.level) ?? .info,
                timestamp: log.timestamp
            )
        }
        status = ConnectionStatus(rawValue: snapshot.status) ?? .disconnected
        isDiscovering = snapshot.isDiscovering
    }

    private func appendLocalLog(_ message: String, level: LogLevel) {
        logs.insert(LogEntry(message: message, level: level), at: 0)

        if logs.count > maxLogEntries {
            logs.removeLast(logs.count - maxLogEntries)
        }
    }
}
