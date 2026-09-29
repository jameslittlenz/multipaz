package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaBrandHeader
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold

/**
 * First-run welcome and consent. It covers what the persona (test identity) path actually does.
 * The passport path's own consent, for biometric processing and the server-side face match, comes
 * with that path (milestone M6).
 */
@Composable
fun WelcomeScreen(onAccept: () -> Unit) {
    ValidatopiaScaffold(title = "Welcome") {
        ValidatopiaBrandHeader(appName = "Wallet")
        Text(
            text = "Validatopia Wallet is a demonstration wallet for the fictional country of Validatopia. " +
                "It holds a Validatopia Photo ID, a digital identity document you can show to shops, hotels " +
                "and border officers, along with the Driver Licence, Gym Membership and Age Verification " +
                "issued with it.",
            style = MaterialTheme.typography.bodyLarge,
        )

        SectionHeading("Test identities only")
        Text(
            text = "In this version you get a Photo ID for a test identity chosen from a list. No real person's " +
                "data is used, and your camera, face and passport aren't involved.",
            style = MaterialTheme.typography.bodyLarge,
        )

        SectionHeading("What the issuer keeps")
        Text(
            text = "To create the Photo ID, the Validatopia issuer builds synthetic passport data for the test " +
                "identity and keeps an encrypted copy so it can renew your Photo ID. It keeps the copy for a " +
                "limited time (30 days unless the issuer's administrator changes it) and deletes it if the " +
                "Photo ID is revoked. It also logs each issuance (the time, the nationality and a partly " +
                "hidden document number), never the photo.",
            style = MaterialTheme.typography.bodyLarge,
        )

        SectionHeading("Sharing is always your choice")
        Text(
            text = "Nothing leaves your phone unless you agree. Each time a verifier asks, you'll see who is " +
                "asking, what they want and whether they intend to keep it, before you decide.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text("Agree and continue")
        }
    }
}
