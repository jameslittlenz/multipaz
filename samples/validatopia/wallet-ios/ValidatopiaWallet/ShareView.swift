import AVFoundation
import CoreBluetooth
import SwiftUI

/// The one place to share from, in one of two panels:
/// - In person: a QR code for the verifier to scan, then the two phones connect over Bluetooth. The
///   holder picks in the consent sheet from the documents that answer the request. (iOS apps can't
///   present over NFC.)
/// - Online: scanning a website's OpenID4VP request code, which ``OnlinePresentmentView`` answers.
///
/// The consent sheet names the verifier when its request is signed by a trusted certificate. There's
/// no time limit on any step the holder takes.
struct ShareView: View {
    @Binding var path: [WalletRoute]
    @State private var tab = ShareTab.inPerson

    var body: some View {
        ValidatopiaScreen {
            Picker("Share", selection: $tab) {
                ForEach(ShareTab.allCases) { tab in
                    Text(tab.label).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            // Only the selected panel is shown, so the camera runs only on the Online tab and the
            // Bluetooth code only on the In person tab.
            switch tab {
            case .inPerson: InPersonPanel()
            case .online: OnlinePanel { path.append(.online($0)) }
            }
        }
        .navigationTitle("Share")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private enum ShareTab: CaseIterable, Identifiable {
    case inPerson
    case online

    var id: Self { self }

    var label: String {
        switch self {
        case .inPerson: "In person"
        case .online: "Online"
        }
    }
}

/// Sharing with a verifier in front of the holder: a QR code to scan.
private struct InPersonPanel: View {
    @Environment(WalletModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        if CBManager.authorization == .denied || CBManager.authorization == .restricted {
            Text("Sharing by QR code uses Bluetooth to connect to the verifier's phone. Allow Validatopia Wallet to use Bluetooth in Settings.")
            PrimaryButton(title: "Open Settings") {
                openURL(URL(string: UIApplication.openSettingsURLString)!)
            }
        } else {
            // Nothing preselected: the consent sheet offers every document that answers.
            presentment([])
        }
    }

    private func presentment(_ preselectedDocuments: [Document]) -> some View {
        MdocProximityQrPresentment(
            source: model.presentmentSource,
            preselectedDocuments: preselectedDocuments,
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
                    ShareOptionHeading(systemImages: ["qrcode"], text: "Show this code")
                    Text("Ask the verifier to scan it with Validatopia Verify.")
                        .multilineTextAlignment(.center)
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

/// Sharing with a website: scanning the OpenID4VP request code it shows. `onRequest` opens the
/// request in ``OnlinePresentmentView``, the same as the website's link would on this phone.
private struct OnlinePanel: View {
    let onRequest: (String) -> Void
    @Environment(\.openURL) private var openURL
    @State private var cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
    // Why the last code scanned can't be used, if it can't.
    @State private var problem: String?
    // A new value restarts the scanner, which stops after each code.
    @State private var scanAttempt = 0

    var body: some View {
        VStack(spacing: 16) {
            ShareOptionHeading(systemImages: ["laptopcomputer"], text: "Scan a website's code")
            Text("Point the camera at the code the website shows. You'll see what's being asked for, and who's asking, before anything is shared.")
                .multilineTextAlignment(.center)
            switch cameraAccess {
            case .authorized:
                QrScannerView(onCode: handle)
                    .id(scanAttempt)
                    .aspectRatio(1, contentMode: .fit)
                    .frame(maxWidth: 360)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .accessibilityLabel("Camera view for scanning the website's code")
                if let problem {
                    Text(problem)
                        .foregroundStyle(Brand.error)
                        .multilineTextAlignment(.center)
                        .announcing(problem)
                    SecondaryButton(title: "Scan again") {
                        self.problem = nil
                        scanAttempt += 1
                    }
                }
            case .notDetermined:
                PrimaryButton(title: "Allow camera") {
                    Task {
                        _ = await AVCaptureDevice.requestAccess(for: .video)
                        cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
                    }
                }
            default:
                Text("Scanning a website's code needs the camera. Allow Validatopia Wallet to use it in Settings.")
                    .multilineTextAlignment(.center)
                PrimaryButton(title: "Open Settings") {
                    openURL(URL(string: UIApplication.openSettingsURLString)!)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func handle(_ code: String) {
        let scheme = code.prefix { $0 != ":" }.lowercased()
        if onlineRequestSchemes.contains(scheme) {
            problem = nil
            onRequest(code)
            // Ready for another code when the holder comes back.
            scanAttempt += 1
        } else if scheme == "fido" {
            // A cross-device W3C Digital Credentials API request. On iPhone only a wallet with an
            // Identity Document Provider extension can answer it, which this build doesn't have.
            problem = "This website is asking through the Digital Credentials API, which Validatopia Wallet can't answer on iPhone yet. Ask the website for an OpenID4VP code instead."
        } else {
            problem = "That code isn't a request to share. Scan the code on the website's page for sharing a digital ID."
        }
    }
}

/// The link schemes of online sharing requests that ``OnlinePresentmentView`` answers.
let onlineRequestSchemes: Set<String> = ["openid4vp", "haip-vp", "mdoc"]

/// Share methods' icons, side by side, over their name. VoiceOver reads it as one heading.
private struct ShareOptionHeading: View {
    let systemImages: [String]
    let text: String

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 16) {
                ForEach(systemImages, id: \.self) { name in
                    Image(systemName: name)
                        .font(.system(size: 36))
                }
            }
            .accessibilityHidden(true)
            Text(text)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}
