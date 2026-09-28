package org.multipaz.samples.validatopia.verifier.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.samples.validatopia.shared.branding.LocalValidatopiaStatusColors
import org.multipaz.samples.validatopia.shared.crossborder.PassportCheckResult
import org.multipaz.samples.validatopia.shared.result.CheckOutcome
import org.multipaz.samples.validatopia.shared.result.ClaimValue
import org.multipaz.samples.validatopia.shared.result.Dg1Field
import org.multipaz.samples.validatopia.shared.result.PhotoIdVerification
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdElement

private val PORTRAIT = PhotoIdElement(PhotoID.ISO_23220_2_NAMESPACE, "portrait")
private val AGE_OVER_18 = PhotoIdElement(PhotoID.ISO_23220_2_NAMESPACE, "age_over_18")

/** What was shared, how far it can be trusted, and what wasn't shared. */
@Composable
fun ResultScreen(
    result: PhotoIdVerification,
    onCheckAnother: () -> Unit,
    onDone: () -> Unit,
) {
    ValidatopiaScaffold(title = result.useCase.title, onBack = onDone) {
        Verdict(result)
        AgeAnswer(result)

        val portrait = (result.valueOf(PORTRAIT) as? ClaimValue.Image)?.bytes
        val passportCheck = result.passportCheck
        if (passportCheck != null) {
            Faces(portrait = portrait, passportCheck = passportCheck)
        } else if (portrait != null) {
            PhotoBlock(
                bytes = portrait,
                caption = "Photo ID portrait",
                description = "Photo ID portrait. Compare it with the person in front of you.",
                modifier = Modifier.widthIn(max = 280.dp).align(Alignment.CenterHorizontally),
            )
        }

        TrustPanelCard(result.credentialIssuer)
        result.passportIssuer?.let { TrustPanelCard(it) }
        passportCheck?.let { Dg1Comparison(it) }

        SharedData(result)
        result.dg1Reveals?.let { Dg1RevealsNote(it) }
        NotShared(result)

        Button(onClick = onCheckAnother, modifier = Modifier.fillMaxWidth()) { Text("Check another person") }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun Verdict(result: PhotoIdVerification) {
    val outcomes = listOfNotNull(result.credentialIssuer.overall, result.passportIssuer?.overall)
    val overall = when {
        CheckOutcome.FAILED in outcomes -> CheckOutcome.FAILED
        CheckOutcome.UNKNOWN in outcomes -> CheckOutcome.UNKNOWN
        CheckOutcome.WARNING in outcomes -> CheckOutcome.WARNING
        else -> CheckOutcome.PASSED
    }
    val (title, detail) = when (overall) {
        CheckOutcome.PASSED -> "Verified" to "Every check passed."
        CheckOutcome.WARNING -> "Verified against TEST issuers" to
            "Every check passed, but only against Validatopia's test trust anchors. Fine for a demo; not " +
            "proof of a real identity."
        CheckOutcome.UNKNOWN -> "Verified, with checks outstanding" to
            "The data is authentic, but some checks couldn't run. See below."
        CheckOutcome.FAILED -> "Don't rely on this" to "At least one check failed. See below."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutcomeBadge(overall)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The one answer the venue and liquor store need, shown large. */
@Composable
private fun AgeAnswer(result: PhotoIdVerification) {
    val value = (result.valueOf(AGE_OVER_18) as? ClaimValue.Text)?.text ?: return
    val colors = LocalValidatopiaStatusColors.current
    val over = value == "Yes"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Icon(
            imageVector = if (over) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (over) colors.success else colors.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = if (over) "18 or over" else "Under 18",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Faces(portrait: ByteArray?, passportCheck: PassportCheckResult) {
    SectionHeading("Faces")
    Text(
        text = "Both should show the person in front of you. The chip photo comes from the passport data, " +
            "checked against the passport issuer's signature.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        PhotoBlock(
            bytes = portrait,
            caption = "Photo ID portrait",
            description = "Photo ID portrait",
            modifier = Modifier.weight(1f),
        )
        PhotoBlock(
            bytes = passportCheck.faceImage,
            caption = "Passport chip photo (DG2)",
            description = "Passport chip photo, from DG2",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PhotoBlock(bytes: ByteArray?, caption: String, description: String, modifier: Modifier = Modifier) {
    val image = remember(bytes) { bytes?.let { decode(it) } }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Text(
                text = if (bytes == null) "Not shared" else "This image format (probably JPEG 2000) can't be shown",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(caption, style = MaterialTheme.typography.labelLarge)
    }
}

private fun decode(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

@Composable
private fun Dg1Comparison(passportCheck: PassportCheckResult) {
    if (passportCheck.comparisons.isEmpty()) {
        return
    }
    SectionHeading("Photo ID compared with the passport (DG1)")
    for (comparison in passportCheck.comparisons) {
        val outcome = when (comparison.matches) {
            true -> CheckOutcome.PASSED
            false -> CheckOutcome.FAILED
            null -> CheckOutcome.UNKNOWN
        }
        Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comparison.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutcomeBadge(outcome)
            }
            Text("Photo ID: ${comparison.credentialValue ?: "not shared"}", style = MaterialTheme.typography.bodyMedium)
            Text("Passport: ${comparison.passportValue}", style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
    }
}

@Composable
private fun SharedData(result: PhotoIdVerification) {
    SectionHeading("Shared")
    for (claim in result.disclosed) {
        Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(claim.displayName, style = MaterialTheme.typography.labelLarge)
            val text = when (val value = claim.value) {
                is ClaimValue.Text -> value.text
                is ClaimValue.Image -> "Photo (shown above)"
                is ClaimValue.Binary -> "Signed data, ${value.size} bytes"
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (claim.intentToRetain) {
                Text("Kept on file", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun Dg1RevealsNote(fields: List<Dg1Field>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null)
                SectionHeading("DG1 reveals")
            }
            Text(
                text = "DG1 can't be shared in part. Sharing it gave you all of this:",
                style = MaterialTheme.typography.bodyMedium,
            )
            for (field in fields) {
                Text("• ${field.label}: ${field.value ?: "unreadable"}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NotShared(result: PhotoIdVerification) {
    val neutral = LocalValidatopiaStatusColors.current.neutral
    SectionHeading("Not shared")
    Text(
        text = "Everything else on the Photo ID stayed on the holder's phone.",
        style = MaterialTheme.typography.bodyMedium,
    )
    for (element in result.notShared) {
        Text(
            text = if (element.wasRequested) "• ${element.displayName} (asked for, withheld)" else "• ${element.displayName}",
            color = neutral,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
