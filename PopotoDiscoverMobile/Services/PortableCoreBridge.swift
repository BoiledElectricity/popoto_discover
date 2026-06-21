import Foundation
import SharedCore

final class PortableCoreBridge {
    struct SessionLog {
        let message: String
        let level: String
        let timestamp: Date
    }

    struct OutboundPacket {
        let nonce: String
        let payload: Data
        let timeout: TimeInterval
    }

    struct CompletedOperation {
        let nonce: String
        let type: String
        let success: Bool
        let message: String
        let rtc: String?
        let rediscoverDelay: TimeInterval?
    }

    struct SessionSnapshot {
        let status: String
        let isDiscovering: Bool
        let selectedDeviceKey: String?
        let devicesByKey: [String: HydrophoneDevice]
        let sortedDeviceKeys: [String]
        let logs: [SessionLog]
    }

    struct SessionMutation {
        let snapshot: SessionSnapshot
        let outboundPackets: [OutboundPacket]
        let completedOperations: [CompletedOperation]
    }

    static let shared = PortableCoreBridge()

    private let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    private let decoder = JSONDecoder()
    private let kotlinFacade = AppleCoreFacade()

    private init() {}

    func isValidSecret(_ secret: String) -> Bool {
        kotlinFacade.validateSecret(secret: secret)
    }

    func uniqueKey(for device: HydrophoneDevice) -> String {
        guard let deviceJson = encode(device: device),
              let uniqueKey = kotlinFacade.uniqueKey(deviceJson: deviceJson) else {
            assertionFailure("Failed to encode device for SharedCore unique key lookup")
            return "local:\(device.localId)"
        }

        return uniqueKey
    }

    func devicesMatch(_ lhs: HydrophoneDevice, _ rhs: HydrophoneDevice) -> Bool {
        guard let lhsJson = encode(device: lhs),
              let rhsJson = encode(device: rhs) else {
            assertionFailure("Failed to encode devices for SharedCore match check")
            return false
        }

        return kotlinFacade.matchesDevices(lhsDeviceJson: lhsJson, rhsDeviceJson: rhsJson)
    }

    func initializeSession(now: Date = Date()) -> SessionMutation? {
        decodeMutation(
            from: kotlinFacade.initializeSessionJson(nowEpochMillis: epochMillis(now))
        )
    }

    func shutdownSession() -> SessionMutation? {
        decodeMutation(from: kotlinFacade.shutdownSessionJson())
    }

    func currentSessionSnapshot() -> SessionSnapshot? {
        decodeSnapshot(from: kotlinFacade.sessionSnapshotJson())
    }

    func selectDevice(uniqueKey: String?) -> SessionMutation? {
        decodeMutation(from: kotlinFacade.selectDeviceJson(uniqueKey: uniqueKey))
    }

    func clearLogs() -> SessionMutation? {
        decodeMutation(from: kotlinFacade.clearLogsJson())
    }

    func startDiscovery(timeout: TimeInterval, secret: String?, now: Date = Date()) -> SessionMutation? {
        decodeMutation(
            from: kotlinFacade.startDiscoveryJson(
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        )
    }

    func startSetIp(
        newIp: String,
        netmask: String,
        gateway: String,
        timeout: TimeInterval,
        secret: String?,
        now: Date = Date()
    ) -> SessionMutation? {
        decodeMutation(
            from: kotlinFacade.startSetIpForSelectedDeviceJson(
                newIp: newIp,
                netmask: netmask,
                gateway: gateway,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        )
    }

    func startSetRtc(
        rtc: String,
        timeout: TimeInterval,
        secret: String?,
        now: Date = Date()
    ) -> SessionMutation? {
        decodeMutation(
            from: kotlinFacade.startSetRtcForSelectedDeviceJson(
                rtc: rtc,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        )
    }

    func startGetRtc(
        timeout: TimeInterval,
        secret: String?,
        now: Date = Date()
    ) -> SessionMutation? {
        decodeMutation(
            from: kotlinFacade.startGetRtcForSelectedDeviceJson(
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        )
    }

    func startSetParam(
        paramName: String,
        paramValue: Any,
        timeout: TimeInterval,
        secret: String?,
        now: Date = Date()
    ) -> SessionMutation? {
        let mutationJson: String?

        switch paramValue {
        case let value as String:
            mutationJson = kotlinFacade.startSetParamStringForSelectedDeviceJson(
                paramName: paramName,
                paramValue: value,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        case let value as Bool:
            mutationJson = kotlinFacade.startSetParamBooleanForSelectedDeviceJson(
                paramName: paramName,
                paramValue: value,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        case let value as Int:
            mutationJson = kotlinFacade.startSetParamLongForSelectedDeviceJson(
                paramName: paramName,
                paramValue: Int64(value),
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        case let value as Int64:
            mutationJson = kotlinFacade.startSetParamLongForSelectedDeviceJson(
                paramName: paramName,
                paramValue: value,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        case let value as Double:
            mutationJson = kotlinFacade.startSetParamDoubleForSelectedDeviceJson(
                paramName: paramName,
                paramValue: value,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        case let value as Float:
            mutationJson = kotlinFacade.startSetParamDoubleForSelectedDeviceJson(
                paramName: paramName,
                paramValue: Double(value),
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        default:
            assertionFailure("Unsupported parameter value type for SharedCore session engine")
            return nil
        }

        return decodeMutation(from: mutationJson)
    }

    func startShellExec(
        command: String,
        timeout: TimeInterval,
        secret: String?,
        now: Date = Date()
    ) -> SessionMutation? {
        decodeMutation(
            from: kotlinFacade.startShellExecForSelectedDeviceJson(
                command: command,
                timeoutMillis: timeoutMillis(timeout),
                secret: secret,
                nowEpochMillis: epochMillis(now)
            )
        )
    }

    func handleIncomingPacket(_ data: Data, secret: String?, receivedAt: Date = Date()) -> SessionMutation? {
        guard let json = String(data: data, encoding: .utf8),
              let mutationJson = kotlinFacade.handleIncomingPacketJson(
                json: json,
                secret: secret,
                receivedAtEpochMillis: epochMillis(receivedAt)
              ) else {
            return nil
        }

        return decodeMutation(from: mutationJson)
    }

    func handleTimeout(nonce: String, now: Date = Date()) -> SessionMutation? {
        guard let mutationJson = kotlinFacade.handleTimeoutJson(
            nonce: nonce,
            nowEpochMillis: epochMillis(now)
        ) else {
            return nil
        }

        return decodeMutation(from: mutationJson)
    }

    private func encode(device: HydrophoneDevice) -> String? {
        let payload = DevicePayload(
            localId: device.localId,
            name: device.name,
            deviceId: device.deviceId,
            cpuUid: device.cpuUid,
            identitySource: device.identitySource,
            model: device.model,
            serial: device.serial,
            ipAddress: device.ipAddress,
            macAddress: device.macAddress,
            interfaceName: device.interfaceName,
            configuredMode: device.configuredMode,
            activeMode: device.activeMode,
            linkState: device.linkState,
            topologyHint: device.topologyHint,
            gatewayReachable: device.gatewayReachable,
            netmask: device.netmask,
            gateway: device.gateway,
            firmwareVersion: device.firmwareVersion,
            batteryVoltage: device.batteryVoltage,
            sampleRate: device.sampleRate,
            rtc: device.rtc,
            storageUsedGb: device.storageUsedGb,
            storageTotalGb: device.storageTotalGb,
            recordingState: device.recordingState,
            lastSeenEpochMillis: epochMillis(device.lastSeen),
            rtcQueryEpochMillis: device.rtcQueryTime.map { date in
                epochMillis(date)
            }
        )

        guard let data = try? encoder.encode(payload) else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    private func decodeMutation(from json: String?) -> SessionMutation? {
        guard let json,
              let data = json.data(using: .utf8),
              let rawMutation = try? decoder.decode(RawSessionMutation.self, from: data) else {
            return nil
        }

        return SessionMutation(
            snapshot: decodeSnapshot(rawMutation.snapshot),
            outboundPackets: rawMutation.outboundPackets.compactMap { rawPacket in
                decodeOutboundPacket(rawPacket)
            },
            completedOperations: rawMutation.completedOperations.map { rawOperation in
                decodeCompletedOperation(rawOperation)
            }
        )
    }

    private func decodeSnapshot(from json: String?) -> SessionSnapshot? {
        guard let json,
              let data = json.data(using: .utf8),
              let rawSnapshot = try? decoder.decode(RawSessionSnapshot.self, from: data) else {
            return nil
        }

        return decodeSnapshot(rawSnapshot)
    }

    private func decodeSnapshot(_ rawSnapshot: RawSessionSnapshot) -> SessionSnapshot {
        let devicesByKey = rawSnapshot.devicesByKey.mapValues { payload in
            decodeDevicePayload(payload)
        }
        let logs = rawSnapshot.logs.map { log in
            SessionLog(
                message: log.message,
                level: log.level,
                timestamp: Date(timeIntervalSince1970: TimeInterval(log.timestampEpochMillis) / 1000.0)
            )
        }

        return SessionSnapshot(
            status: rawSnapshot.status,
            isDiscovering: rawSnapshot.isDiscovering,
            selectedDeviceKey: rawSnapshot.selectedDeviceKey,
            devicesByKey: devicesByKey,
            sortedDeviceKeys: rawSnapshot.sortedDeviceKeys,
            logs: logs
        )
    }

    private func decodeOutboundPacket(_ rawPacket: RawOutboundPacket) -> OutboundPacket? {
        guard let payload = rawPacket.payloadJson.data(using: .utf8) else {
            return nil
        }

        return OutboundPacket(
            nonce: rawPacket.nonce,
            payload: payload,
            timeout: TimeInterval(rawPacket.timeoutMillis) / 1000.0
        )
    }

    private func decodeCompletedOperation(_ rawOperation: RawCompletedOperation) -> CompletedOperation {
        CompletedOperation(
            nonce: rawOperation.nonce,
            type: rawOperation.type,
            success: rawOperation.success,
            message: rawOperation.message,
            rtc: rawOperation.rtc,
            rediscoverDelay: rawOperation.rediscoverDelayMillis.map { TimeInterval($0) / 1000.0 }
        )
    }

    private func decodeDevicePayload(_ payload: DevicePayload) -> HydrophoneDevice {
        HydrophoneDevice(
            localId: payload.localId,
            name: payload.name,
            deviceId: payload.deviceId,
            cpuUid: payload.cpuUid,
            identitySource: payload.identitySource,
            model: payload.model,
            serial: payload.serial,
            ipAddress: payload.ipAddress,
            macAddress: payload.macAddress,
            interfaceName: payload.interfaceName,
            configuredMode: payload.configuredMode,
            activeMode: payload.activeMode,
            linkState: payload.linkState,
            topologyHint: payload.topologyHint,
            gatewayReachable: payload.gatewayReachable,
            netmask: payload.netmask,
            gateway: payload.gateway,
            firmwareVersion: payload.firmwareVersion,
            batteryVoltage: payload.batteryVoltage,
            sampleRate: payload.sampleRate,
            rtc: payload.rtc,
            storageUsedGb: payload.storageUsedGb,
            storageTotalGb: payload.storageTotalGb,
            recordingState: payload.recordingState,
            lastSeen: Date(timeIntervalSince1970: TimeInterval(payload.lastSeenEpochMillis) / 1000.0),
            rtcQueryTime: payload.rtcQueryEpochMillis.map {
                Date(timeIntervalSince1970: TimeInterval($0) / 1000.0)
            }
        )
    }

    private func epochMillis(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000.0)
    }

    private func timeoutMillis(_ timeout: TimeInterval) -> Int64 {
        max(0, Int64(timeout * 1000.0))
    }
}

private struct RawSessionMutation: Decodable {
    let snapshot: RawSessionSnapshot
    let outboundPackets: [RawOutboundPacket]
    let completedOperations: [RawCompletedOperation]
}

private struct RawSessionSnapshot: Decodable {
    let status: String
    let isDiscovering: Bool
    let selectedDeviceKey: String?
    let devicesByKey: [String: DevicePayload]
    let sortedDeviceKeys: [String]
    let logs: [RawSessionLog]
}

private struct RawOutboundPacket: Decodable {
    let nonce: String
    let payloadJson: String
    let timeoutMillis: Int64
}

private struct RawCompletedOperation: Decodable {
    let nonce: String
    let type: String
    let success: Bool
    let message: String
    let rtc: String?
    let rediscoverDelayMillis: Int64?
}

private struct RawSessionLog: Decodable {
    let message: String
    let level: String
    let timestampEpochMillis: Int64
}

private struct DevicePayload: Codable {
    let localId: String
    let name: String?
    let deviceId: String?
    let cpuUid: String?
    let identitySource: String?
    let model: String?
    let serial: String?
    let ipAddress: String?
    let macAddress: String?
    let interfaceName: String?
    let configuredMode: String?
    let activeMode: String?
    let linkState: String?
    let topologyHint: String?
    let gatewayReachable: Bool?
    let netmask: String?
    let gateway: String?
    let firmwareVersion: String?
    let batteryVoltage: Double?
    let sampleRate: Int?
    let rtc: String?
    let storageUsedGb: Double?
    let storageTotalGb: Double?
    let recordingState: String?
    let lastSeenEpochMillis: Int64
    let rtcQueryEpochMillis: Int64?
}
