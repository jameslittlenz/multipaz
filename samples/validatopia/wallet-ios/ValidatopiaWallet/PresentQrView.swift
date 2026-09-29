import CoreBluetooth
import SwiftUI

/// Presents a document by QR code: the verifier scans the code, then the two phones connect over
/// Bluetooth. The consent sheet names the verifier when its request is signed by a trusted reader
/// certificate. There's no time limit on any step the holder takes.
struct PresentQrView: View {
    @Environment(WalletModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let documentId: String

    var body: some View {
        let document = model.documentModel.documentInfos.first { $0.identifier == documentId }?.document
        ValidatopiaScreen {
            if let document {
                if CBManager.authorization == .denied || CBManager.authorization == .restricted {
                    Text("Sharing by QR code uses Bluetooth to connect to the verifier's phone. Allow Validatopia Wallet to use Bluetooth in Settings.")
                    PrimaryButton(title: "Open Settings") {
                        openURL(URL(string: UIApplication.openSettingsURLString)!)
                    }
                } else {
                    presentment(document)
                }
            } else {
                Text("This document is no longer in the wallet.")
            }
        }
        .navigationTitle("Share with QR code")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func presentment(_ document: Document) -> some View {
        MdocProximityQrPresentment(
            source: model.presentmentSource,
            preselectedDocuments: [document],
            prepareSettings: { generateQrCode in
                // Show the code straight away; there's nothing to configure.
                ProgressRow(text: "Preparing your code…")
                    .task {
                        generateQrCode(MdocProximityQrSettings(
                            availableConnectionMethods: ValidatopiaTransport.shared.bleConnectionMethods(
                                uuid: ValidatopiaShared.UUID.companion.randomUUID(random: Crypto.shared.secureRandom)
                            ),
                            createTransportOptions: ValidatopiaTransport.shared.options
                        ))
                    }
            },
            showQrCode: { uri, reset in
                VStack(spacing: 16) {
                    Text("Ask the verifier to scan this code with Validatopia Verify.")
                    Image(uiImage: generateQrCode(uri: uri))
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 360)
                        // A light quiet zone keeps it scannable in dark mode too.
                        .padding(16)
                        .background(Color.white)
                        .accessibilityLabel("QR code for sharing your document. Show it to the verifier.")
                    SecondaryButton(title: "Cancel") {
                        reset()
                        dismiss()
                    }
                }
                .onAppear { logQrForDebugging(uri) }
            },
            showTransacting: { reset in
                VStack(spacing: 16) {
                    ProgressRow(text: "Connected. Check your screen to review the request.")
                    SecondaryButton(title: "Cancel") {
                        reset()
                        dismiss()
                    }
                }
            },
            showCompleted: { error, reset in
                VStack(spacing: 16) {
                    let message = completionMessage(error)
                    Text(message)
                        .font(.headline)
                        .announcing(message)
                    PrimaryButton(title: "Done") {
                        reset()
                        dismiss()
                    }
                }
            }
        )
    }

    private func completionMessage(_ error: Error?) -> String {
        guard let error else { return "Shared. The verifier has your answer." }
        if error.kotlinException is PresentmentCanceledException || error.kotlinException is PromptDismissedException {
            return "Nothing was shared."
        }
        return "Sharing didn't complete: \(error.userMessage)"
    }

    /// Debug builds log the engagement so a verifier on a device with no camera view of this
    /// screen can be handed it (see the README). It holds only an ephemeral key and BLE details.
    private func logQrForDebugging(_ uri: String) {
        #if DEBUG
        // NSLog, not print: stdout is block-buffered when it isn't a terminal (e.g. a devicectl console).
        NSLog("%@", "VALIDATOPIA_QR \(uri)")
        #endif
    }
}
