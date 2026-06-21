import Foundation

struct HydrophoneDevice: Identifiable, Equatable {
    let localId: String

    var id: String { uniqueKey }

    var uniqueKey: String { PortableCoreBridge.shared.uniqueKey(for: self) }

    var name: String?
    var deviceId: String?
    var cpuUid: String?
    var identitySource: String?
    var model: String?
    var serial: String?
    var ipAddress: String?
    var macAddress: String?
    var interfaceName: String?
    var configuredMode: String?
    var activeMode: String?
    var linkState: String?
    var topologyHint: String?
    var gatewayReachable: Bool?
    var netmask: String?
    var gateway: String?
    var firmwareVersion: String?
    var batteryVoltage: Double?
    var sampleRate: Int?
    var rtc: String?
    var storageUsedGb: Double?
    var storageTotalGb: Double?
    var recordingState: String?
    var lastSeen: Date
    var rtcQueryTime: Date?

    var displayNameText: String {
        Self.meaningfulName(name)
            ?? Self.synthesizedName(model: model, serial: serial)
            ?? Self.meaningfulModel(model)
            ?? "Unknown Device"
    }

    var displayModelText: String {
        Self.meaningfulModel(model) ?? "Unknown Model"
    }

    var displaySerialText: String {
        Self.meaningfulSerial(serial) ?? "Unavailable"
    }

    var displayDeviceIdText: String {
        Self.meaningfulDeviceId(deviceId) ?? Self.meaningfulDeviceId(cpuUid) ?? "Unavailable"
    }

    var displayIpAddressText: String {
        Self.meaningfulIpAddress(ipAddress) ?? "Unavailable"
    }

    var displayMacAddressText: String {
        Self.meaningfulMacAddress(macAddress) ?? "Unavailable"
    }

    var displayFirmwareVersionText: String {
        Self.meaningfulFirmwareVersion(firmwareVersion) ?? "Unavailable"
    }

    var displayInterfaceText: String {
        Self.trimmed(interfaceName) ?? "Unavailable"
    }

    var displayConfiguredModeText: String {
        Self.formattedNetworkMode(configuredMode) ?? "Unknown"
    }

    var displayActiveModeText: String {
        Self.formattedNetworkMode(activeMode) ?? "Unknown"
    }

    var displayLinkStateText: String {
        Self.formattedToken(linkState) ?? "Unknown"
    }

    var displayTopologyText: String {
        Self.formattedTopology(topologyHint) ?? "Unknown"
    }

    var displayGatewayReachableText: String {
        switch gatewayReachable {
        case true:
            return "Reachable"
        case false:
            return "Unreachable"
        case nil:
            return "Unknown"
        }
    }

    var displayNetmaskText: String {
        Self.trimmed(netmask) ?? "Unavailable"
    }

    var displayGatewayText: String {
        Self.meaningfulIpAddress(gateway) ?? "Unavailable"
    }

    var displayNetworkSummaryText: String {
        let mode = Self.formattedNetworkMode(activeMode) ?? Self.formattedNetworkMode(configuredMode)
        let topology = Self.formattedTopology(topologyHint)
        let values = [mode, topology].compactMap { $0 }
        return values.isEmpty ? "Network unknown" : values.joined(separator: " | ")
    }

    var hasNetworkWarning: Bool {
        let active = Self.normalized(activeMode)
        let link = Self.normalized(linkState)
        let topology = Self.normalized(topologyHint)

        if active == "fallback_static" || active == "fallback" {
            return true
        }

        if link == "down" || topology == "no_link" || topology == "gateway_unreachable" {
            return true
        }

        return gatewayReachable == false && Self.meaningfulIpAddress(gateway) != nil
    }

    init(
        localId: String = UUID().uuidString,
        name: String? = nil,
        deviceId: String? = nil,
        cpuUid: String? = nil,
        identitySource: String? = nil,
        model: String? = nil,
        serial: String? = nil,
        ipAddress: String? = nil,
        macAddress: String? = nil,
        interfaceName: String? = nil,
        configuredMode: String? = nil,
        activeMode: String? = nil,
        linkState: String? = nil,
        topologyHint: String? = nil,
        gatewayReachable: Bool? = nil,
        netmask: String? = nil,
        gateway: String? = nil,
        firmwareVersion: String? = nil,
        batteryVoltage: Double? = nil,
        sampleRate: Int? = nil,
        rtc: String? = nil,
        storageUsedGb: Double? = nil,
        storageTotalGb: Double? = nil,
        recordingState: String? = nil,
        lastSeen: Date = Date(),
        rtcQueryTime: Date? = nil
    ) {
        self.localId = localId
        self.name = name
        self.deviceId = deviceId
        self.cpuUid = cpuUid
        self.identitySource = identitySource
        self.model = model
        self.serial = serial
        self.ipAddress = ipAddress
        self.macAddress = macAddress
        self.interfaceName = interfaceName
        self.configuredMode = configuredMode
        self.activeMode = activeMode
        self.linkState = linkState
        self.topologyHint = topologyHint
        self.gatewayReachable = gatewayReachable
        self.netmask = netmask
        self.gateway = gateway
        self.firmwareVersion = firmwareVersion
        self.batteryVoltage = batteryVoltage
        self.sampleRate = sampleRate
        self.rtc = rtc
        self.storageUsedGb = storageUsedGb
        self.storageTotalGb = storageTotalGb
        self.recordingState = recordingState
        self.lastSeen = lastSeen
        self.rtcQueryTime = rtcQueryTime
    }

    func matches(_ other: HydrophoneDevice) -> Bool {
        PortableCoreBridge.shared.devicesMatch(self, other)
    }

    // RTC format: YYYY.MM.DD-HH:MM:SS
    private static let rtcDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy.MM.dd-HH:mm:ss"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()

    func getInterpolatedRtc() -> String? {
        guard let rtcString = rtc,
              let queryTime = rtcQueryTime,
              let rtcDate = HydrophoneDevice.rtcDateFormatter.date(from: rtcString) else {
            return rtc
        }

        let elapsedSeconds = Date().timeIntervalSince(queryTime)
        let interpolatedDate = rtcDate.addingTimeInterval(elapsedSeconds)
        return HydrophoneDevice.rtcDateFormatter.string(from: interpolatedDate)
    }

    func getStoragePercentage() -> Double? {
        guard let used = getClampedStorageUsedGb(),
              let total = storageTotalGb,
              total > 0 else {
            return nil
        }

        return (used / total) * 100
    }

    func getStorageFreeGb() -> Double? {
        guard let used = getClampedStorageUsedGb(),
              let total = storageTotalGb,
              total >= 0 else {
            return nil
        }

        return max(total - used, 0)
    }

    func getClampedStorageUsedGb() -> Double? {
        guard let used = storageUsedGb,
              let total = storageTotalGb,
              total >= 0 else {
            return nil
        }

        return min(max(used, 0), total)
    }

    private static func formattedNetworkMode(_ value: String?) -> String? {
        guard let normalized = normalized(value) else { return nil }

        switch normalized {
        case "static":
            return "Static"
        case "fallback", "fallback_static":
            return "Fallback Static"
        default:
            return formattedToken(value)
        }
    }

    private static func formattedTopology(_ value: String?) -> String? {
        guard let normalized = normalized(value) else { return nil }

        switch normalized {
        case "routed":
            return "Routed"
        case "direct", "direct_or_isolated":
            return "Direct"
        case "gateway_unreachable":
            return "Gateway Issue"
        case "no_link":
            return "No Link"
        default:
            return formattedToken(value)
        }
    }

    private static func formattedToken(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        let words = value
            .replacingOccurrences(of: "-", with: "_")
            .split(separator: "_")
            .map { word in
                word.prefix(1).uppercased() + word.dropFirst().lowercased()
            }
        return words.isEmpty ? nil : words.joined(separator: " ")
    }

    private static func normalized(_ value: String?) -> String? {
        trimmed(value)?.lowercased()
    }

    private static func synthesizedName(model: String?, serial: String?) -> String? {
        guard let model = meaningfulModel(model),
              let serial = meaningfulSerial(serial) else {
            return nil
        }

        return "\(model)-\(serial)"
    }

    private static func meaningfulName(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        let normalized = value.lowercased()
        if isPlaceholder(normalized) || normalized.contains("unknown") {
            return nil
        }
        return value
    }

    private static func meaningfulModel(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        return isPlaceholder(value.lowercased()) ? nil : value
    }

    private static func meaningfulSerial(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        let normalized = value.lowercased()
        let compact = normalized.filter(\.isHexDigit)
        let isFactoryPlaceholder = !compact.isEmpty &&
            (compact.allSatisfy { $0 == "0" } || compact.allSatisfy { $0 == "f" })
        if isPlaceholder(normalized) || normalized.hasPrefix("unknown") || isFactoryPlaceholder {
            return nil
        }
        return value
    }

    private static func meaningfulDeviceId(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        let normalized = value.lowercased()
        let compact = normalized.filter(\.isHexDigit)
        let isPlaceholder = !compact.isEmpty &&
            (compact.allSatisfy { $0 == "0" } || compact.allSatisfy { $0 == "f" })
        if isPlaceholder || normalized.hasPrefix("unknown") {
            return nil
        }
        return value
    }

    private static func meaningfulFirmwareVersion(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        return isPlaceholder(value.lowercased()) ? nil : value
    }

    private static func meaningfulIpAddress(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        let normalized = value.lowercased()
        if normalized == "0.0.0.0" || isPlaceholder(normalized) {
            return nil
        }
        return value
    }

    private static func meaningfulMacAddress(_ value: String?) -> String? {
        guard let value = trimmed(value) else { return nil }
        let cleaned = value.lowercased().filter(\.isHexDigit)
        guard !cleaned.isEmpty, cleaned.contains(where: { $0 != "0".first! }) else {
            return nil
        }
        return value
    }

    private static func trimmed(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func isPlaceholder(_ value: String) -> Bool {
        value == "unknown" ||
        value == "n/a" ||
        value == "na" ||
        value == "none" ||
        value == "null" ||
        value == "-" ||
        value == "--" ||
        value == "unavailable"
    }
}
