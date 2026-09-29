import SwiftUI

/// The wallet's documents: Photo IDs and the Driver Licences, Gym Memberships and Age
/// Verifications issued with them. Each card is one labelled button: multipaz-swiftui's `VerticalCardList`
/// is left out for the same reasons as on Android (unlabelled card images, drag-only reordering,
/// WCAG 2.2 SC 2.5.7).
struct HomeView: View {
    @Environment(WalletModel.self) private var model
    @Binding var path: [WalletRoute]

    var body: some View {
        let documentInfos = model.documentModel.documentInfos
        ValidatopiaScreen {
            if documentInfos.isEmpty {
                Text("You don't have any documents yet.")
                    .font(.title2)
                Text("Get a Photo ID from the Validatopia issuer to prove who you are or how old you are. It comes with a Driver Licence, Gym Membership and Age Verification.")
                PrimaryButton(title: "Get a Photo ID") { path.append(.addPhotoId) }
            } else {
                ForEach(documentInfos, id: \.identifier) { documentInfo in
                    DocumentCardButton(documentInfo: documentInfo) {
                        path.append(.document(documentInfo.identifier))
                    }
                    // `DocumentInfo` equality only compares the document identifier, so without
                    // this SwiftUI keeps showing a card's old art after it's repainted.
                    .id(ObjectIdentifier(documentInfo.cardArt))
                }
                IssuanceStatus(state: model.issuanceState) { model.issuance.dismissFailure() }
                Text("To share, open a document and show its code to the verifier.")
                SecondaryButton(title: "Get another Photo ID") { path.append(.addPhotoId) }
            }
            PoweredByValid8()
                .padding(.top, 16)
        }
        .navigationTitle("Validatopia Wallet")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    path.append(.settings)
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
        }
    }
}

/// A document card with its caption. The card art shows only the holder's shortened name; the
/// caption gives the full title.
struct DocumentCardButton: View {
    let documentInfo: DocumentInfo
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            DocumentCard(documentInfo: documentInfo)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(documentInfo.document.displayName ?? "Document")
        .accessibilityHint("Opens it for sharing")
        .accessibilityAddTraits(.isButton)
    }
}

/// The card art with its italic caption underneath.
struct DocumentCard: View {
    let documentInfo: DocumentInfo

    var body: some View {
        VStack(spacing: 8) {
            Image(uiImage: documentInfo.cardArt)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .accessibilityHidden(true)
            Text(documentInfo.document.displayName ?? "Document")
                .italic()
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}

/// Progress, or a failure, in issuing the documents that come with a Photo ID.
private struct IssuanceStatus: View {
    let state: ValidatopiaIssuance.State
    let onDismiss: () -> Void

    var body: some View {
        switch onEnum(of: state) {
        case .idle:
            EmptyView()
        case .issuing(let issuing):
            ProgressRow(
                text: issuing.remaining == 1
                    ? "Adding 1 more document…"
                    : "Adding \(issuing.remaining) more documents…"
            )
        case .failed(let failed):
            Text(failed.message)
                .foregroundStyle(Brand.error)
                .announcing(failed.message)
            SecondaryButton(title: "Dismiss", action: onDismiss)
        }
    }
}
