import SwiftUI

struct SetParameterDialog: View {
    @Binding var isPresented: Bool
    @StateObject private var deviceManager = DeviceManager.shared

    @State private var selectedParameter: String = "TxPowerWatts"
    @State private var customParameter: String = ""
    @State private var parameterValue: String = ""
    @State private var timeout: Double = 5
    @State private var isSubmitting: Bool = false
    @State private var errorMessage: String?

    private let commonParameters = [
        "TxPowerWatts",
        "RecordMode",
        "PlayMode",
        "PayloadMode",
        "ChannelInputMask",
        "Custom..."
    ]

    private let parameterDescriptions: [String: String] = [
        "TxPowerWatts": "Transmit power in watts (e.g., 1, 5, 10)",
        "RecordMode": "Recording mode (0 = off, 1 = on)",
        "PlayMode": "Playback mode (0 = off, 1 = on)",
        "PayloadMode": "Payload transmission mode",
        "ChannelInputMask": "Bitmask for active input channels"
    ]

    var body: some View {
        NavigationView {
            Form {
                if let device = deviceManager.selectedDevice {
                    Section("Selected Device") {
                        LabeledContent("Name", value: device.displayNameText)
                        LabeledContent("Serial", value: device.displaySerialText)
                    }
                }

                Section("Parameter") {
                    Picker("Parameter", selection: $selectedParameter) {
                        ForEach(commonParameters, id: \.self) { param in
                            Text(param).tag(param)
                        }
                    }

                    if selectedParameter == "Custom..." {
                        TextField("Custom Parameter Name", text: $customParameter)
                            .textContentType(.none)
                            .autocorrectionDisabled()
                    }

                    if let description = parameterDescriptions[selectedParameter] {
                        Text(description)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Section("Value") {
                    TextField("Parameter Value", text: $parameterValue)
                        .textContentType(.none)
                        .autocorrectionDisabled()

                    Text("Enter integer, decimal, boolean (true/false), or string value")
                        .font(.caption)
                        .foregroundColor(.secondary)
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
            .navigationTitle("Set Parameter")
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
        }
    }

    private var effectiveParameterName: String {
        selectedParameter == "Custom..." ? customParameter : selectedParameter
    }

    private var isValid: Bool {
        !effectiveParameterName.isEmpty && !parameterValue.isEmpty
    }

    private func parseValue(_ value: String) -> Any {
        // Try to parse as Int
        if let intValue = Int(value) {
            return intValue
        }

        // Try to parse as Double
        if let doubleValue = Double(value) {
            return doubleValue
        }

        // Try to parse as Bool
        if value.lowercased() == "true" {
            return true
        }
        if value.lowercased() == "false" {
            return false
        }

        // Default to String
        return value
    }

    private func applySettings() async {
        isSubmitting = true
        errorMessage = nil

        let parsedValue = parseValue(parameterValue)

        let result = await deviceManager.setParameter(
            paramName: effectiveParameterName,
            paramValue: parsedValue,
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
    SetParameterDialog(isPresented: .constant(true))
}
