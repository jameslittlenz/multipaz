import SwiftUI

/// The bundled trust anchors, all TEST.
struct TrustView: View {
    var body: some View {
        ValidatopiaScreen {
            Text("This app trusts only the Validatopia test anchors below. They are shared by every Validatopia deployment and marked TEST: a credential that chains to them proves it came from the Validatopia demo, not that a real person was identified.")
            AnchorCard(title: "Photo ID issuer (IACA)", certificate: ValidatopiaTrust.shared.iacaCertificate)
            AnchorCard(title: "Passport issuer (CSCA)", certificate: ValidatopiaTrust.shared.testCscaCertificate)
            Text("Importing ICAO master lists (real countries' CSCAs) isn't supported in this version.")
                .font(.subheadline)

            SectionHeading("This verifier's identity")
            Text("Requests are signed as \"\(ValidatopiaTrust.shared.VERIFIER_DISPLAY_NAME)\", so the holder's wallet can show who is asking. The key is a TEST key shipped in the app.")
                .font(.subheadline)

            SectionHeading("About")
            let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
            Text("Validatopia Verify \(version)")
            PoweredByValid8()
        }
        .navigationTitle("Trusted issuers")
    }
}

private struct AnchorCard: View {
    let title: String
    let certificate: X509Cert
    @State private var fingerprint = "…"

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionHeading(title)
            OutcomeBadge(outcome: .warning)
            Text(certificate.subject.name).font(.subheadline)
            Text("Valid until \(Date(kotlinInstant: certificate.validityNotAfter).formatted(date: .long, time: .omitted))")
                .font(.subheadline)
            Text("SHA-256 \(fingerprint)").font(.footnote).textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Brand.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .task {
            let encoded = certificate.encoded.toByteArray(startIndex: 0, endIndex: certificate.encoded.size)
            if let digest = try? await Crypto.shared.digest(algorithm: .sha256, message: encoded) {
                fingerprint = digest.toData().map { String(format: "%02X", $0) }.joined(separator: ":")
            }
        }
    }
}

extension Date {
    init(kotlinInstant: KotlinInstant) {
        self.init(timeIntervalSince1970: TimeInterval(kotlinInstant.toEpochMilliseconds()) / 1000)
    }
}
