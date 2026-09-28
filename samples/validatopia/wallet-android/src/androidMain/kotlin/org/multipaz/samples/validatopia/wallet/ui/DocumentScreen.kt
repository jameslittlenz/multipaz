package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.claim.MdocClaim
import org.multipaz.compose.claim.RenderClaimValue
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.samples.validatopia.shared.ui.SectionHeading
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.WalletModel

/** One Photo ID: its card, how to share it, what it contains, and deletion. */
@Composable
fun DocumentScreen(
    model: WalletModel,
    documentId: String,
    onBack: () -> Unit,
    onShowQr: (documentId: String) -> Unit,
    onDeleted: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    val documentInfo = documentInfos.firstOrNull { it.document.identifier == documentId }
    var confirmDelete by remember { mutableStateOf(false) }

    ValidatopiaScaffold(title = documentInfo?.document?.displayName ?: "Photo ID", onBack = onBack) {
        if (documentInfo == null) {
            Text("This Photo ID is no longer in the wallet.", style = MaterialTheme.typography.bodyLarge)
            return@ValidatopiaScaffold
        }
        Image(
            bitmap = documentInfo.cardArt,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        )

        SectionHeading("Share")
        Button(onClick = { onShowQr(documentId) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Show QR code")
        }
        Text(
            text = "Or hold the back of your phone near the verifier's NFC reader. You don't need to open " +
                "the app first. Either way, you'll see what's being asked for before anything is shared.",
            style = MaterialTheme.typography.bodyLarge,
        )

        SectionHeading("What's on this Photo ID")
        val claims = documentInfo.credentialInfos.firstOrNull()?.claims.orEmpty()
        val (passportData, visible) = claims.partition {
            it is MdocClaim && it.namespaceName == PhotoID.DATAGROUPS_NAMESPACE
        }
        for (claim in visible) {
            Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(claim.displayName, style = MaterialTheme.typography.labelLarge)
                RenderClaimValue(claim = claim)
            }
            HorizontalDivider()
        }
        if (passportData.isNotEmpty()) {
            Text(
                text = "It also carries the signed passport data it was issued from (SOD, DG1 and DG2). " +
                    "Verifiers only get it if they ask and you agree, typically at a border. Sharing DG1 " +
                    "reveals your full name, date of birth, sex, nationality, passport number and expiry " +
                    "together.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedButton(
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Remove from this phone")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this Photo ID?") },
            text = { Text("It's deleted from this phone. You can get a new one from the issuer at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    coroutineScope.launch {
                        model.documentStore.deleteDocument(documentId)
                        onDeleted()
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}
