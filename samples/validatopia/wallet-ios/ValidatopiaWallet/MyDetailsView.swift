import SwiftUI

/// The holder's own view of one of their documents: a "viewing" display in the sense of the NZ DISTF flash
/// pass guidance, opened by tapping the document's card on the home screen. The warning that this
/// screen isn't for sharing stays pinned while the card art and details scroll under it. Attributes
/// are a plain list with nothing (age, date of birth) made prominent. The portrait isn't on this
/// page; it opens separately on request.
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
                Image(uiImage: documentInfo.cardArt)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    // A faint edge, so dark cards stand out from a dark background.
                    .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Brand.onBackground.opacity(0.15), lineWidth: 1))
                    // The art's text (type, subtitle, short name) is in the image, so it's described.
                    .accessibilityLabel("Card for \(documentInfo.document.displayName ?? "this document")")
                Text("For your own reference. To prove who you are or how old you are, use Share on the home screen.")
                    .font(.subheadline)
                if claimsOf(documentInfo).contains(where: { $0.isPortrait }) {
                    PortraitPlaceholder { path.append(.portrait(documentId)) }
                }
                StatusChip(documentInfo: documentInfo)
                if claimsOf(documentInfo).contains(where: { $0.isPassportData }) {
                    DtcBadge()
                        .frame(maxWidth: .infinity)
                }
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
        .navigationTitle(documentInfo?.document.displayName ?? "My details")
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

/// The holder's portrait, alone, behind the same warning: the full width of the page, centred in
/// the space below the warning, with the card art's rounded corners.
struct PortraitView: View {
    @Environment(WalletModel.self) private var model
    let documentId: String

    var body: some View {
        let documentInfo = model.documentModel.documentInfos.first { $0.identifier == documentId }
        Group {
            if let image = documentInfo.flatMap(portraitImage) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .accessibilityLabel("Your portrait")
            } else {
                Text("No portrait to show.")
            }
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: 700, maxHeight: .infinity)
        .frame(maxWidth: .infinity)
        .background(Brand.background.ignoresSafeArea())
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
        .foregroundStyle(Brand.onWarningContainer)
        .padding(12)
        .background(Brand.warningContainer, in: RoundedRectangle(cornerRadius: 8))
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
/// Whether the credential can be used: its dates, and the issuer's revocation list, checked afresh
/// each time the page opens. An icon plus words, never colour alone: revoked in red; suspended,
/// expired or not yet valid in yellow; valid in green. Until the check answers, or if it can't,
/// only the dates count, with a line saying so.
private struct StatusChip: View {
    @Environment(WalletModel.self) private var model
    let documentInfo: DocumentInfo
    @State private var revocation: RevocationCheckResult?

    var body: some View {
        if let credential = documentInfo.credentialInfos.first?.credential {
            let status = CredentialStatus.companion.of(
                validFrom: credential.validFrom,
                validUntil: credential.validUntil,
                revocation: revocation?.state,
                at: KotlinClockCompanion().getSystem().now()
            )
            let from = Date(kotlinInstant: credential.validFrom).formatted(date: .long, time: .omitted)
            let until = Date(kotlinInstant: credential.validUntil).formatted(date: .long, time: .omitted)
            let (text, color, icon): (String, Color, String) = switch status {
            case .valid: ("Valid until \(until)", Brand.success, "checkmark.circle.fill")
            case .notYetValid: ("Not valid until \(from)", Brand.warning, "exclamationmark.triangle.fill")
            case .expired: ("Expired on \(until)", Brand.warning, "exclamationmark.triangle.fill")
            case .suspended: ("Suspended", Brand.warning, "exclamationmark.triangle.fill")
            case .revoked: ("Revoked", Brand.error, "xmark.octagon.fill")
            }
            VStack(spacing: 4) {
                Label(text, systemImage: icon)
                    .font(.subheadline.bold())
                    .foregroundStyle(color)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .overlay(Capsule().stroke(color, lineWidth: 1))
                    .accessibilityElement(children: .combine)
                    .announcing(revocation == nil ? nil : text)
                if let note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(Brand.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)
            .task(id: credential.identifier) {
                revocation = try? await model.credentialStatusChecker.check(credential: credential)
            }
        }
    }

    private var note: String? {
        guard let revocation else { return "Checking with the issuer…" }
        return revocation.state == .unknown ? "Couldn't check with the issuer, so this is from the dates only." : nil
    }
}

private struct DetailsList: View {
    let documentInfo: DocumentInfo

    var body: some View {
        let claims = claimsOf(documentInfo)
        let rest = claims.filter { !$0.isPortrait && !$0.isPassportData }
        let timeZone = ValidatopiaShared.TimeZone.companion.currentSystemDefault()
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(DetailsRow.companion.of(claims: rest).enumerated()), id: \.offset) { _, row in
                VStack(alignment: .leading, spacing: 2) {
                    switch onEnum(of: row) {
                    case .single(let single):
                        let claim = single.claim
                        Text(claim.displayName).font(.subheadline.weight(.semibold))
                        Text(claim.render(timeZone: timeZone))
                            // Monospaced, so the MRZ's fixed-width lines line up as on a passport.
                            .font(claim.isMrz ? .body.monospaced() : .body)
                    case .ageOverGroup(let group):
                        Text("Age").font(.subheadline.weight(.semibold))
                        FlowLayout(spacing: 8) {
                            ForEach(group.ages, id: \.age) { ageOver in
                                AgeOverBadge(ageOver: ageOver)
                            }
                        }
                        .padding(.top, 4)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
                Divider()
            }
        }
    }
}

/// Marks a Photo ID that carries the signed passport data it was issued from (SOD, DG1 and DG2),
/// making it an ICAO Digital Travel Credential of type 1. Styled like the status chip above it.
private struct DtcBadge: View {
    var body: some View {
        Label {
            Text("DTC Compliant (Type 1)")
        } icon: {
            Image("EPassport")
                .resizable()
                .scaledToFit()
                .frame(width: 28, height: 16)
                .accessibilityHidden(true)
        }
        .font(.subheadline.bold())
        .foregroundStyle(Brand.success)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .overlay(Capsule().stroke(Brand.success, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

/// "✓ Over 18" in green or "✗ Over 65" in red: an icon and words, with colour only as reinforcement.
private struct AgeOverBadge: View {
    let ageOver: AgeOver

    var body: some View {
        let color = ageOver.isOver ? Brand.success : Brand.error
        Label("Over \(ageOver.age)", systemImage: ageOver.isOver ? "checkmark" : "xmark")
            .font(.subheadline.bold())
            .foregroundStyle(color)
            .padding(.leading, 8)
            .padding(.trailing, 12)
            .padding(.vertical, 4)
            .background(color.opacity(0.12), in: Capsule())
            .overlay(Capsule().strokeBorder(color, lineWidth: 1))
            .fixedSize()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Over \(ageOver.age): \(ageOver.isOver ? "yes" : "no")")
    }
}

/// Lays its children out left to right, wrapping onto new lines when they don't fit.
private struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let frames = frames(for: subviews, maxWidth: proposal.width ?? .infinity)
        return CGSize(
            width: frames.map(\.maxX).max() ?? 0,
            height: frames.map(\.maxY).max() ?? 0
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (subview, frame) in zip(subviews, frames(for: subviews, maxWidth: bounds.width)) {
            subview.place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(frame.size)
            )
        }
    }

    private func frames(for subviews: Subviews, maxWidth: CGFloat) -> [CGRect] {
        var frames: [CGRect] = []
        var origin = CGPoint.zero
        var lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if origin.x > 0 && origin.x + size.width > maxWidth {
                origin = CGPoint(x: 0, y: origin.y + lineHeight + spacing)
                lineHeight = 0
            }
            frames.append(CGRect(origin: origin, size: size))
            origin.x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        return frames
    }
}

private func claimsOf(_ documentInfo: DocumentInfo) -> [Claim] {
    documentInfo.credentialInfos.first?.claims ?? []
}

private extension Claim {
    var isPortrait: Bool { (self as? MdocClaim)?.dataElementName == "portrait" }
    /// The passport's signed data (SOD, DG1, DG2), which only a Photo ID issued from a passport carries.
    var isPassportData: Bool { (self as? MdocClaim)?.namespaceName == PhotoID.shared.DATAGROUPS_NAMESPACE }
    /// The Photo ID's machine-readable zone, copied from the passport.
    var isMrz: Bool { (self as? MdocClaim)?.dataElementName == "travel_document_mrz" }
}

extension Date {
    init(kotlinInstant: KotlinInstant) {
        self.init(timeIntervalSince1970: TimeInterval(kotlinInstant.toEpochMilliseconds()) / 1000)
    }
}
