import CoreNFC
import Foundation
import Observation

/// The verifier's long-lived state: the bundled TEST trust anchors (via `PhotoIdVerifier`), the
/// reader key, and results so far. Results are never persisted.
@MainActor
@Observable
final class VerifierModel {
    // The bundled TEST key is checked by ValidatopiaTestPkiTest, so parsing it can't fail at run time.
    let readerKey = try! ValidatopiaTrust.shared.readerKey()
    let verifier = PhotoIdVerifier.companion.createValidatopia(
        storage: Platform.shared.nonBackedUpStorage,
        httpClientEngine: Darwin()
    )
    let promptModel = Platform.shared.promptModel

    /// The NFC reader, if this build may read NFC and the phone can. Reading needs the NFC Tag
    /// Reading capability, which free developer accounts can't provision (see Validatopia.xcconfig).
    let nfcReader: NfcTagReader? = {
        let enabled = (Bundle.main.object(forInfoDictionaryKey: "ValidatopiaNfcReading") as? String) == "YES"
        guard enabled, NFCTagReaderSession.readingAvailable else { return nil }
        return NfcTagReaderCompanion.shared.getReaders().first
    }()

    private(set) var results: [Foundation.UUID: PhotoIdVerification] = [:]

    /// Whether the debug QR payload (see ``debugQr``) has been used; it's read only once.
    var debugQrConsumed = false

    init() {
        PromptModel.Companion.shared.setGlobal(promptModel: promptModel)
    }

    func store(_ result: PhotoIdVerification) -> Foundation.UUID {
        let id = Foundation.UUID()
        results[id] = result
        return id
    }

    /// Debug builds only: a QR payload and use case handed in as launch arguments, standing in for
    /// the camera so a wallet whose screen this phone can't see (another device on the desk, or a
    /// wallet logging its code) can still run the real Bluetooth flow:
    /// `-debugQr "mdoc:…" -debugUseCase CROSS_BORDER`.
    static let debugQr: (useCase: PhotoIdUseCase, qr: String)? = {
        #if DEBUG
        guard let qr = UserDefaults.standard.string(forKey: "debugQr") else { return nil }
        let name = UserDefaults.standard.string(forKey: "debugUseCase") ?? "CROSS_BORDER"
        let useCase = PhotoIdUseCase.allCases.first { $0.name == name } ?? .crossBorder
        return (useCase, qr)
        #else
        return nil
        #endif
    }()
}
