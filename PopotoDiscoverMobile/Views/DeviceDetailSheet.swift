import SwiftUI

struct DeviceDetailSheet: View {
    let device: HydrophoneDevice
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                AppTheme.shellBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 16) {
                        detailHero

                        DetailSection(title: "Device", icon: "cpu") {
                            DetailRow(label: "Name", value: device.displayNameText)
                            DetailRow(label: "Model", value: device.displayModelText)
                            DetailRow(label: "Serial", value: device.displaySerialText, monospace: true)
                        }

                        DetailSection(title: "Network", icon: "network") {
                            DetailRow(
                                label: "Status",
                                value: device.displayNetworkSummaryText,
                                valueColor: networkColor(device)
                            )
                            DetailRow(label: "Configured", value: device.displayConfiguredModeText)
                            DetailRow(label: "Active", value: device.displayActiveModeText)
                            DetailRow(
                                label: "Topology",
                                value: device.displayTopologyText,
                                valueColor: networkColor(device)
                            )
                            DetailRow(label: "IP Address", value: device.displayIpAddressText, monospace: true)
                            DetailRow(label: "Netmask", value: device.displayNetmaskText, monospace: true)
                            DetailRow(label: "Gateway", value: device.displayGatewayText, monospace: true)
                            DetailRow(label: "Interface", value: device.displayInterfaceText, monospace: true)
                            DetailRow(label: "Link", value: device.displayLinkStateText, valueColor: networkColor(device))
                            DetailRow(
                                label: "Gateway Ping",
                                value: device.displayGatewayReachableText,
                                valueColor: gatewayReachableColor(device)
                            )
                            DetailRow(label: "MAC Address", value: device.displayMacAddressText, monospace: true)
                        }

                        DetailSection(title: "Hardware", icon: "memorychip") {
                            DetailRow(label: "Firmware", value: device.displayFirmwareVersionText)
                            DetailRow(
                                label: "Battery",
                                value: device.batteryVoltage.map { String(format: "%.2f V", $0) } ?? "Unknown",
                                valueColor: batteryColor(device.batteryVoltage)
                            )
                            DetailRow(
                                label: "Sample Rate",
                                value: device.sampleRate.map { "\($0) Hz" } ?? "Unknown"
                            )
                        }

                        DetailSection(title: "Time", icon: "clock") {
                            TimelineView(.periodic(from: .now, by: 5)) { _ in
                                DetailRow(
                                    label: "Device Time",
                                    value: device.getInterpolatedRtc() ?? "Unknown",
                                    monospace: true
                                )
                            }

                            DetailRow(
                                label: "Last Seen",
                                value: formatDate(device.lastSeen)
                            )
                        }

                        if let total = device.storageTotalGb {
                            DetailSection(title: "Storage", icon: "internaldrive") {
                                DetailRow(label: "Total", value: String(format: "%.1f GB", total))

                                if let used = device.getClampedStorageUsedGb() {
                                    DetailRow(label: "Used", value: String(format: "%.1f GB", used))
                                    DetailRow(label: "Free", value: String(format: "%.1f GB", device.getStorageFreeGb() ?? 0))
                                }

                                if let percentage = device.getStoragePercentage() {
                                    VStack(spacing: 8) {
                                        HStack {
                                            Text("Usage")
                                                .font(.system(size: 13, weight: .semibold, design: .rounded))
                                                .foregroundColor(AppTheme.heading)
                                            Spacer()
                                            Text(String(format: "%.1f%%", percentage))
                                                .font(.system(size: 13, weight: .bold, design: .rounded))
                                                .foregroundColor(storageColor(percentage))
                                        }

                                        GeometryReader { geometry in
                                            ZStack(alignment: .leading) {
                                                RoundedRectangle(cornerRadius: 4)
                                                    .fill(AppTheme.surfaceAlt)

                                                RoundedRectangle(cornerRadius: 4)
                                                    .fill(storageColor(percentage))
                                                    .frame(width: geometry.size.width * CGFloat(clampedStorageFraction(percentage)))
                                            }
                                        }
                                        .frame(height: 10)
                                    }
                                }
                            }
                        }

                        if let state = device.recordingState {
                            DetailSection(title: "Status", icon: "record.circle") {
                                DetailRow(label: "Recording", value: state.capitalized)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 18)
                }
            }
            .navigationTitle("Device Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(AppTheme.surface, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { dismiss() }) {
                        Text("Done")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(AppTheme.primary)
                            .foregroundColor(.white)
                            .cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .tint(AppTheme.primary)
    }

    private var detailHero: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(device.displayNameText)
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundColor(.white)

                    Text(device.displayModelText)
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                        .foregroundColor(.white.opacity(0.92))
                }

                Spacer()

                if let state = device.recordingState {
                    PopotoStatusChip(
                        text: state.capitalized,
                        foreground: .white,
                        background: LinearGradient(
                            colors: [AppTheme.primary, AppTheme.secondary],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                }
            }

            HStack(spacing: 12) {
                HeroMetricTile(title: "IPv4", value: device.displayIpAddressText)
                HeroMetricTile(title: "Network", value: device.displayNetworkSummaryText)
            }
        }
        .padding(22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(AppTheme.heroGradient)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.white.opacity(0.14), lineWidth: 1)
        )
        .shadow(color: AppTheme.chromeShadow, radius: 16, x: 0, y: 10)
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        return formatter.string(from: date)
    }

    private func batteryColor(_ voltage: Double?) -> Color {
        guard let v = voltage else { return AppTheme.muted }
        if v < 10 { return AppTheme.error }
        if v < 12 { return AppTheme.warning }
        return AppTheme.success
    }

    private func networkColor(_ device: HydrophoneDevice) -> Color {
        if device.hasNetworkWarning {
            return AppTheme.warning
        }
        if device.displayNetworkSummaryText == "Network unknown" {
            return AppTheme.muted
        }
        return AppTheme.success
    }

    private func gatewayReachableColor(_ device: HydrophoneDevice) -> Color {
        switch device.gatewayReachable {
        case true:
            return AppTheme.success
        case false:
            return AppTheme.warning
        case nil:
            return AppTheme.muted
        }
    }

    private func storageColor(_ percentage: Double) -> Color {
        if percentage > 90 { return AppTheme.error }
        if percentage > 75 { return AppTheme.warning }
        return AppTheme.primary
    }

    private func clampedStorageFraction(_ percentage: Double) -> Double {
        min(max(percentage, 0), 100) / 100
    }
}

struct DetailSection<Content: View>: View {
    let title: String
    let icon: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .foregroundColor(AppTheme.primary)
                Text(title)
                    .font(.system(size: 20, weight: .semibold, design: .rounded))
                    .foregroundColor(AppTheme.heading)
            }

            VStack(spacing: 10) {
                content
            }
        }
        .popotoSurfaceCard()
    }
}

struct DetailRow: View {
    let label: String
    let value: String
    var monospace: Bool = false
    var valueColor: Color = AppTheme.heading

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 14, weight: .medium, design: .rounded))
                .foregroundColor(AppTheme.muted)
            Spacer()
            if monospace {
                Text(value)
                    .font(.system(.body, design: .monospaced))
                    .foregroundColor(valueColor)
                    .textSelection(.enabled)
            } else {
                Text(value)
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundColor(valueColor)
                    .textSelection(.enabled)
            }
        }
    }
}

#Preview {
    DeviceDetailSheet(device: HydrophoneDevice(
        name: "pmm5544-UNKNOWN-A6E58F",
        model: "pmm5544",
        serial: "UNKNOWN-A6E58F",
        ipAddress: "10.1.0.78",
        macAddress: "96:00:51:7d:30:85",
        firmwareVersion: "4.7.0-alpha+1007.da",
        batteryVoltage: 20.4,
        sampleRate: 102400,
        storageUsedGb: 4.5,
        storageTotalGb: 56.3,
        recordingState: "stopped"
    ))
}
