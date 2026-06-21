import SwiftUI

struct SetRTCDialog: View {
    @Binding var isPresented: Bool
    @StateObject private var deviceManager = DeviceManager.shared

    @State private var rtcString: String = ""
    @State private var timeout: Double = 5
    @State private var isSubmitting: Bool = false
    @State private var errorMessage: String?

    private let rtcDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy.MM.dd-HH:mm:ss"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter
    }()

    var body: some View {
        NavigationView {
            Form {
                if let device = deviceManager.selectedDevice {
                    Section("Selected Device") {
                        LabeledContent("Name", value: device.displayNameText)
                        LabeledContent("Serial", value: device.displaySerialText)
                        LabeledContent("Current RTC", value: device.getInterpolatedRtc() ?? "-")
                    }
                }

                Section("Device Time (UTC)") {
                    TextField("YYYY.MM.DD-HH:MM:SS", text: $rtcString)
                        .keyboardType(.numbersAndPunctuation)
                        .textContentType(.none)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))

                    Button("Use Current Time") {
                        rtcString = rtcDateFormatter.string(from: Date())
                    }

                    Text("Format: YYYY.MM.DD-HH:MM:SS")
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
            .navigationTitle("Set Device Time")
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

    private var isValid: Bool {
        isValidRTC(rtcString)
    }

    private func isValidRTC(_ rtc: String) -> Bool {
        // Format: YYYY.MM.DD-HH:MM:SS
        let pattern = #"^\d{4}\.\d{2}\.\d{2}-\d{2}:\d{2}:\d{2}$"#
        guard rtc.range(of: pattern, options: .regularExpression) != nil else {
            return false
        }

        // Validate actual date/time values
        let parts = rtc.split(separator: "-")
        guard parts.count == 2 else { return false }

        let dateParts = parts[0].split(separator: ".")
        let timeParts = parts[1].split(separator: ":")

        guard dateParts.count == 3, timeParts.count == 3 else { return false }

        guard let year = Int(dateParts[0]), year >= 2000, year <= 2100,
              let month = Int(dateParts[1]), month >= 1, month <= 12,
              let day = Int(dateParts[2]), day >= 1, day <= 31,
              let hour = Int(timeParts[0]), hour >= 0, hour <= 23,
              let minute = Int(timeParts[1]), minute >= 0, minute <= 59,
              let second = Int(timeParts[2]), second >= 0, second <= 59 else {
            return false
        }

        return true
    }

    private func applySettings() async {
        isSubmitting = true
        errorMessage = nil

        let result = await deviceManager.setRtc(rtcString, timeout: timeout)

        isSubmitting = false

        if result.success {
            isPresented = false
        } else {
            errorMessage = result.message
        }
    }
}

#Preview {
    SetRTCDialog(isPresented: .constant(true))
}
