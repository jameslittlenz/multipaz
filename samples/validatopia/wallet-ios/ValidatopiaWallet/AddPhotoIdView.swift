import SwiftUI

/// Getting a Photo ID. Only the test-identity path exists in this version; "Verify with passport"
/// (NFC chip read, liveness, face match) arrives in milestone M6 and isn't shown until then.
struct AddPhotoIdView: View {
    private enum PersonasState {
        case loading
        case unavailable
        case failed(String)
        case loaded([Persona])
    }

    @Environment(WalletModel.self) private var model
    @State private var state = PersonasState.loading
    @State private var reloadCount = 0
    @State private var requestingPersona: Persona?
    @State private var requestError: String?

    var body: some View {
        ValidatopiaScreen {
            SectionHeading("Use a test identity")
            Text("Choose a test identity. The Validatopia issuer creates a Photo ID with that person's details, backed by synthetic passport data.")

            switch state {
            case .loading:
                ProgressRow(text: "Loading test identities…")
            case .unavailable:
                Text("This issuer doesn't offer test identities at the moment.")
            case .failed(let message):
                Text("Couldn't reach the issuer: \(message)")
                    .foregroundStyle(Brand.error)
                    .announcing(message)
                SecondaryButton(title: "Try again") { reloadCount += 1 }
            case .loaded(let personas):
                if personas.isEmpty {
                    Text("The issuer has no test identities set up.")
                }
                VStack(spacing: 0) {
                    ForEach(personas, id: \.id) { persona in
                        personaRow(persona)
                        Divider()
                    }
                }
            }

            if let requestingPersona {
                ProgressRow(text: "Asking the issuer for \(requestingPersona.givenName)'s Photo ID…")
            }
            if let requestError {
                Text("The issuer couldn't create the Photo ID: \(requestError)")
                    .foregroundStyle(Brand.error)
                    .announcing(requestError)
            }
        }
        .navigationTitle("Get a Photo ID")
        .task(id: reloadCount) { await loadPersonas() }
    }

    private func personaRow(_ persona: Persona) -> some View {
        let name = "\(persona.givenName) \(persona.familyName)"
        return Button {
            Task { await request(persona) }
        } label: {
            HStack(spacing: 16) {
                Image(systemName: "person.crop.circle")
                    .font(.title2)
                    .accessibilityHidden(true)
                VStack(alignment: .leading) {
                    Text(name).font(.body)
                    Text("Test identity").font(.subheadline).foregroundStyle(Brand.onSurfaceVariant)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(Brand.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(requestingPersona != nil)
        .accessibilityLabel(name)
        .accessibilityHint("Gets a Photo ID for this test identity")
    }

    private func loadPersonas() async {
        state = .loading
        do {
            state = .loaded(try await model.idvClient().listPersonas())
        } catch {
            state = error.kotlinException is IdvUnavailableException ? .unavailable : .failed(error.userMessage)
        }
    }

    private func request(_ persona: Persona) async {
        requestingPersona = persona
        requestError = nil
        defer { requestingPersona = nil }
        do {
            try await model.requestPhotoId(for: persona)
        } catch {
            requestError = error.userMessage
        }
    }
}
