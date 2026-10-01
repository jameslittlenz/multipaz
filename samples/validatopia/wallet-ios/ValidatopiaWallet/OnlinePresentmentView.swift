import SwiftUI

/// Answers an online sharing request: an OpenID4VP (or ISO/IEC 18013-7 Annex A) link scanned on the
/// Share screen or opened by a website or app. The consent sheet names the website when its request
/// is signed by a trusted certificate, and otherwise shows it as unverified. There's no time limit
/// on any step the holder takes.
struct OnlinePresentmentView: View {
    @Environment(WalletModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let uri: String
    @State private var outcome: String?

    var body: some View {
        ValidatopiaScreen {
            if let outcome {
                Text(outcome)
                    .font(.headline)
                    .announcing(outcome)
                PrimaryButton(title: "Done") { dismiss() }
            } else {
                ProgressRow(text: "Getting the website's request…")
            }
        }
        .navigationTitle("Share online")
        .navigationBarTitleDisplayMode(.inline)
        .task { await present() }
    }

    private func present() async {
        guard outcome == nil else { return }
        do {
            let redirectUri = try await ValidatopiaOnlinePresentment.shared.present(
                source: model.presentmentSource,
                uri: uri,
                httpClientEngineFactory: Darwin()
            )
            outcome = "Shared. The website has your answer."
            // Same-device flows continue in the browser.
            if let redirectUri, let url = URL(string: redirectUri) {
                openURL(url)
            }
        } catch {
            if error.kotlinException is PresentmentCanceledException || error.kotlinException is PromptDismissedException {
                outcome = "Nothing was shared."
            } else {
                outcome = "Sharing didn't complete: \(error.userMessage)"
            }
        }
    }
}
