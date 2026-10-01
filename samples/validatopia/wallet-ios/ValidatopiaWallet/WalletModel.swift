import Foundation
import LocalAuthentication
import Observation
import UIKit

/// The wallet's long-lived state: storage, the documents, presentment and provisioning.
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

    /// Whether the wallet is loaded and its terms accepted, so it can answer a sharing request.
    var isReadyForSharing: Bool {
        if case .ready = loadState { return consentAccepted }
        return false
    }

    /// The Validatopia issuer's base URL.
    var issuerUrl: String = UserDefaults.standard.string(forKey: Keys.issuerUrl) ?? WalletModel.defaultIssuerUrl {
        didSet { UserDefaults.standard.set(issuerUrl, forKey: Keys.issuerUrl) }
    }

    /// The identifier of the document most recently added to the wallet, to open once issued.
    private(set) var lastAddedDocumentId: String?

    /// Progress in issuing the documents that come with a Photo ID.
    private(set) var issuanceState: ValidatopiaIssuance.State = ValidatopiaIssuance.StateIdle.shared

    private(set) var documentStore: DocumentStore!
    private(set) var documentModel: DocumentModel!
    private(set) var presentmentSource: PresentmentSource!
    private(set) var provisioningModel: ProvisioningModel!
    private(set) var issuance: ValidatopiaIssuance!
    /// Checks documents against their issuer's revocation list, for the details screen.
    private(set) var credentialStatusChecker: CredentialStatusChecker!
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
        } ?? "http://localhost:6000/openid4vci"

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
            credentialStatusChecker = CredentialStatusChecker(storage: storage, httpClientEngine: Darwin())
            provisioningModel = createProvisioningModel()
            // The documents that come with a Photo ID are redeemed on a second model that no UI
            // follows (see ValidatopiaIssuance).
            issuance = ValidatopiaIssuance(backgroundProvisioningModel: createProvisioningModel())
            watchIssuance()
            loadState = .ready
        } catch {
            loadState = .failed(error.userMessage)
        }
    }

    private func createProvisioningModel() -> ProvisioningModel {
        let selectedSecureArea = secureArea!
        return ProvisioningModel(
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

    /// Asks the issuer to proof [persona] and starts redeeming the resulting offers: the Photo ID
    /// on [provisioningModel], which the provisioning sheet follows, then the documents that come
    /// with it in the background once the Photo ID is issued.
    func requestPhotoId(for persona: Persona) async throws {
        let offers = try await idvClient().requestPersonaOffers(personaId: persona.id)
        let backend = try await getBackend()
        let clientPreferences = try await ValidatopiaWallet.shared.clientPreferences(backend: backend)
        guard !provisioningModel.isActive, let photoIdOffer = offers.first else { return }
        let photoId = provisioningModel.launchOpenID4VCIProvisioning(
            offerUri: photoIdOffer,
            clientPreferences: clientPreferences,
            backend: backend,
            appData: nil
        )
        let issuance = issuance!
        Task {
            try? await issuance.issueAfterPhotoId(
                photoId: photoId,
                offers: Array(offers.dropFirst()),
                clientPreferences: clientPreferences,
                backend: backend
            )
        }
    }

    private func watchIssuance() {
        let issuance = issuance!
        Task { [weak self] in
            for await state in issuance.state {
                self?.issuanceState = state
            }
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
    // the consent sheet). Every document gets Validatopia's own art instead, in its type's colors
    // with only the holder's shortened name, and documents with older art are brought up to date.
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
        // A new document has no credentials, so no type, until provisioning creates them.
        let style = try await ValidatopiaCardArt.shared.styleFor(document: document)
        guard style != ValidatopiaCardArt.shared.unknown else { return }
        let holderName = try await ValidatopiaCardArt.shared.holderShortName(document: document)
        let art = DocumentCardArt.png(for: style, holderName: holderName)
        guard document.cardArt != art else { return }
        try await document.edit { editor in
            editor.cardArt = art
        }
    }

    private enum Keys {
        static let consentAccepted = "consent_accepted"
        static let issuerUrl = "issuer_url"
    }
}
