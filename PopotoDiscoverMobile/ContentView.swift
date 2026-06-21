import SwiftUI

struct ContentView: View {
    @State private var isSetupComplete: Bool = false
    @State private var hasCheckedSecret: Bool = false

    var body: some View {
        ZStack {
            AppTheme.shellBackground
                .ignoresSafeArea()

            Group {
                if !hasCheckedSecret {
                    VStack(spacing: 18) {
                        ZStack {
                            Circle()
                                .fill(Color.white)
                                .frame(width: 96, height: 96)
                                .shadow(color: AppTheme.cardShadow, radius: 14, x: 0, y: 8)

                            Image("PopotoIcon")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 78, height: 78)
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }

                        ProgressView()
                            .tint(AppTheme.primary)
                            .scaleEffect(1.15)

                        Text("Loading Popoto Discover")
                            .font(.system(size: 20, weight: .semibold, design: .rounded))
                            .foregroundColor(AppTheme.heading)

                        Text("Preparing the shared core and checking secure configuration.")
                            .font(.system(size: 14, weight: .medium, design: .rounded))
                            .foregroundColor(AppTheme.muted)
                            .multilineTextAlignment(.center)
                    }
                    .padding(30)
                    .popotoSurfaceCard()
                    .padding(.horizontal, 24)
                    .onAppear {
                        checkSecretStatus()
                    }
                } else if !isSetupComplete {
                    SecretKeySetupView(isSetupComplete: $isSetupComplete)
                } else {
                    DeviceListView()
                }
            }
        }
        .tint(AppTheme.primary)
    }

    private func checkSecretStatus() {
        if AuthService.shared.hasSecret() {
            isSetupComplete = true
        }
        hasCheckedSecret = true
    }
}

#Preview {
    ContentView()
}
