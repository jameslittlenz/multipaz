package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.multipaz.compose.document.DocumentInfo
import org.multipaz.samples.validatopia.shared.ui.PoweredByValid8
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.WalletModel

/**
 * The list of Photo IDs.
 *
 * This deliberately doesn't use `VerticalCardList`: it labels every card "Card Image" for
 * TalkBack and reorders only by dragging (WCAG 2.2 SC 2.5.7). Each card here is a single labelled
 * button instead.
 */
@Composable
fun HomeScreen(
    model: WalletModel,
    onAddPhotoId: () -> Unit,
    onOpenDocument: (documentId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    ValidatopiaScaffold(
        title = "Validatopia Wallet",
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) {
        if (documentInfos.isEmpty()) {
            Text(
                text = "You don't have a Photo ID yet.",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Get one from the Validatopia issuer to prove who you are or how old you are.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onAddPhotoId, modifier = Modifier.fillMaxWidth()) {
                Text("Get a Photo ID")
            }
        } else {
            for (documentInfo in documentInfos) {
                PhotoIdCard(documentInfo = documentInfo, onClick = { onOpenDocument(documentInfo.document.identifier) })
            }
            Text(
                text = "To share, open your Photo ID and show its QR code, or hold your phone near the " +
                    "verifier's NFC reader.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onAddPhotoId, modifier = Modifier.fillMaxWidth()) {
                Text("Get another Photo ID")
            }
        }
        PoweredByValid8(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

@Composable
private fun PhotoIdCard(documentInfo: DocumentInfo, onClick: () -> Unit) {
    val name = documentInfo.document.displayName ?: "Photo ID"
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = "Open") { onClick(); true }
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
            Image(
                bitmap = documentInfo.cardArt,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            documentInfo.document.typeDisplayName?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
