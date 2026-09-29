import SwiftUI

/// What was shared, how far it can be trusted, and what wasn't shared.
struct ResultView: View {
    let result: PhotoIdVerification
    @Binding var path: [VerifierRoute]

    private static let portrait = PhotoIdElement(namespace: PhotoID.shared.ISO_23220_2_NAMESPACE, identifier: "portrait")
    private static let ageOver18 = PhotoIdElement(namespace: PhotoID.shared.ISO_23220_2_NAMESPACE, identifier: "age_over_18")

    var body: some View {
        ValidatopiaScreen {
            Verdict(result: result)
            AgeAnswer(result: result)

            let portrait = imageBytes(result.valueOf(element: Self.portrait))
            if let passportCheck = result.passportCheck {
                Faces(portrait: portrait, passportCheck: passportCheck)
            } else if portrait != nil {
                PhotoBlock(
                    bytes: portrait,
                    caption: "Photo ID portrait",
                    description: "Photo ID portrait. Compare it with the person in front of you."
                )
                .frame(maxWidth: 280)
                .frame(maxWidth: .infinity)
            }

            TrustPanelCard(panel: result.credentialIssuer)
            if let passportIssuer = result.passportIssuer {
                TrustPanelCard(panel: passportIssuer)
            }
            if let passportCheck = result.passportCheck {
                Dg1Comparison(passportCheck: passportCheck)
            }

            SharedData(result: result)
            if let dg1Reveals = result.dg1Reveals {
                Dg1RevealsNote(fields: dg1Reveals)
            }
            NotShared(result: result)

            PrimaryButton(title: "Check another person") { path.removeLast() }
            SecondaryButton(title: "Done") { path = [] }
        }
        .navigationTitle(result.useCase.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func imageBytes(_ value: ClaimValue?) -> KotlinByteArray? {
        guard let value, case .image(let image) = onEnum(of: value) else { return nil }
        return image.bytes
    }
}

private struct Verdict: View {
    let result: PhotoIdVerification

    var body: some View {
        let outcomes = [result.credentialIssuer.overall] + (result.passportIssuer.map { [$0.overall] } ?? [])
        let overall: CheckOutcome = outcomes.contains(.failed) ? .failed
            : outcomes.contains(.unknown) ? .unknown
            : outcomes.contains(.warning) ? .warning : .passed
        let (title, detail): (String, String) = switch overall {
        case .passed: ("Verified", "Every check passed.")
        case .warning: ("Verified against TEST issuers", "Every check passed, but only against Validatopia's test trust anchors. Fine for a demo; not proof of a real identity.")
        case .unknown: ("Verified, with checks outstanding", "The data is authentic, but some checks couldn't run. See below.")
        case .failed: ("Don't rely on this", "At least one check failed. See below.")
        }
        VStack(alignment: .leading, spacing: 8) {
            OutcomeBadge(outcome: overall)
            Text(title).font(.title2.bold())
            Text(detail)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Brand.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .announcing(title)
    }
}

/// The one answer the venue and liquor store need, shown large.
private struct AgeAnswer: View {
    let result: PhotoIdVerification

    var body: some View {
        if let value = result.valueOf(element: PhotoIdElement(namespace: PhotoID.shared.ISO_23220_2_NAMESPACE, identifier: "age_over_18")),
           case .text(let text) = onEnum(of: value) {
            let over = text.text == "Yes"
            HStack(spacing: 12) {
                Image(systemName: over ? "checkmark.circle.fill" : "xmark.octagon.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(over ? Brand.success : Brand.error)
                    .accessibilityHidden(true)
                Text(over ? "18 or over" : "Under 18")
                    .font(.largeTitle.bold())
            }
            .accessibilityElement(children: .combine)
        }
    }
}

private struct Faces: View {
    let portrait: KotlinByteArray?
    let passportCheck: PassportCheckResult

    var body: some View {
        SectionHeading("Faces")
        Text("Both should show the person in front of you. The chip photo comes from the passport data, checked against the passport issuer's signature.")
            .font(.subheadline)
        ViewThatFits {
            HStack(alignment: .top, spacing: 12) { blocks }
            VStack(spacing: 12) { blocks }
        }
    }

    @ViewBuilder private var blocks: some View {
        PhotoBlock(bytes: portrait, caption: "Photo ID portrait", description: "Photo ID portrait")
        PhotoBlock(bytes: passportCheck.faceImage, caption: "Passport chip photo (DG2)", description: "Passport chip photo, from DG2")
    }
}

private struct PhotoBlock: View {
    let bytes: KotlinByteArray?
    let caption: String
    let description: String

    var body: some View {
        VStack(spacing: 4) {
            if let bytes, let image = UIImage(data: bytes.toData()) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(description)
            } else {
                Text(bytes == nil ? "Not shared" : "This image format (probably JPEG 2000) can't be shown")
                    .font(.subheadline)
            }
            Text(caption).font(.caption.weight(.semibold))
        }
        .frame(maxWidth: .infinity)
    }
}

private struct Dg1Comparison: View {
    let passportCheck: PassportCheckResult

    var body: some View {
        if !passportCheck.comparisons.isEmpty {
            SectionHeading("Photo ID compared with the passport (DG1)")
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(passportCheck.comparisons.enumerated()), id: \.offset) { _, comparison in
                    let outcome: CheckOutcome = switch comparison.matches?.boolValue {
                    case true: .passed
                    case false: .failed
                    case nil: .unknown
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(comparison.label).font(.subheadline.weight(.semibold))
                            Spacer()
                            OutcomeBadge(outcome: outcome)
                        }
                        Text("Photo ID: \(comparison.credentialValue ?? "not shared")").font(.subheadline)
                        Text("Passport: \(comparison.passportValue)").font(.subheadline)
                    }
                    .padding(.vertical, 8)
                    .accessibilityElement(children: .combine)
                    Divider()
                }
            }
        }
    }
}

private struct SharedData: View {
    let result: PhotoIdVerification

    var body: some View {
        SectionHeading("Shared")
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(result.disclosed.enumerated()), id: \.offset) { _, claim in
                VStack(alignment: .leading, spacing: 2) {
                    Text(claim.displayName).font(.subheadline.weight(.semibold))
                    Text(text(claim.value))
                    if claim.intentToRetain {
                        Text("Kept on file").font(.caption.bold())
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
                .accessibilityElement(children: .combine)
                Divider()
            }
        }
    }

    private func text(_ value: ClaimValue) -> String {
        switch onEnum(of: value) {
        case .text(let text): text.text
        case .image: "Photo (shown above)"
        case .binary(let binary): "Signed data, \(binary.size) bytes"
        }
    }
}

private struct Dg1RevealsNote: View {
    let fields: [Dg1Field]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "info.circle.fill").accessibilityHidden(true)
                SectionHeading("DG1 reveals")
            }
            Text("DG1 can't be shared in part. Sharing it gave you all of this:").font(.subheadline)
            ForEach(Array(fields.enumerated()), id: \.offset) { _, field in
                Text("• \(field.label): \(field.value ?? "unreadable")").font(.subheadline)
            }
        }
        .foregroundStyle(Brand.onSecondaryContainer)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Brand.secondaryContainer, in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct NotShared: View {
    let result: PhotoIdVerification

    var body: some View {
        SectionHeading("Not shared")
        Text("Everything else on the Photo ID stayed on the holder's phone.").font(.subheadline)
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(result.notShared.enumerated()), id: \.offset) { _, element in
                Text(element.wasRequested ? "• \(element.displayName) (asked for, withheld)" : "• \(element.displayName)")
                    .font(.subheadline)
                    .foregroundStyle(Brand.neutral)
            }
        }
    }
}
