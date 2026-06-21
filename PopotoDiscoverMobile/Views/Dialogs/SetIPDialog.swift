import SwiftUI

struct SetIPDialog: View {
    @Binding var isPresented: Bool
    @StateObject private var deviceManager = DeviceManager.shared

    @State private var newIP: String = ""
    @State private var netmask: String = "255.255.255.0"
    @State private var gateway: String = ""
    @State private var timeout: Double = 5
    @State private var isSubmitting: Bool = false
    @State private var errorMessage: String?
    @State private var didLoadDefaults: Bool = false

    var body: some View {
        NavigationView {
            Form {
                if let device = deviceManager.selectedDevice {
                    Section("Selected Device") {
                        LabeledContent("Name", value: device.displayNameText)
                        LabeledContent("Serial", value: device.displaySerialText)
                        LabeledContent("Current IP", value: device.displayIpAddressText)
                        LabeledContent("Network", value: device.displayNetworkSummaryText)
                        LabeledContent("Configured", value: device.displayConfiguredModeText)
                        LabeledContent("MAC", value: device.displayMacAddressText)
                    }
                }

                Section("Static IP Configuration") {
                    TextField("New IP Address", text: $newIP)
                        .keyboardType(.decimalPad)
                        .textContentType(.none)
                        .autocorrectionDisabled()

                    TextField("Netmask", text: $netmask)
                        .keyboardType(.decimalPad)
                        .textContentType(.none)
                        .autocorrectionDisabled()

                    TextField("Gateway", text: $gateway)
                        .keyboardType(.decimalPad)
                        .textContentType(.none)
                        .autocorrectionDisabled()

                    if canUseCurrentLease {
                        Button("Use Current IP") {
                            prefillStaticFieldsFromSelectedDevice()
                        }
                    }
                }

                Section("Timeout") {
                    Picker("Timeout", selection: $timeout) {
                        ForEach([5.0, 10.0, 15.0, 20.0, 30.0], id: \.self) { value in
                            Text("\(Int(value)) seconds").tag(value)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                if let error = errorMessage {
                    Section {
                        Text(error)
                            .foregroundColor(AppTheme.error)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppTheme.shellBackground)
            .navigationTitle("Set IP Address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(AppTheme.surface, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .tint(AppTheme.primary)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        isPresented = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        Task { await applySettings() }
                    }
                    .disabled(!isValid || isSubmitting)
                }
            }
            .overlay {
                if isSubmitting {
                    ProgressView("Applying...")
                        .padding()
                        .background(AppTheme.surface)
                        .cornerRadius(12)
                        .shadow(color: AppTheme.chromeShadow, radius: 12, x: 0, y: 6)
                }
            }
            .onAppear {
                loadDefaultsIfNeeded()
            }
        }
    }

    private var canUseCurrentLease: Bool {
        validAddress(deviceManager.selectedDevice?.ipAddress) != nil
    }

    private var isValid: Bool {
        return isValidIP(newIP) && isValidIP(netmask) && (gateway.isEmpty || isValidIP(gateway))
    }

    private func isValidIP(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".")
        guard parts.count == 4 else { return false }

        for part in parts {
            guard let value = Int(part), value >= 0, value <= 255 else {
                return false
            }
        }
        return true
    }

    private func loadDefaultsIfNeeded() {
        guard !didLoadDefaults else { return }
        didLoadDefaults = true
        prefillStaticFieldsFromSelectedDevice()
    }

    private func prefillStaticFieldsFromSelectedDevice() {
        guard let device = deviceManager.selectedDevice else {
            newIP = "10.0.0.238"
            netmask = "255.255.255.0"
            gateway = "10.0.0.1"
            return
        }

        let nextIp = validAddress(device.ipAddress) ?? "10.0.0.238"
        newIP = nextIp
        netmask = validAddress(device.netmask) ?? "255.255.255.0"
        gateway = validAddress(device.gateway) ?? suggestedGateway(for: nextIp) ?? ""
    }

    private func validAddress(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return isValidIP(trimmed) && trimmed != "0.0.0.0" ? trimmed : nil
    }

    private func suggestedGateway(for ip: String) -> String? {
        var parts = ip.split(separator: ".").map(String.init)
        guard parts.count == 4 else { return nil }
        parts[3] = "1"
        return parts.joined(separator: ".")
    }

    private func applySettings() async {
        isSubmitting = true
        errorMessage = nil

        let result = await deviceManager.setIpAddress(
            newIp: newIP,
            netmask: netmask,
            gateway: gateway.isEmpty ? "0.0.0.0" : gateway,
            timeout: timeout
        )

        isSubmitting = false

        if result.success {
            isPresented = false
        } else {
            errorMessage = result.message
        }
    }
}

#Preview {
    SetIPDialog(isPresented: .constant(true))
}
