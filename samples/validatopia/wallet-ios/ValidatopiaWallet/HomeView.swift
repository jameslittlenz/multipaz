import SwiftUI

/// The list of Photo IDs. Each card is one labelled button: multipaz-swiftui's `VerticalCardList`
/// is left out for the same reasons as on Android (unlabelled card images, drag-only reordering,
/// WCAG 2.2 SC 2.5.7).
struct HomeView: View {
    @Environment(WalletModel.self) private var model
    @Binding var path: [WalletRoute]

    var body: some View {
        let documentInfos = model.documentModel.documentInfos
        ValidatopiaScreen {
            if documentInfos.isEmpty {
                Text("You don't have a Photo ID yet.")
                    .font(.title2)
                Text("Get one from the Validatopia issuer to prove who you are or how old you are.")
                PrimaryButton(title: "Get a Photo ID") { path.append(.addPhotoId) }
            } else {
                ForEach(documentInfos, id: \.identifier) { documentInfo in
                    PhotoIdCardButton(documentInfo: documentInfo) {
                        path.append(.document(documentInfo.identifier))
                    }
                }
                Text("To share, open your Photo ID and show its code to the verifier.")
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

/// A Photo ID card with its caption. The card art carries no identifying information (NZ DISTF
/// flash pass guidance); only the caption says whose Photo ID it is.
struct PhotoIdCardButton: View {
    let documentInfo: DocumentInfo
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            PhotoIdCard(documentInfo: documentInfo)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(documentInfo.document.displayName ?? "Photo ID")
        .accessibilityHint("Opens it for sharing")
        .accessibilityAddTraits(.isButton)
    }
}

/// The card art with its italic caption underneath.
struct PhotoIdCard: View {
    let documentInfo: DocumentInfo

    var body: some View {
        VStack(spacing: 8) {
            Image(uiImage: documentInfo.cardArt)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .accessibilityHidden(true)
            Text(documentInfo.document.displayName ?? "Photo ID")
                .italic()
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}
