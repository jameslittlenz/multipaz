import SwiftUI

/// The presenting screen for one Photo ID, following the NZ DISTF "flash pass" guidance: how to
/// share, and a card with no identifying information. The holder's own details are a separate
/// viewing screen, clearly marked as not for sharing. iOS apps can't present over NFC, so the only
/// way to share here is a code for the verifier to scan (then Bluetooth).
struct DocumentView: View {
    @Environment(WalletModel.self) private var model
    let documentId: String
    @Binding var path: [WalletRoute]

    var body: some View {
        let documentInfo = model.documentModel.documentInfos.first { $0.identifier == documentId }
        ValidatopiaScreen {
            if let documentInfo {
                VStack(spacing: 4) {
                    Image(systemName: "qrcode")
                        .font(.system(size: 44))
                        .accessibilityHidden(true)
                    Text("Show code")
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)

                PhotoIdCard(documentInfo: documentInfo)
                    .accessibilityElement(children: .combine)

                Text("Show the verifier a code to scan with their Validatopia Verify app. You'll see what's being asked for before anything is shared, and they check your Photo ID with their app. Showing your screen isn't proof.")

                PrimaryButton(title: "Show code", systemImage: "qrcode") {
                    path.append(.present(documentId))
                }
                SecondaryButton(title: "View my details") {
                    path.append(.details(documentId))
                }
            } else {
                Text("This Photo ID is no longer in the wallet.")
            }
        }
        .navigationTitle(documentInfo?.document.displayName ?? "Photo ID")
        .navigationBarTitleDisplayMode(.inline)
    }
}
