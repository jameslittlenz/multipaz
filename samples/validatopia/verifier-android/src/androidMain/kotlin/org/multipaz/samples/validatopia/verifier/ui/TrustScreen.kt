package org.multipaz.samples.validatopia.verifier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X509Cert
import org.multipaz.samples.validatopia.shared.result.CheckOutcome
import org.multipaz.samples.validatopia.shared.trust.ValidatopiaTrust
import org.multipaz.samples.validatopia.shared.ui.PoweredByValid8
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.verifier.BuildConfig

/** The bundled trust anchors, all TEST. */
@Composable
fun TrustScreen(onBack: () -> Unit) {
    ValidatopiaScaffold(title = "Trusted issuers", onBack = onBack) {
        Text(
            text = "This app trusts only the Validatopia test anchors below. They are shared by every " +
                "Validatopia deployment and marked TEST: a credential that chains to them proves it came from " +
                "the Validatopia demo, not that a real person was identified.",
            style = MaterialTheme.typography.bodyLarge,
        )
        AnchorCard("Photo ID issuer (IACA)", ValidatopiaTrust.iacaCertificate)
        AnchorCard("Passport issuer (CSCA)", ValidatopiaTrust.testCscaCertificate)
        Text(
            text = "Importing ICAO master lists (real countries' CSCAs) isn't supported in this version.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionHeading("This verifier's identity")
        Text(
            text = "Requests are signed as \"${ValidatopiaTrust.VERIFIER_DISPLAY_NAME}\", so the holder's wallet " +
                "can show who is asking. The key is a TEST key shipped in the app.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionHeading("About")
        Text("Validatopia Verify ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        PoweredByValid8(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AnchorCard(title: String, certificate: X509Cert) {
    val fingerprint by produceState("…", certificate) {
        value = Crypto.digest(Algorithm.SHA256, certificate.encoded.toByteArray())
            .joinToString(":") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() }
    }
    val validUntil = certificate.validityNotAfter.toLocalDateTime(TimeZone.currentSystemDefault()).date
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeading(title)
            OutcomeBadge(CheckOutcome.WARNING)
            Text(certificate.subject.name, style = MaterialTheme.typography.bodyMedium)
            Text("Valid until $validUntil", style = MaterialTheme.typography.bodyMedium)
            Text("SHA-256 $fingerprint", style = MaterialTheme.typography.bodySmall)
        }
    }
}
