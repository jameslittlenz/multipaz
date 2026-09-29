import SwiftUI

extension CheckOutcome {
    /// Always an icon and a word; colour only reinforces them.
    var style: (systemImage: String, word: String, color: Color) {
        switch self {
        case .passed: ("checkmark.circle.fill", "Passed", Brand.success)
        case .warning: ("exclamationmark.triangle.fill", "Test only", Brand.warning)
        case .failed: ("xmark.octagon.fill", "Failed", Brand.error)
        case .unknown: ("questionmark.circle.fill", "Unknown", Brand.neutral)
        }
    }
}

/// An outcome badge: icon plus word, e.g. "✓ Passed".
struct OutcomeBadge: View {
    let outcome: CheckOutcome

    var body: some View {
        let style = outcome.style
        Label(style.word, systemImage: style.systemImage)
            .font(.subheadline.bold())
            .foregroundStyle(style.color)
            .fixedSize()
    }
}

/// A trust panel ("Credential issuer" or "Passport issuer (CSCA)") with one badge per check.
struct TrustPanelCard: View {
    let panel: TrustPanel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SectionHeading(panel.title)
                OutcomeBadge(outcome: panel.overall)
            }
            ForEach(Array(panel.checks.enumerated()), id: \.offset) { index, check in
                if index > 0 {
                    Divider()
                }
                // Read as one item by VoiceOver: "Issuer, Test only, Validatopia Photo ID issuer (TEST…)".
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(check.label).font(.subheadline.weight(.semibold))
                        Spacer()
                        OutcomeBadge(outcome: check.outcome)
                    }
                    Text(check.detail).font(.subheadline)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(16)
        .background(Brand.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
    }
}
