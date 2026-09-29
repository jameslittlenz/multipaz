import SwiftUI

/// First-run welcome and consent, covering what the test-identity path actually does. The passport
/// path's own consent, for biometric processing and the server-side face match, comes with that path
/// (milestone M6). Same wording as the Android wallet.
struct WelcomeView: View {
    let onAccept: () -> Void

    var body: some View {
        ValidatopiaScreen {
            ValidatopiaBrandHeader(appName: "Wallet")
            Text("Validatopia Wallet is a demonstration wallet for the fictional country of Validatopia. It holds a Validatopia Photo ID, a digital identity document you can show to shops, hotels and border officers, along with the Driver Licence, Gym Membership and Age Verification issued with it.")

            SectionHeading("Test identities only")
            Text("In this version you get a Photo ID for a test identity chosen from a list. No real person's data is used, and your camera, face and passport aren't involved.")

            SectionHeading("What the issuer keeps")
            Text("To create the Photo ID, the Validatopia issuer builds synthetic passport data for the test identity and keeps an encrypted copy so it can renew your Photo ID. It keeps the copy for a limited time (30 days unless the issuer's administrator changes it) and deletes it if the Photo ID is revoked. It also logs each issuance (the time, the nationality and a partly hidden document number), never the photo.")

            SectionHeading("Sharing is always your choice")
            Text("Nothing leaves your phone unless you agree. Each time a verifier asks, you'll see who is asking, what they want and whether they intend to keep it, before you decide.")

            PrimaryButton(title: "Agree and continue", action: onAccept)
        }
        .navigationTitle("Welcome")
    }
}
