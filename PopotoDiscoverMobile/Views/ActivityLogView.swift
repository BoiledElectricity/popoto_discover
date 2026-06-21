import SwiftUI

struct ActivityLogView: View {
    let logs: [LogEntry]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 6) {
                ForEach(logs) { entry in
                    LogEntryRow(entry: entry)

                    if logs.last?.id != entry.id {
                        Divider()
                            .overlay(AppTheme.divider)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }
}

struct LogEntryRow: View {
    let entry: LogEntry

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: entry.level.icon)
                .foregroundColor(entry.level.color)
                .font(.system(size: 13, weight: .semibold))
                .frame(width: 18)

            Text(entry.formattedTime)
                .font(.system(.caption, design: .monospaced))
                .foregroundColor(AppTheme.muted)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(
                    Capsule(style: .continuous)
                        .fill(AppTheme.surfaceAlt)
                )

            Text(entry.message)
                .font(.system(size: 13, weight: .medium, design: .rounded))
                .foregroundColor(entry.level.color)
                .lineLimit(3)

            Spacer()
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    ActivityLogView(logs: [
        LogEntry(message: "Service initialized", level: .success),
        LogEntry(message: "Broadcasting device discovery...", level: .info),
        LogEntry(message: "Discovered device: Hydrophone-001", level: .success),
        LogEntry(message: "Failed to connect", level: .error),
        LogEntry(message: "Low battery warning", level: .warning)
    ])
}
