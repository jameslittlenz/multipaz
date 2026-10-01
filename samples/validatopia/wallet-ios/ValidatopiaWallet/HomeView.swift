import SwiftUI

/// The wallet's documents: Photo IDs and the Driver Licences, Gym Memberships and Age
/// Verifications issued with them, stacked under a button to share them in person or online. Each card is one
/// labelled button: multipaz-swiftui's `VerticalCardList` is left out for the same reasons as on
/// Android (unlabelled card images, drag-only reordering, WCAG 2.2 SC 2.5.7).
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
                PrimaryButton(title: "Add a Credential") { path.append(.addPhotoId) }
            } else {
                // No document preselected: the consent sheet offers every one that answers the request.
                ShareButton { path.append(.share) }
                DocumentCardStack(documentInfos: documentInfos) { documentInfo in
                    path.append(.details(documentInfo.identifier))
                }
                IssuanceStatus(state: model.issuanceState) { model.issuance.dismissFailure() }
                SecondaryButton(title: "Add a Credential") { path.append(.addPhotoId) }
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

/// The large button to the Share screen: its QR code and online icons above the label. There's no
/// contactless icon, unlike on Android, as iOS apps can't present over NFC. Outlined, on the page's
/// background, so it doesn't read as another card in the stack below it.
private struct ShareButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                HStack(spacing: 20) {
                    Image(systemName: "qrcode")
                    // Online presentation, to a website or app.
                    Image(systemName: "laptopcomputer")
                }
                .font(.system(size: 36))
                // Outlines only, like the QR code; the laptop's screen is otherwise filled grey.
                .symbolRenderingMode(.monochrome)
                .accessibilityHidden(true)
                Text("Share")
                    .font(.title2)
            }
            .foregroundStyle(Brand.onBackground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
            .background(Brand.background, in: RoundedRectangle(cornerRadius: 24))
            .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(Brand.onBackground, lineWidth: 2))
            .contentShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Share in person with a QR code, or online with a website")
    }
}

/// The cards stacked top to bottom, each overlapping the one before it so only the top of the art
/// (the document type and its subtitle) shows, apart from the last card, which shows in full.
private struct DocumentCardStack: View {
    let documentInfos: [DocumentInfo]
    let onOpen: (DocumentInfo) -> Void

    /// The ID-1 card art's width over the height of the strip left showing: its title and subtitle.
    private static let peekAspectRatio: CGFloat = 1012.0 / (638.0 * 0.32)

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(documentInfos.enumerated()), id: \.element.identifier) { index, documentInfo in
                let card = StackedDocumentCard(documentInfo: documentInfo) { onOpen(documentInfo) }
                    // `DocumentInfo` equality only compares the document identifier, so without
                    // this SwiftUI keeps showing a card's old art after it's repainted.
                    .id(ObjectIdentifier(documentInfo.cardArt))
                if index == documentInfos.count - 1 {
                    card
                } else {
                    // Takes up only the strip that shows; the rest of the card runs on under the
                    // next one, which is drawn over it.
                    Color.clear
                        .aspectRatio(Self.peekAspectRatio, contentMode: .fit)
                        // Full height for the width, not fitted into the strip.
                        .overlay(alignment: .top) { card.fixedSize(horizontal: false, vertical: true) }
                }
            }
        }
    }
}

/// A card in the stack: the art alone, as one labelled button.
private struct StackedDocumentCard: View {
    let documentInfo: DocumentInfo
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(uiImage: documentInfo.cardArt)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 16))
                // A faint edge, so dark cards stand out from a dark background.
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Brand.onBackground.opacity(0.15), lineWidth: 1))
                .shadow(color: .black.opacity(0.25), radius: 6, y: -2)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(documentInfo.document.displayName ?? "Document")
        .accessibilityHint("Opens its details")
        .accessibilityAddTraits(.isButton)
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
