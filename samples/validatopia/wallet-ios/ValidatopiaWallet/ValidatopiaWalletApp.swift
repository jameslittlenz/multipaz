import SwiftUI

@main
struct ValidatopiaWalletApp: App {
    var body: some Scene {
        WindowGroup {
            WalletRootView()
                .validatopiaBranding()
        }
    }
}
