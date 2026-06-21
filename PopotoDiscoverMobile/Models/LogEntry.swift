import Foundation
import SwiftUI

enum LogLevel: String {
    case info
    case success
    case warning
    case error

    var color: Color {
        switch self {
        case .info: return AppTheme.muted
        case .success: return AppTheme.success
        case .warning: return AppTheme.warning
        case .error: return AppTheme.error
        }
    }

    var icon: String {
        switch self {
        case .info: return "info.circle"
        case .success: return "checkmark.circle"
        case .warning: return "exclamationmark.triangle"
        case .error: return "xmark.circle"
        }
    }
}

struct LogEntry: Identifiable {
    let id = UUID()
    let message: String
    let level: LogLevel
    let timestamp: Date

    init(message: String, level: LogLevel = .info, timestamp: Date = Date()) {
        self.message = message
        self.level = level
        self.timestamp = timestamp
    }

    var formattedTime: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: timestamp)
    }
}
