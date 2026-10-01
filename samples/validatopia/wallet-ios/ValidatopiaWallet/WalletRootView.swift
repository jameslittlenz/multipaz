import SwiftUI

/// The wallet's screens, pushed onto one navigation stack.
enum WalletRoute: Hashable {
    case addPhotoId
    case settings
    /// The Share screen.
    case share
    /// An online sharing request link (see ``OnlinePresentmentView``).
    case online(String)
    case details(String)
    case portrait(String)
}

struct WalletRootView: View {
    @State private var model = WalletModel()
    @State private var path: [WalletRoute] = []
    @State private var provisioningActive = false
    /// An online sharing request link that arrived before the wallet was ready for it.
    @State private var pendingOnlineRequest: String?

    var body: some View {
        Group {
            switch model.loadState {
            case .loading:
                ValidatopiaLaunchView(appName: "Wallet")
            case .failed(let message):
                ValidatopiaScreen {
                    ValidatopiaBrandHeader(appName: "Wallet")
                    Text("The wallet couldn't start: \(message)")
                        .foregroundStyle(Brand.error)
                }
            case .ready:
                if model.consentAccepted {
                    NavigationStack(path: $path) {
                        HomeView(path: $path)
                            .navigationDestination(for: WalletRoute.self, destination: destination)
                    }
                    .sheet(isPresented: $provisioningActive, onDismiss: { model.provisioningModel.cancel() }) {
                        ProvisioningSheet(provisioningModel: model.provisioningModel)
                    }
                    .background { PromptDialogs(promptModel: model.promptModel) }
                    .task { await followProvisioning() }
                } else {
                    NavigationStack {
                        WelcomeView(onAccept: { model.consentAccepted = true })
                    }
                }
            }
        }
        .environment(model)
        .task { await model.load() }
        // A website or app opened an online sharing request link (see Info.plist's URL types).
        .onOpenURL { url in
            guard let scheme = url.scheme?.lowercased(), onlineRequestSchemes.contains(scheme) else { return }
            pendingOnlineRequest = url.absoluteString
            openPendingOnlineRequest()
        }
        .onChange(of: model.isReadyForSharing) { openPendingOnlineRequest() }
    }

    @ViewBuilder
    private func destination(_ route: WalletRoute) -> some View {
        switch route {
        case .addPhotoId: AddPhotoIdView()
        case .settings: SettingsView()
        case .share: ShareView(path: $path)
        case .online(let uri): OnlinePresentmentView(uri: uri)
        case .details(let id): MyDetailsView(documentId: id, path: $path)
        case .portrait(let id): PortraitView(documentId: id)
        }
    }

    /// Opens the pending online sharing request once the wallet is loaded and its terms accepted.
    private func openPendingOnlineRequest() {
        guard model.isReadyForSharing, let uri = pendingOnlineRequest else { return }
        pendingOnlineRequest = nil
        path = [.online(uri)]
    }

    /// Shows the provisioning sheet while the issuer is being talked to, and once a new Photo ID is
    /// issued, closes it and opens that Photo ID.
    private func followProvisioning() async {
        for await state in model.provisioningModel.state {
            switch onEnum(of: state) {
            case .idle:
                provisioningActive = false
            case .credentialsIssued(let issued):
                provisioningActive = false
                model.provisioningModel.cancel()
                if issued.isNewlyIssued {
                    path = [.details(issued.document.identifier)]
                }
            default:
                provisioningActive = true
            }
        }
    }
}

/// Progress while the issuer creates the Photo ID, using multipaz-swiftui's ``ProvisioningView``.
private struct ProvisioningSheet: View {
    let provisioningModel: ProvisioningModel

    var body: some View {
        NavigationStack {
            ScrollView {
                ProvisioningView(
                    provisioningModel: provisioningModel,
                    // Test-identity offers are pre-authorized, so there's never a browser redirect.
                    waitForRedirectLinkInvocation: { _ in "" }
                )
            }
            .navigationTitle("Getting your Photo ID")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { provisioningModel.cancel() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
