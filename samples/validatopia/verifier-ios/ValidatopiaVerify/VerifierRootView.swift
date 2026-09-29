import SwiftUI

enum VerifierRoute: Hashable {
    case trust
    case read(PhotoIdUseCase)
    case result(Foundation.UUID)
}

struct VerifierRootView: View {
    @State private var model = VerifierModel()
    @State private var path: [VerifierRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(path: $path)
                .navigationDestination(for: VerifierRoute.self) { route in
                    switch route {
                    case .trust:
                        TrustView()
                    case .read(let useCase):
                        ReadView(useCase: useCase, path: $path)
                    case .result(let id):
                        if let result = model.results[id] {
                            ResultView(result: result, path: $path)
                        }
                    }
                }
        }
        .background { PromptDialogs(promptModel: model.promptModel) }
        .environment(model)
        .onAppear {
            if let debugQr = VerifierModel.debugQr {
                path = [.read(debugQr.useCase)]
            }
        }
    }
}
