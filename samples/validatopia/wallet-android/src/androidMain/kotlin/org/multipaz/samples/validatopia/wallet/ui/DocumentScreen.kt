package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.WalletModel

/**
 * The presenting screen for one document, following the NZ DISTF "flash pass" guidance: it shows
 * how to share (tap a reader, or show a code), and a card showing only the holder's shortened
 * name. The holder's own details are a separate viewing screen, clearly marked as not for sharing.
 */
@Composable
fun DocumentScreen(
    model: WalletModel,
    documentId: String,
    onBack: () -> Unit,
    onShowQr: (documentId: String) -> Unit,
    onViewDetails: (documentId: String) -> Unit,
) {
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    val documentInfo = documentInfos.firstOrNull { it.document.identifier == documentId }
    val name = documentInfo?.document?.displayName ?: "Document"

    ValidatopiaScaffold(title = name, onBack = onBack) {
        if (documentInfo == null) {
            Text("This document is no longer in the wallet.", style = MaterialTheme.typography.bodyLarge)
            return@ValidatopiaScaffold
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
        ) {
            Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(
                text = "Tap reader or show code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Image(
                bitmap = documentInfo.cardArt,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = "Hold the back of your phone near the verifier's NFC reader (you don't need to open the " +
                "app first), or show them a code to scan. Either way you'll see what's being asked for before " +
                "anything is shared, and they check your document with their app. Showing your screen isn't proof.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = { onShowQr(documentId) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Show code")
        }
        OutlinedButton(onClick = { onViewDetails(documentId) }, modifier = Modifier.fillMaxWidth()) {
            Text("View my details")
        }
    }
}
