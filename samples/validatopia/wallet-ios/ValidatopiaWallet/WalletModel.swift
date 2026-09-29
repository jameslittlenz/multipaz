import Foundation
import LocalAuthentication
import Observation
import UIKit

/// The wallet's long-lived state: storage, the Photo IDs, presentment and provisioning.
@MainActor
@Observable
final class WalletModel {
    enum LoadState {
        case loading
        case ready
        case failed(String)
    }

    private(set) var loadState = LoadState.loading

    /// Whether the user has accepted the welcome screen's terms.
    var consentAccepted: Bool = UserDefaults.standard.bool(forKey: Keys.consentAccepted) {
        didSet { UserDefaults.standard.set(consentAccepted, forKey: Keys.consentAccepted) }
    }

    /// The Validatopia issuer's base URL.
    var issuerUrl: String = UserDefaults.standard.string(forKey: Keys.issuerUrl) ?? WalletModel.defaultIssuerUrl {
        didSet { UserDefaults.standard.set(issuerUrl, forKey: Keys.issuerUrl) }
    }

    /// The identifier of the Photo ID most recently added to the wallet, to open once issued.
    private(set) var lastAddedDocumentId: String?

    private(set) var documentStore: DocumentStore!
    private(set) var documentModel: DocumentModel!
    private(set) var presentmentSource: PresentmentSource!
    private(set) var provisioningModel: ProvisioningModel!
    let promptModel = Platform.shared.promptModel

    private var storage: Storage!
    private var secureArea: SecureArea!
    private var backend: (url: String, backend: OpenID4VCIBackend)?
    private let httpClient = HttpClient(engineFactory: Darwin()) { config in
        // The provisioning and IDV clients must see redirects, not follow them.
        config.followRedirects = false
    }

    /// Debug builds sign wallet attestations with the public Multipaz development identity
    /// (`DevWalletBackend`), because the Simulator can't use App Attest and a free developer account
    /// can't provision it. Release builds use the attested Validatopia back-end next to the issuer.
    static let usesDevelopmentAttestation: Bool = {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }()

    static let defaultIssuerUrl: String =
        (Bundle.main.object(forInfoDictionaryKey: "ValidatopiaIssuerUrl") as? String).flatMap {
            $0.isEmpty ? nil : $0
        } ?? "http://localhost:8000/openid4vci"

    func load() async {
        do {
            PromptModel.Companion.shared.setGlobal(promptModel: promptModel)
            storage = Platform.shared.nonBackedUpStorage
            #if targetEnvironment(simulator)
            // The Simulator has no Secure Enclave to rely on.
            secureArea = try await SoftwareSecureArea.companion.create(storage: storage)
            #else
            secureArea = try await Platform.shared.getSecureArea(storage: storage)
            #endif
            let documentTypeRepository = ValidatopiaWallet.shared.createDocumentTypeRepository()
            documentStore = DocumentStore.Builder(
                storage: storage,
                secureAreaRepository: SecureAreaRepository.Builder().add(secureArea: secureArea).build()
            ).build()
            try await applyCardArtToExistingDocuments()
            watchDocuments()
            documentModel = try await DocumentModel(
                documentStore: documentStore,
                documentTypeRepository: documentTypeRepository
            )
            presentmentSource = ValidatopiaWallet.shared.createPresentmentSource(
                documentStore: documentStore,
                documentTypeRepository: documentTypeRepository,
                readerTrustManager: ValidatopiaTrust.shared.createReaderTrustManager()
            )
            let selectedSecureArea = secureArea!
            provisioningModel = ProvisioningModel(
                documentProvisioningHandler: DocumentProvisioningHandler.companion.create(
                    secureArea: selectedSecureArea,
                    documentStore: documentStore,
                    defaultDocumentProvisioningSettings: ValidatopiaWallet.shared.provisioningSettings(
                        deviceSecure: Self.deviceHasPasscode
                    ),
                    selectSecureAreaFn: { _, suggestedCreateKeySettings in
                        SelectedSecureArea(secureArea: selectedSecureArea, createKeySettings: suggestedCreateKeySettings)
                    }
                ),
                httpClient: httpClient,
                promptModel: promptModel,
                authorizationSecureArea: selectedSecureArea,
                eventLogger: nil
            )
            loadState = .ready
        } catch {
            loadState = .failed(error.userMessage)
        }
    }

    /// The wallet back-end that signs wallet and key attestations for [issuerUrl].
    func getBackend() async throws -> OpenID4VCIBackend {
        let url = issuerUrl
        if let backend, backend.url == url {
            return backend.backend
        }
        let created: OpenID4VCIBackend = Self.usesDevelopmentAttestation
            ? DevWalletBackend.shared.create()
            : try await ValidatopiaWallet.shared.createAttestedBackend(
                issuerUrl: url,
                httpClientEngine: Darwin(),
                secureArea: secureArea,
                storage: storage
            )
        backend = (url, created)
        return created
    }

    /// A client for the issuer's identity-proofing endpoints.
    func idvClient() async throws -> IdvClient {
        IdvClient(
            issuerUrl: issuerUrl,
            httpClient: httpClient,
            backend: try await getBackend(),
            secureArea: secureArea,
            random: Crypto.shared.secureRandom
        )
    }

    /// Asks the issuer to proof [persona] and starts redeeming the resulting offer.
    func requestPhotoId(for persona: Persona) async throws {
        let offer = try await idvClient().requestPersonaOffer(personaId: persona.id)
        let backend = try await getBackend()
        let clientPreferences = try await ValidatopiaWallet.shared.clientPreferences(backend: backend)
        if !provisioningModel.isActive {
            provisioningModel.launchOpenID4VCIProvisioning(
                offerUri: offer,
                clientPreferences: clientPreferences,
                backend: backend,
                appData: nil
            )
        }
    }

    func deleteDocument(identifier: String) async throws {
        try await documentStore.deleteDocument(identifier: identifier)
    }

    /// Whether keys can require user authentication: they need a device passcode to exist.
    private static var deviceHasPasscode: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    // The issuer supplies no card art, and multipaz-swiftui's fallback art shows the holder's name,
    // which the DISTF flash pass guidance rules out wherever a credential is presented (including
    // the consent sheet). Every Photo ID gets Validatopia's own, non-identifying art instead.
    private func applyCardArtToExistingDocuments() async throws {
        for document in try await documentStore.listDocuments(sort: false) {
            try await applyCardArt(to: document)
        }
    }

    private func watchDocuments() {
        let documentStore = documentStore!
        Task { [weak self] in
            for await event in documentStore.eventFlow {
                guard event is DocumentAdded || event is DocumentUpdated,
                      let document = try? await documentStore.lookupDocument(identifier: event.documentId)
                else { continue }
                if event is DocumentAdded {
                    self?.lastAddedDocumentId = document.identifier
                }
                try? await self?.applyCardArt(to: document)
            }
        }
    }

    private func applyCardArt(to document: Document) async throws {
        guard document.cardArt == nil else { return }
        let art = PhotoIdCardArt.image.pngData()!.toByteString()
        try await document.edit { editor in
            editor.cardArt = art
        }
    }

    private enum Keys {
        static let consentAccepted = "consent_accepted"
        static let issuerUrl = "issuer_url"
    }
}
