package org.multipaz.samples.validatopia.wallet.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.cbor.Bstr
import org.multipaz.claim.Claim
import org.multipaz.claim.MdocClaim
import org.multipaz.compose.document.DocumentInfo
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.samples.validatopia.shared.branding.LocalValidatopiaStatusColors
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.wallet.WalletModel
import kotlin.time.Clock

/**
 * The holder's own view of one of their documents: a "viewing" display in the sense of the NZ DISTF
 * "flash pass" guidance. The warning that this screen isn't for sharing stays on screen while the
 * details scroll. Attributes are a plain list, with nothing (age, date of birth) made prominent
 * and no document styling. The portrait isn't on this page; it opens separately on request.
 */
@Composable
fun MyDetailsScreen(
    model: WalletModel,
    documentId: String,
    onBack: () -> Unit,
    onViewPortrait: (documentId: String) -> Unit,
    onDeleted: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    val documentInfo = documentInfos.firstOrNull { it.document.identifier == documentId }
    var confirmDelete by remember { mutableStateOf(false) }

    ValidatopiaScaffold(title = "My details", onBack = onBack, scrollable = false) {
        NotForSharingBanner()
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (documentInfo == null) {
                Text("This document is no longer in the wallet.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text(
                text = "For your own reference. To prove who you are or how old you are, share the document " +
                    "by tapping a reader or showing a code.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (claimsOf(documentInfo).any { it.isPortrait() }) {
                PortraitPlaceholder(onClick = { onViewPortrait(documentId) })
            }
            StatusChip(documentInfo)
            DetailsList(documentInfo)
            OutlinedButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove from this phone")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this document?") },
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

/** The holder's portrait, alone, behind the same warning. */
@Composable
fun PortraitScreen(model: WalletModel, documentId: String, onBack: () -> Unit) {
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    val portrait = documentInfos.firstOrNull { it.document.identifier == documentId }
        ?.let { claimsOf(it) }
        ?.firstOrNull { it.isPortrait() }
        ?.let { ((it as MdocClaim).value as? Bstr)?.value }
    val image = remember(portrait) { portrait?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    ValidatopiaScaffold(title = "My portrait", onBack = onBack, scrollable = false) {
        NotForSharingBanner()
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Your portrait",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Text("No portrait to show.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The DISTF-style warning that a viewing screen can't be relied on. Always visible, never colour alone. */
@Composable
private fun ColumnScope.NotForSharingBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
        Text(
            text = "Do not share this screen. It isn't verified, and the information on it can't be relied on.",
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PortraitPlaceholder(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "View portrait", onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
        }
        Text("Tap to view portrait", style = MaterialTheme.typography.labelLarge)
    }
}

/** Whether the credential is currently valid, as an icon plus words. */
@Composable
private fun StatusChip(documentInfo: DocumentInfo) {
    val credential = documentInfo.credentialInfos.firstOrNull()?.credential ?: return
    val colors = LocalValidatopiaStatusColors.current
    val now = Clock.System.now()
    val until = credential.validUntil.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val (valid, text) = when {
        now < credential.validFrom -> false to "Not yet valid"
        now > credential.validUntil -> false to "Expired on $until"
        else -> true to "Valid until $until"
    }
    val color = if (valid) colors.success else colors.error
    Surface(
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color),
        modifier = Modifier.widthIn(min = 48.dp).semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(if (valid) Icons.Filled.CheckCircle else Icons.Filled.Error, contentDescription = null, tint = color)
            Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DetailsList(documentInfo: DocumentInfo) {
    val claims = claimsOf(documentInfo)
    val (passportData, rest) = claims.partition {
        it is MdocClaim && it.namespaceName == PhotoID.DATAGROUPS_NAMESPACE
    }
    val timeZone = TimeZone.currentSystemDefault()
    for (claim in rest.filterNot { it.isPortrait() }) {
        Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(claim.displayName, style = MaterialTheme.typography.labelLarge)
            Text(claim.render(timeZone), style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
    }
    if (passportData.isNotEmpty()) {
        Text(
            text = "Your Photo ID also carries the signed passport data it was issued from (SOD, DG1 and DG2). " +
                "Verifiers only get it if they ask and you agree, typically at a border. Sharing DG1 reveals " +
                "your full name, date of birth, sex, nationality, passport number and expiry together.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun claimsOf(documentInfo: DocumentInfo): List<Claim> =
    documentInfo.credentialInfos.firstOrNull()?.claims.orEmpty()

private fun Claim.isPortrait(): Boolean = this is MdocClaim && dataElementName == "portrait"
