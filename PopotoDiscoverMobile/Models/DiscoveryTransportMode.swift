import Foundation

enum DiscoveryTransportMode: String, CaseIterable, Identifiable {
    case auto
    case udp
    case l2

    var id: String { rawValue }

    var title: String {
        switch self {
        case .auto:
            return "Auto"
        case .udp:
            return "UDP"
        case .l2:
            return "L2"
        }
    }

    var statusTitle: String {
        switch self {
        case .auto:
            return "Auto/UDP"
        case .udp:
            return "UDP"
        case .l2:
            return "L2"
        }
    }

    var activeTransportTitle: String {
        switch self {
        case .auto:
            return "UDP"
        case .udp:
            return "UDP"
        case .l2:
            return "L2"
        }
    }

    var isUsableOnIOS: Bool {
        self != .l2
    }

    static func storedValue(from rawValue: String?) -> DiscoveryTransportMode {
        guard let rawValue,
              let mode = DiscoveryTransportMode(rawValue: rawValue) else {
            return .auto
        }

        return mode
    }
}
