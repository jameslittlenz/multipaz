import SwiftUI

/// Picks the use case, which fixes exactly what the request will ask for.
struct HomeView: View {
    @Binding var path: [VerifierRoute]

    var body: some View {
        ValidatopiaScreen {
            ValidatopiaBrandHeader(appName: "Verify")
            SectionHeading("What are you checking?")
            ForEach(PhotoIdUseCase.allCases, id: \.self) { useCase in
                Button {
                    path.append(.read(useCase))
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(useCase.title).font(.headline)
                        Text(useCase.purpose).font(.subheadline)
                        Text("Asks for: " + useCase.requested.map(\.element.displayName).joined(separator: ", "))
                            .font(.footnote)
                            .foregroundStyle(Brand.onSurfaceVariant)
                    }
                    .foregroundStyle(Brand.onBackground)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(Brand.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint("Starts a check")
            }
        }
        .navigationTitle("Validatopia Verify")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    path.append(.trust)
                } label: {
                    Label("Trusted issuers", systemImage: "checkmark.shield")
                }
            }
        }
    }
}

/// The requested elements, marking the ones the verifier intends to keep.
struct RequestedElementsList: View {
    let useCase: PhotoIdUseCase

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(useCase.requested, id: \.self) { requested in
                let name = requested.element.displayName
                Text(requested.intentToRetain ? "• \(name) (kept on file)" : "• \(name)")
            }
        }
    }
}
