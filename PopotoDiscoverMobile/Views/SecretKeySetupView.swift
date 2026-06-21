import SwiftUI

struct SecretKeySetupView: View {
    @Binding var isSetupComplete: Bool
    @State private var secretKey: String = ""
    @State private var showSecret: Bool = false
    @State private var errorMessage: String?
    @State private var isValidating: Bool = false

    var body: some View {
        NavigationView {
            ZStack {
                AppTheme.shellBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 18) {
                        headerCard
                        formCard
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 24)
                }
            }
            .navigationBarHidden(true)
        }
        .tint(AppTheme.primary)
    }

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Security Setup")
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .foregroundColor(.white)

                    Text("Match the Popoto web experience by enabling secure communication with the same 64-character hex secret.")
                        .font(.system(size: 14, weight: .medium, design: .rounded))
                        .foregroundColor(.white.opacity(0.92))
                }

                Spacer()

                ZStack {
                    Circle()
                        .fill(Color.white.opacity(0.18))
                        .frame(width: 72, height: 72)

                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundColor(.white)
                }
            }

            HStack(spacing: 12) {
                HeroMetricTile(title: "Format", value: "64 Hex")
                HeroMetricTile(title: "Storage", value: "Keychain")
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

    private var formCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Secret Key")
                    .font(.system(size: 21, weight: .semibold, design: .rounded))
                    .foregroundColor(AppTheme.heading)

                Text("Use the same authentication secret as your Popoto web dashboard.")
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundColor(AppTheme.muted)
            }

            HStack(spacing: 12) {
                Group {
                    if showSecret {
                        TextField("Enter 64-character hex secret", text: $secretKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } else {
                        SecureField("Enter 64-character hex secret", text: $secretKey)
                    }
                }
                .font(.system(.body, design: .monospaced))
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(AppTheme.surfaceAlt)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(AppTheme.border, lineWidth: 1)
                )

                Button(action: { showSecret.toggle() }) {
                    Image(systemName: showSecret ? "eye.slash" : "eye")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(AppTheme.primary)
                        .padding(12)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(AppTheme.surfaceAlt)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(AppTheme.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }

            HStack {
                Text("\(secretKey.count)/64 characters")
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundColor(secretKey.count == 64 ? AppTheme.success : AppTheme.muted)

                Spacer()

                Text(isValidSecret ? "Valid format" : "Waiting for 64 hex characters")
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundColor(isValidSecret ? AppTheme.success : AppTheme.muted)
            }

            if let error = errorMessage {
                Text(error)
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundColor(AppTheme.error)
            }

            VStack(spacing: 12) {
                Button(action: saveAndContinue) {
                    HStack(spacing: 10) {
                        if isValidating {
                            ProgressView()
                                .tint(.white)
                        }

                        Text("Save and Continue")
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(isValidSecret ? AppTheme.primary : AppTheme.dolphinGrey)
                    )
                    .foregroundColor(.white)
                    .shadow(color: isValidSecret ? AppTheme.chromeShadow : .clear, radius: 10, x: 0, y: 6)
                }
                .buttonStyle(.plain)
                .disabled(!isValidSecret || isValidating)

                Button(action: skipSetup) {
                    Text("Continue without authentication")
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                        .foregroundColor(AppTheme.primary)
                }
                .buttonStyle(.plain)

                Text("Not recommended")
                    .font(.system(size: 12, weight: .medium, design: .rounded))
                    .foregroundColor(AppTheme.muted)
            }
        }
        .popotoSurfaceCard()
    }

    private var isValidSecret: Bool {
        AuthService.shared.isValidSecret(secretKey)
    }

    private func saveAndContinue() {
        guard isValidSecret else {
            errorMessage = "Invalid secret key format"
            return
        }

        isValidating = true
        errorMessage = nil

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            if AuthService.shared.setSecret(secretKey) {
                isSetupComplete = true
            } else {
                errorMessage = "Failed to save secret key"
            }
            isValidating = false
        }
    }

    private func skipSetup() {
        isSetupComplete = true
    }
}

#Preview {
    SecretKeySetupView(isSetupComplete: .constant(false))
}
