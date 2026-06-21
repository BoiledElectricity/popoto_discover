import SwiftUI

extension Color {
    init(hex: UInt, opacity: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255.0,
            green: Double((hex >> 8) & 0xff) / 255.0,
            blue: Double(hex & 0xff) / 255.0,
            opacity: opacity
        )
    }
}

// MARK: - App Theme
struct AppTheme {
    static let aquamarine = Color(hex: 0x77E0BF)
    static let popotoLightBlue = Color(hex: 0xD6F5FF)
    static let primary = Color(hex: 0x2777C3)
    static let secondary = Color(hex: 0x21608A)
    static let darkBlue = Color(hex: 0x234C6F)
    static let clamshellWhite = Color(hex: 0xF2F4F7)
    static let dolphinGrey = Color(hex: 0xA1A1A1)
    static let mantaGrey = Color(hex: 0x727272)
    static let transducerGrey = Color(hex: 0x434343)
    static let deepseaNavy = Color(hex: 0x282B34)

    static let shellBackground = clamshellWhite
    static let surface = Color.white
    static let surfaceAlt = Color(hex: 0xEEF3FA)
    static let border = Color(hex: 0xD3DCE8)
    static let divider = Color(hex: 0xD9E0E8)
    static let heading = transducerGrey
    static let text = transducerGrey
    static let muted = mantaGrey
    static let success = Color(hex: 0x0BA16D)
    static let warning = Color(hex: 0xD58A1F)
    static let error = Color(hex: 0xC41212)

    static let heroGradient = LinearGradient(
        colors: [
            Color(red: 48 / 255, green: 75 / 255, blue: 157 / 255),
            Color(red: 122 / 255, green: 205 / 255, blue: 222 / 255)
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    static let statusGradient = LinearGradient(
        colors: [Color(hex: 0x1FC188), Color(hex: 0x05AB71)],
        startPoint: .top,
        endPoint: .bottom
    )

    static let chromeShadow = Color.black.opacity(0.10)
    static let cardShadow = Color.black.opacity(0.08)
}

extension View {
    func popotoSurfaceCard(
        padding: CGFloat = 18,
        fill: Color = AppTheme.surface,
        stroke: Color = AppTheme.border.opacity(0.9)
    ) -> some View {
        self
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(fill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(stroke, lineWidth: 1)
            )
            .shadow(color: AppTheme.cardShadow, radius: 16, x: 0, y: 8)
    }
}

struct DeviceListView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var deviceManager = DeviceManager.shared
    @State private var showSetIPDialog = false
    @State private var showSetRTCDialog = false
    @State private var showSetParameterDialog = false
    @State private var showActivityLog = false
    @State private var discoverTimeout: Double = 5
    @State private var deviceToShowDetail: HydrophoneDevice?
    @State private var hasPerformedInitialDiscovery = false
    @State private var isSyncingClient = false
    @State private var syncStatusMessage: String?

    var body: some View {
        NavigationView {
            ZStack {
                AppTheme.shellBackground
                    .ignoresSafeArea()

                VStack(spacing: 0) {
                    shellHeader
                    topCommandBar

                    ScrollView {
                        VStack(spacing: 18) {
                            deviceTable

                            if showActivityLog {
                                activityLogSection
                            }

                            footerSummarySection
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 16)
                        .padding(.bottom, 28)
                    }
                }
            }
            .navigationBarHidden(true)
            .sheet(isPresented: $showSetIPDialog) {
                SetIPDialog(isPresented: $showSetIPDialog)
            }
            .sheet(isPresented: $showSetRTCDialog) {
                SetRTCDialog(isPresented: $showSetRTCDialog)
            }
            .sheet(isPresented: $showSetParameterDialog) {
                SetParameterDialog(isPresented: $showSetParameterDialog)
            }
            .sheet(item: $deviceToShowDetail) { device in
                DeviceDetailSheet(device: device)
            }
            .onAppear {
                activateServiceIfNeeded(shouldDiscover: !hasPerformedInitialDiscovery)
            }
            .onChange(of: scenePhase) { _, newPhase in
                handleScenePhaseChange(newPhase)
            }
        }
        .navigationViewStyle(.stack)
        .tint(AppTheme.primary)
    }

    private var shellHeader: some View {
        HStack(alignment: .center, spacing: 12) {
            Image("PopotoIcon")
                .resizable()
                .scaledToFit()
                .frame(width: 38, height: 38)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(AppTheme.border, lineWidth: 1)
                )

            VStack(alignment: .leading, spacing: 3) {
                Text("Popoto Discover")
                    .font(.system(size: 23, weight: .semibold, design: .rounded))
                    .foregroundColor(AppTheme.heading)

                Text(headerSubtitle)
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundColor(AppTheme.muted)
                    .lineLimit(1)
            }

            Spacer(minLength: 12)

            PopotoStatusChip(
                text: statusLabel,
                foreground: .white,
                background: statusBackground
            )

            Button(action: { showActivityLog.toggle() }) {
                Image(systemName: showActivityLog ? "list.bullet.rectangle.fill" : "list.bullet.rectangle")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 40, height: 40)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(showActivityLog ? AppTheme.primary : AppTheme.surfaceAlt)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(showActivityLog ? AppTheme.primary : AppTheme.border, lineWidth: 1)
                    )
                    .foregroundColor(showActivityLog ? .white : AppTheme.primary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(AppTheme.surface)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(AppTheme.divider)
                .frame(height: 1)
        }
        .shadow(color: AppTheme.chromeShadow, radius: 12, x: 0, y: 4)
    }

    private var topCommandBar: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    CommandBarButton(
                        title: "Discover",
                        icon: "antenna.radiowaves.left.and.right",
                        isLoading: deviceManager.isDiscovering,
                        style: .primary
                    ) {
                        Task {
                            await deviceManager.discoverDevices(timeout: discoverTimeout)
                        }
                    }

                    CommandBarButton(
                        title: "Set IP",
                        icon: "network",
                        isDisabled: deviceManager.selectedDevice == nil
                    ) {
                        showSetIPDialog = true
                    }

                    CommandBarButton(
                        title: "Set RTC",
                        icon: "clock",
                        isDisabled: deviceManager.selectedDevice == nil
                    ) {
                        showSetRTCDialog = true
                    }

                    CommandBarButton(
                        title: "Get RTC",
                        icon: "clock.arrow.circlepath",
                        isDisabled: deviceManager.selectedDevice == nil
                    ) {
                        Task {
                            _ = await deviceManager.getRtc()
                        }
                    }

                    CommandBarButton(
                        title: "Param",
                        icon: "slider.horizontal.3",
                        isDisabled: deviceManager.selectedDevice == nil
                    ) {
                        showSetParameterDialog = true
                    }

                    CommandBarButton(
                        title: "Sync Client",
                        icon: "arrow.triangle.2.circlepath",
                        isLoading: isSyncingClient,
                        isDisabled: deviceManager.selectedDevice == nil || isSyncingClient
                    ) {
                        syncSelectedClient()
                    }

                    if let selectedDevice = deviceManager.selectedDevice {
                        CommandBarButton(
                            title: "Details",
                            icon: "chevron.right",
                            style: .secondary
                        ) {
                            deviceToShowDetail = selectedDevice
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
            }
            .background(AppTheme.surface)
        }
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(AppTheme.divider)
                .frame(height: 1)
        }
        .shadow(color: AppTheme.chromeShadow, radius: 10, x: 0, y: 4)
    }

    private var footerSummarySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Popoto Discover")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundColor(.white)

                    Text(heroSubtitle)
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundColor(.white.opacity(0.92))
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer()
            }

            HStack(spacing: 10) {
                HeroMetricTile(title: "Devices", value: "\(deviceManager.sortedDevices.count)")
                HeroMetricTile(title: "Selected", value: selectedDeviceLabel)
                HeroMetricTile(title: "Transport", value: "UDP")
                HeroMetricTile(title: "Activity", value: activityLabel)
            }

            if let syncStatusMessage {
                Text(syncStatusMessage)
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundColor(.white.opacity(0.92))
                    .lineLimit(2)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(AppTheme.heroGradient)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.white.opacity(0.14), lineWidth: 1)
        )
        .shadow(color: AppTheme.chromeShadow, radius: 14, x: 0, y: 8)
    }

    private var deviceTable: some View {
        let devices = deviceManager.sortedDevices

        return VStack(alignment: .leading, spacing: 14) {
            sectionHeading(
                title: "Discovered Devices",
                subtitle: devices.isEmpty
                    ? "No devices are currently visible on the network."
                    : "\(devices.count) device\(devices.count == 1 ? "" : "s") available."
            )

            if devices.isEmpty {
                VStack(spacing: 14) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 38, weight: .medium))
                        .foregroundColor(AppTheme.primary)

                    Text("No Devices Found")
                        .font(.system(size: 22, weight: .semibold, design: .rounded))
                        .foregroundColor(AppTheme.heading)

                    Text("Tap Discover to search for Popoto devices on your network.")
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                        .foregroundColor(AppTheme.muted)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 36)
                .popotoSurfaceCard(fill: AppTheme.surfaceAlt.opacity(0.45))
            } else {
                VStack(spacing: 14) {
                    ForEach(devices, id: \.uniqueKey) { device in
                        DeviceRow(
                            device: device,
                            isSelected: deviceManager.selectedDevice?.uniqueKey == device.uniqueKey,
                            onSelect: {
                                if deviceManager.selectedDevice?.uniqueKey == device.uniqueKey {
                                    deviceManager.selectDevice(nil)
                                } else {
                                    deviceManager.selectDevice(device)
                                }
                            },
                            onViewDetails: {
                                deviceToShowDetail = device
                            }
                        )
                    }
                }
            }
        }
    }

    private var activityLogSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                sectionHeading(
                    title: "Activity Log",
                    subtitle: "Recent discovery and command activity."
                )

                Spacer()

                Button("Clear") {
                    deviceManager.clearLogs()
                }
                .buttonStyle(.plain)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundColor(AppTheme.primary)
            }

            ActivityLogView(logs: deviceManager.logs)
                .frame(minHeight: 110, maxHeight: 190)
        }
        .popotoSurfaceCard()
    }

    private func sectionHeading(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 21, weight: .semibold, design: .rounded))
                .foregroundColor(AppTheme.heading)

            Text(subtitle)
                .font(.system(size: 13, weight: .medium, design: .rounded))
                .foregroundColor(AppTheme.muted)
        }
    }

    private var heroSubtitle: String {
        if isSyncingClient {
            return "Updating the selected modem discovery client."
        }

        if let selectedDevice = deviceManager.selectedDevice {
            return "Selected device: \(selectedDevice.displayNameText)"
        }

        if deviceManager.sortedDevices.isEmpty {
            return "Run a network discovery to locate nearby Popoto hardware."
        }

        return "Choose a device to configure IP, time sync, and runtime parameters."
    }

    private var selectedDeviceLabel: String {
        deviceManager.selectedDevice?.displayNameText ?? "None"
    }

    private var activityLabel: String {
        if isSyncingClient {
            return "Syncing"
        }
        if deviceManager.isDiscovering {
            return "Scanning"
        }
        return "Ready"
    }

    private var headerSubtitle: String {
        if let selectedDevice = deviceManager.selectedDevice {
            return "UDP • \(selectedDevice.displayModelText) • \(selectedDevice.displayIpAddressText)"
        }

        if deviceManager.sortedDevices.isEmpty {
            return "UDP discovery ready"
        }

        return "UDP • \(deviceManager.sortedDevices.count) device\(deviceManager.sortedDevices.count == 1 ? "" : "s") available"
    }

    private var statusLabel: String {
        switch deviceManager.status {
        case .connected:
            return deviceManager.isDiscovering ? "UDP Scan" : "UDP"
        case .connecting:
            return "UDP..."
        case .disconnected:
            return "UDP Off"
        }
    }

    private var statusBackground: LinearGradient {
        switch deviceManager.status {
        case .connected:
            return deviceManager.isDiscovering
                ? LinearGradient(colors: [AppTheme.primary, AppTheme.secondary], startPoint: .leading, endPoint: .trailing)
                : AppTheme.statusGradient
        case .connecting:
            return LinearGradient(colors: [AppTheme.warning, AppTheme.primary], startPoint: .leading, endPoint: .trailing)
        case .disconnected:
            return LinearGradient(colors: [AppTheme.dolphinGrey, AppTheme.mantaGrey], startPoint: .leading, endPoint: .trailing)
        }
    }

    private func handleScenePhaseChange(_ newPhase: ScenePhase) {
        switch newPhase {
        case .active:
            activateServiceIfNeeded(shouldDiscover: deviceManager.sortedDevices.isEmpty && !deviceManager.isDiscovering)
        case .background:
            deviceManager.shutdown()
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    private func activateServiceIfNeeded(shouldDiscover: Bool) {
        deviceManager.initialize()

        guard shouldDiscover else { return }

        hasPerformedInitialDiscovery = true
        Task {
            await deviceManager.discoverDevices(timeout: discoverTimeout)
        }
    }

    private func syncSelectedClient() {
        guard !isSyncingClient else { return }

        isSyncingClient = true
        syncStatusMessage = "Preparing client sync..."

        Task {
            let result = await MobileClientSyncService(deviceManager: deviceManager).sync { progress in
                syncStatusMessage = "\(progress.step)/\(progress.totalSteps): \(progress.message)"
            }

            isSyncingClient = false
            syncStatusMessage = result.success ? result.message : "Sync failed: \(result.message)"
        }
    }
}

struct PopotoStatusChip: View {
    let text: String
    let foreground: Color
    let background: LinearGradient

    var body: some View {
        Text(text)
            .font(.system(size: 13, weight: .bold, design: .rounded))
            .foregroundColor(foreground)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                Capsule(style: .continuous)
                    .fill(background)
            )
            .shadow(color: Color.black.opacity(0.12), radius: 8, x: 0, y: 4)
    }
}

struct HeroMetricTile: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: 11, weight: .bold, design: .rounded))
                .tracking(0.8)
                .foregroundColor(Color.white.opacity(0.75))

            Text(value)
                .font(.system(size: 14, weight: .semibold, design: .rounded))
                .foregroundColor(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.white.opacity(0.12))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.14), lineWidth: 1)
        )
    }
}

// MARK: - Device Row

struct DeviceRow: View {
    let device: HydrophoneDevice
    let isSelected: Bool
    var onSelect: (() -> Void)? = nil
    var onViewDetails: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(device.displayNameText)
                        .font(.system(size: 20, weight: .semibold, design: .rounded))
                        .foregroundColor(AppTheme.heading)

                    Text(device.displayModelText)
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                        .foregroundColor(AppTheme.muted)
                }

                Spacer()

                if isSelected {
                    PopotoStatusChip(
                        text: "Selected",
                        foreground: .white,
                        background: LinearGradient(
                            colors: [AppTheme.primary, AppTheme.secondary],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                }
            }

            HStack(spacing: 10) {
                TransportPill(text: device.displayTransportText)

                if device.displayNetworkSummaryText != "Network unknown" {
                    NetworkStatusPill(device: device)
                }

                Button(action: { onViewDetails?() }) {
                    Label("Details", systemImage: "chevron.right")
                        .font(.system(size: 12, weight: .semibold, design: .rounded))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(AppTheme.primary)
                        )
                        .foregroundColor(.white)
                }
                .buttonStyle(.plain)

                if let state = device.displayRecordingStateText {
                    Text(state)
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .padding(.horizontal, 11)
                        .padding(.vertical, 8)
                        .background(
                            Capsule(style: .continuous)
                                .fill(recordingChipColor(for: state).opacity(0.12))
                        )
                        .foregroundColor(recordingChipColor(for: state))
                }

                Spacer()
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                InfoBadge(icon: "cpu", label: "Device ID", value: device.displayDeviceIdText)
                InfoBadge(icon: "number", label: "Serial", value: device.displaySerialText)
                InfoBadge(icon: "network", label: "IPv4", value: device.displayIpAddressText)
                InfoBadge(icon: "memorychip", label: "Firmware", value: shortFirmware(device.displayFirmwareVersionText))
                InfoBadge(
                    icon: "battery.100",
                    label: "Battery",
                    value: device.batteryVoltage.map { String(format: "%.1f V", $0) } ?? "-",
                    valueColor: batteryColor(device.batteryVoltage)
                )
                InfoBadge(
                    icon: "waveform",
                    label: "Sample Rate",
                    value: device.sampleRate.map { formatSampleRate($0) } ?? "-"
                )
            }

            TimelineView(.periodic(from: .now, by: 5)) { _ in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Image(systemName: "clock")
                            .foregroundColor(AppTheme.primary)
                        Text("Device Time")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(AppTheme.heading)
                        Spacer()
                        Text(device.getInterpolatedRtc() ?? "Unavailable")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(AppTheme.muted)
                    }

                    if let percentage = device.getStoragePercentage(),
                       let total = device.storageTotalGb,
                       let used = device.getClampedStorageUsedGb() {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("Storage")
                                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                                    .foregroundColor(AppTheme.heading)
                                Spacer()
                                Text(String(format: "%.1f / %.1f GB", used, total))
                                    .font(.system(size: 12, weight: .medium, design: .rounded))
                                    .foregroundColor(AppTheme.muted)
                            }

                            GeometryReader { geometry in
                                ZStack(alignment: .leading) {
                                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                                        .fill(AppTheme.surfaceAlt)

                                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                                        .fill(storageColor(percentage))
                                        .frame(width: geometry.size.width * CGFloat(clampedStorageFraction(percentage)))
                                }
                            }
                            .frame(height: 10)

                            Text(String(format: "%.0f%% used", percentage))
                                .font(.system(size: 12, weight: .semibold, design: .rounded))
                                .foregroundColor(storageColor(percentage))
                        }
                    }
                }
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(AppTheme.surfaceAlt.opacity(0.7))
                )
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(AppTheme.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(isSelected ? AppTheme.primary : AppTheme.border, lineWidth: isSelected ? 2 : 1)
        )
        .shadow(color: AppTheme.cardShadow, radius: 14, x: 0, y: 8)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .onTapGesture {
            onSelect?()
        }
    }

    private func shortFirmware(_ fw: String?) -> String {
        guard let fw = fw else { return "-" }
        if fw == "Unavailable" {
            return fw
        }
        if let plusIndex = fw.firstIndex(of: "+") {
            return String(fw[..<plusIndex])
        }
        if fw.count > 16 {
            return String(fw.prefix(16)) + "..."
        }
        return fw
    }

    private func formatSampleRate(_ rate: Int) -> String {
        if rate >= 1000 {
            return String(format: "%.1f kHz", Double(rate) / 1000.0)
        }
        return "\(rate) Hz"
    }

    private func batteryColor(_ voltage: Double?) -> Color {
        guard let v = voltage else { return AppTheme.muted }
        if v < 10 { return AppTheme.error }
        if v < 12 { return AppTheme.warning }
        return AppTheme.success
    }

    private func storageColor(_ percentage: Double) -> Color {
        if percentage > 90 { return AppTheme.error }
        if percentage > 75 { return AppTheme.warning }
        return AppTheme.primary
    }

    private func recordingChipColor(for state: String) -> Color {
        let normalized = state.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return normalized == "recording" || normalized == "active" ? AppTheme.error : AppTheme.success
    }

    private func clampedStorageFraction(_ percentage: Double) -> Double {
        min(max(percentage, 0), 100) / 100
    }
}

struct CommandBarButton: View {
    enum Style {
        case primary
        case secondary
    }

    let title: String
    let icon: String
    var isLoading: Bool = false
    var isDisabled: Bool = false
    var style: Style = .secondary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Group {
                    if isLoading {
                        ProgressView()
                            .tint(foregroundColor)
                            .frame(width: 14, height: 14)
                    } else {
                        Image(systemName: icon)
                            .font(.system(size: 13, weight: .semibold))
                    }
                }

                Text(title)
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .lineLimit(1)
            }
            .frame(minHeight: 36)
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(backgroundColor)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(borderColor, lineWidth: 1)
            )
            .foregroundColor(foregroundColor)
        }
        .buttonStyle(.plain)
        .disabled(isDisabled || isLoading)
        .opacity(isDisabled ? 0.72 : 1)
        .shadow(color: style == .primary && !isDisabled ? AppTheme.chromeShadow : .clear, radius: 8, x: 0, y: 4)
    }

    private var backgroundColor: Color {
        if isDisabled {
            return AppTheme.surfaceAlt
        }

        switch style {
        case .primary:
            return AppTheme.primary
        case .secondary:
            return AppTheme.surfaceAlt
        }
    }

    private var borderColor: Color {
        if isDisabled {
            return AppTheme.border
        }

        switch style {
        case .primary:
            return AppTheme.primary.opacity(0.9)
        case .secondary:
            return AppTheme.border
        }
    }

    private var foregroundColor: Color {
        if isDisabled {
            return AppTheme.muted
        }

        switch style {
        case .primary:
            return .white
        case .secondary:
            return AppTheme.primary
        }
    }
}

struct TransportPill: View {
    let text: String

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 12, weight: .bold))

            Text(text)
                .font(.system(size: 12, weight: .bold, design: .rounded))
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .background(
            Capsule(style: .continuous)
                .fill(AppTheme.success.opacity(0.12))
        )
        .foregroundColor(AppTheme.success)
    }
}

struct NetworkStatusPill: View {
    let device: HydrophoneDevice

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(tint)
                .frame(width: 7, height: 7)

            Text(device.displayNetworkSummaryText)
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .background(
            Capsule(style: .continuous)
                .fill(tint.opacity(0.12))
        )
        .foregroundColor(tint)
    }

    private var tint: Color {
        if device.hasNetworkWarning {
            return AppTheme.warning
        }
        if device.displayNetworkSummaryText == "Network unknown" {
            return AppTheme.muted
        }
        return AppTheme.success
    }
}

struct InfoBadge: View {
    let icon: String
    let label: String
    let value: String
    var valueColor: Color = AppTheme.heading

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .foregroundColor(AppTheme.primary)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 3) {
                Text(label.uppercased())
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .tracking(0.8)
                    .foregroundColor(AppTheme.muted)

                Text(value)
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundColor(valueColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(AppTheme.surfaceAlt.opacity(0.72))
        )
    }
}

#Preview {
    DeviceListView()
}
