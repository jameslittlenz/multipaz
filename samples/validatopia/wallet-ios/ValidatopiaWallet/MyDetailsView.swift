import SwiftUI

/// The holder's own view of one of their documents: a "viewing" display in the sense of the NZ DISTF flash
/// pass guidance. The warning that this screen isn't for sharing stays pinned while the details
/// scroll. Attributes are a plain list with nothing (age, date of birth) made prominent and no
/// document styling. The portrait isn't on this page; it opens separately on request.
struct MyDetailsView: View {
    @Environment(WalletModel.self) private var model
    let documentId: String
    @Binding var path: [WalletRoute]
    @State private var confirmDelete = false
    @State private var deleteError: String?

    var body: some View {
        let documentInfo = model.documentModel.documentInfos.first { $0.identifier == documentId }
        ValidatopiaScreen {
            if let documentInfo {
                Text("For your own reference. To prove who you are or how old you are, share the document by showing its code.")
                    .font(.subheadline)
                if claimsOf(documentInfo).contains(where: { $0.isPortrait }) {
                    PortraitPlaceholder { path.append(.portrait(documentId)) }
                }
                StatusChip(documentInfo: documentInfo)
                DetailsList(documentInfo: documentInfo)
                SecondaryButton(title: "Remove from this phone", role: .destructive) { confirmDelete = true }
                if let deleteError {
                    Text("Couldn't remove it: \(deleteError)").foregroundStyle(Brand.error)
                }
            } else {
                Text("This document is no longer in the wallet.")
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { NotForSharingBanner() }
        .navigationTitle("My details")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Remove this document?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                Task {
                    do {
                        try await model.deleteDocument(identifier: documentId)
                        path = []
                    } catch {
                        deleteError = error.userMessage
                    }
                }
            }
            Button("Keep", role: .cancel) {}
        } message: {
            Text("It's deleted from this phone. You can get a new one from the issuer at any time.")
        }
    }
}

/// The holder's portrait, alone, behind the same warning.
struct PortraitView: View {
    @Environment(WalletModel.self) private var model
    let documentId: String

    var body: some View {
        let documentInfo = model.documentModel.documentInfos.first { $0.identifier == documentId }
        ValidatopiaScreen {
            if let image = documentInfo.flatMap(portraitImage) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("Your portrait")
            } else {
                Text("No portrait to show.")
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { NotForSharingBanner() }
        .navigationTitle("My portrait")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func portraitImage(_ documentInfo: DocumentInfo) -> UIImage? {
        let portrait = claimsOf(documentInfo).first { $0.isPortrait } as? MdocClaim
        guard let bytes = (portrait?.value as? Bstr)?.value else { return nil }
        return UIImage(data: bytes.toData())
    }
}

/// The DISTF-style warning that a viewing screen can't be relied on: icon and words, never colour alone.
private struct NotForSharingBanner: View {
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .accessibilityHidden(true)
            Text("Do not show this screen. This screen is just for you.")
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(Brand.onError)
        .padding(12)
        .background(Brand.error, in: RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Brand.background)
        .accessibilityElement(children: .combine)
    }
}

private struct PortraitPlaceholder: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: "person.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(Brand.onSurfaceVariant)
                    .frame(width: 96, height: 96)
                    .background(Brand.surfaceVariant, in: RoundedRectangle(cornerRadius: 20))
                Text("Tap to view portrait")
                    .font(.subheadline.weight(.semibold))
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("View portrait")
        .accessibilityAddTraits(.isButton)
    }
}

/// Whether the credential is currently valid, as an icon plus words.
private struct StatusChip: View {
    let documentInfo: DocumentInfo

    var body: some View {
        if let credential = documentInfo.credentialInfos.first?.credential {
            let now = Date.now
            let from = Date(kotlinInstant: credential.validFrom)
            let until = Date(kotlinInstant: credential.validUntil)
            let untilText = until.formatted(date: .long, time: .omitted)
            let (valid, text): (Bool, String) = now < from
                ? (false, "Not yet valid")
                : now > until ? (false, "Expired on \(untilText)") : (true, "Valid until \(untilText)")
            let color = valid ? Brand.success : Brand.error
            Label(text, systemImage: valid ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .font(.subheadline.bold())
                .foregroundStyle(color)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .overlay(Capsule().stroke(color, lineWidth: 1))
                .accessibilityElement(children: .combine)
        }
    }
}

private struct DetailsList: View {
    let documentInfo: DocumentInfo

    var body: some View {
        let claims = claimsOf(documentInfo)
        let passportData = claims.filter { ($0 as? MdocClaim)?.namespaceName == PhotoID.shared.DATAGROUPS_NAMESPACE }
        let rest = claims.filter { claim in
            !claim.isPortrait && !passportData.contains { $0 === claim }
        }
        let timeZone = ValidatopiaShared.TimeZone.companion.currentSystemDefault()
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rest.enumerated()), id: \.offset) { _, claim in
                VStack(alignment: .leading, spacing: 2) {
                    Text(claim.displayName).font(.subheadline.weight(.semibold))
                    Text(claim.render(timeZone: timeZone))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
                Divider()
            }
        }
        if !passportData.isEmpty {
            Text("Your Photo ID also carries the signed passport data it was issued from (SOD, DG1 and DG2). Verifiers only get it if they ask and you agree, typically at a border. Sharing DG1 reveals your full name, date of birth, sex, nationality, passport number and expiry together.")
                .font(.subheadline)
        }
    }
}

private func claimsOf(_ documentInfo: DocumentInfo) -> [Claim] {
    documentInfo.credentialInfos.first?.claims ?? []
}

private extension Claim {
    var isPortrait: Bool { (self as? MdocClaim)?.dataElementName == "portrait" }
}

extension Date {
    init(kotlinInstant: KotlinInstant) {
        self.init(timeIntervalSince1970: TimeInterval(kotlinInstant.toEpochMilliseconds()) / 1000)
    }
}
