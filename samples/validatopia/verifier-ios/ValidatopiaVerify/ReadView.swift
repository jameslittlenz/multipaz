import AVFoundation
import CoreBluetooth
import SwiftUI

/// Engagement for one use case: scan the wallet's QR code, or tap the wallet phone (this phone is
/// the NFC reader). Both then connect over Bluetooth. Nothing here times out; the holder takes as
/// long as they need to decide, and the verifier can cancel.
struct ReadView: View {
    private enum ReadState: Equatable {
        case choose
        case scanningQr
        case working(String)
        case failed(String)
    }

    @Environment(VerifierModel.self) private var model
    @Environment(\.openURL) private var openURL
    let useCase: PhotoIdUseCase
    @Binding var path: [VerifierRoute]
    @State private var state = ReadState.choose
    @State private var task: Task<Void, Never>?
    @State private var cameraDenied = false

    private static let waitingForHolder = "Waiting for the holder to review the request on their phone…"
    private static let holdPhonesTogether = "Hold the wallet phone against the top of this iPhone."

    var body: some View {
        ValidatopiaScreen {
            Text(useCase.purpose)
            if CBManager.authorization == .denied || CBManager.authorization == .restricted {
                Text("Validatopia Verify connects to the wallet over Bluetooth. Allow it to use Bluetooth in Settings.")
                PrimaryButton(title: "Open Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
            } else {
                content
            }
        }
        .navigationTitle(useCase.title)
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { cancel() }
        .onAppear {
            if let debugQr = VerifierModel.debugQr, debugQr.useCase == useCase, !model.debugQrConsumed {
                model.debugQrConsumed = true
                readQr(debugQr.qr)
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch state {
        case .choose, .failed:
            if case .failed(let message) = state {
                Text("That didn't work: \(message)")
                    .foregroundStyle(Brand.error)
                    .announcing(message)
            }
            SectionHeading("How is the holder sharing?")
            PrimaryButton(title: "Scan their QR code", systemImage: "qrcode.viewfinder") { startScanning() }
            if cameraDenied {
                Text("Validatopia Verify needs the camera to scan the code. Allow it in Settings.")
                SecondaryButton(title: "Open Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
            }
            if let nfcReader = model.nfcReader {
                PrimaryButton(title: "Tap their phone", systemImage: "wave.3.right") { readNfc(nfcReader) }
            } else {
                Text("This build can't read NFC, so ask the holder to show a QR code.")
                    .font(.subheadline)
            }
            SectionHeading("This request asks for")
            RequestedElementsList(useCase: useCase)
        case .scanningQr:
            Text("Point the camera at the QR code on the holder's phone.")
            QrScannerView { code in readQr(code) }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel("Camera viewfinder")
            SecondaryButton(title: "Cancel") { cancel() }
        case .working(let message):
            ProgressRow(text: message)
            SecondaryButton(title: "Cancel") { cancel() }
        }
    }

    private func startScanning() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            state = .scanningQr
        case .notDetermined:
            Task {
                if await AVCaptureDevice.requestAccess(for: .video) {
                    state = .scanningQr
                } else {
                    cameraDenied = true
                }
            }
        default:
            cameraDenied = true
        }
    }

    private func readQr(_ code: String) {
        run("Connecting to the wallet…") {
            try await PhotoIdReader.shared.readQr(
                useCase: useCase,
                qrCode: code,
                readerKey: model.readerKey,
                onConnected: { Task { @MainActor in state = .working(Self.waitingForHolder) } }
            )
        }
    }

    private func readNfc(_ reader: NfcTagReader) {
        run(Self.holdPhonesTogether) {
            try await PhotoIdReader.shared.readNfc(
                useCase: useCase,
                nfcReader: reader,
                readerKey: model.readerKey,
                message: Self.holdPhonesTogether,
                onEngaged: { Task { @MainActor in state = .working(Self.waitingForHolder) } }
            )
        }
    }

    private func run(_ initialMessage: String, _ read: @escaping () async throws -> PhotoIdReadResult?) {
        state = .working(initialMessage)
        task = Task {
            do {
                guard let result = try await read() else {
                    state = .choose
                    return
                }
                state = .working("Checking the response…")
                let verification = try await model.verifier.verify(
                    useCase: useCase,
                    deviceResponse: result.deviceResponse,
                    sessionTranscript: result.sessionTranscript,
                    at: KotlinClockCompanion().getSystem().now()
                )
                logResultForDebugging(verification)
                state = .choose
                path.append(.result(model.store(verification)))
            } catch {
                if !Task.isCancelled {
                    state = .failed(error.userMessage)
                }
            }
        }
    }

    /// Debug builds log a one-line summary of each result, for checking a run on a device from the
    /// console. It holds no claim values, only which elements were shared and the checks' outcomes.
    private func logResultForDebugging(_ result: PhotoIdVerification) {
        #if DEBUG
        func panel(_ panel: TrustPanel) -> String {
            "\(panel.title)=\(panel.overall.style.word)[" +
                panel.checks.map { "\($0.label):\($0.outcome.style.word)" }.joined(separator: ",") + "]"
        }
        let parts = [
            "useCase=\(result.useCase.name)",
            panel(result.credentialIssuer),
            result.passportIssuer.map(panel),
            result.passportCheck.map { "dg1Matches=\($0.claimsMatch) authentic=\($0.authentic)" },
            "shared=[\(result.disclosed.map { $0.element.identifier }.joined(separator: ","))]",
            "notShared=\(result.notShared.count)",
            result.dg1Reveals.map { "dg1Reveals=\($0.count) fields" },
        ]
        NSLog("%@", "VALIDATOPIA_RESULT " + parts.compactMap { $0 }.joined(separator: " "))
        #endif
    }

    private func cancel() {
        task?.cancel()
        task = nil
        state = .choose
    }
}
