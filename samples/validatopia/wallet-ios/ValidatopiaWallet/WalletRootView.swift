import SwiftUI

/// The wallet's screens, pushed onto one navigation stack.
enum WalletRoute: Hashable {
    case addPhotoId
    case settings
    case document(String)
    case present(String)
    case details(String)
    case portrait(String)
}

struct WalletRootView: View {
    @State private var model = WalletModel()
    @State private var path: [WalletRoute] = []
    @State private var provisioningActive = false

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
    }

    @ViewBuilder
    private func destination(_ route: WalletRoute) -> some View {
        switch route {
        case .addPhotoId: AddPhotoIdView()
        case .settings: SettingsView()
        case .document(let id): DocumentView(documentId: id, path: $path)
        case .present(let id): PresentQrView(documentId: id)
        case .details(let id): MyDetailsView(documentId: id, path: $path)
        case .portrait(let id): PortraitView(documentId: id)
        }
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
                    path = [.document(issued.document.identifier)]
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
