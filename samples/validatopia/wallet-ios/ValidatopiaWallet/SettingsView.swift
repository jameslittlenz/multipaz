import SwiftUI

struct SettingsView: View {
    @Environment(WalletModel.self) private var model
    @State private var url = ""
    @State private var message: String?

    var body: some View {
        let valid = url.hasPrefix("https://") || url.hasPrefix("http://")
        ValidatopiaScreen {
            SectionHeading("Issuer")
            VStack(alignment: .leading, spacing: 4) {
                TextField("Issuer URL", text: $url)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.done)
                    .onChange(of: url) { message = nil }
                Text(valid ? "The Validatopia issuer's address, ending in /openid4vci" : "Must start with https://")
                    .font(.footnote)
                    .foregroundStyle(valid ? Brand.onSurfaceVariant : Brand.error)
            }
            PrimaryButton(title: "Save") {
                model.issuerUrl = url.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                message = "Saved."
            }
            .disabled(!valid || url == model.issuerUrl)
            if let message {
                Text(message).announcing(message)
            }

            SectionHeading("About")
            let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
            Text("Validatopia Wallet \(version). A demonstration app: every identity in it is a test identity.")
            PoweredByValid8()
            if WalletModel.usesDevelopmentAttestation {
                Text("Development build: this copy of the app vouches for itself with a public development key, instead of App Attest checked by the Validatopia back-end. Only issuers configured to trust that key will accept it.")
                    .font(.subheadline)
            }
        }
        .navigationTitle("Settings")
        .onAppear { url = model.issuerUrl }
    }
}
