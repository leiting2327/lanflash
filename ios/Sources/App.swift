import SwiftUI
import UIKit

@main
struct LanFlashApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onAppear {
                    UIApplication.shared.isIdleTimerDisabled = true
                }
        }
    }
}

final class AppModel: ObservableObject {
    @Published var license: LicenseInfo?
    @Published var licenseError: String?
    @Published var activating = false

    let receiver = Receiver()
    let sender = Sender()
    let store = LicenseStore()

    var licenseConfigured: Bool { store.configured }
    var deviceId: String { store.deviceId }

    init() {
        license = store.current()
    }

    func activate(_ code: String) {
        guard !activating else { return }
        activating = true
        licenseError = nil
        Task {
            do {
                let info = try await store.activate(code: code)
                await MainActor.run {
                    self.license = info
                    self.activating = false
                }
            } catch {
                await MainActor.run {
                    self.licenseError = error.localizedDescription
                    self.activating = false
                }
            }
        }
    }

    func clearLicense() {
        store.clear()
        license = nil
    }
}
