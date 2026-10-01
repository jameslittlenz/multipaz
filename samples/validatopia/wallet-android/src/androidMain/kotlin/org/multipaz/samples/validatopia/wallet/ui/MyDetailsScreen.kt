package org.multipaz.samples.validatopia.wallet.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.cbor.Bstr
import org.multipaz.claim.Claim
import org.multipaz.claim.MdocClaim
import org.multipaz.compose.document.DocumentInfo
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.revocation.RevocationCheckResult
import org.multipaz.revocation.RevocationCheckState
import org.multipaz.samples.validatopia.shared.branding.LocalValidatopiaStatusColors
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.shared.wallet.AgeOver
import org.multipaz.samples.validatopia.shared.wallet.CredentialStatus
import org.multipaz.samples.validatopia.shared.wallet.DetailsRow
import org.multipaz.samples.validatopia.wallet.R
import org.multipaz.samples.validatopia.wallet.WalletModel

/**
 * The holder's own view of one of their documents: a "viewing" display in the sense of the NZ DISTF
 * "flash pass" guidance, opened by tapping the document's card on the home screen. The warning that
 * this screen isn't for sharing stays on screen while the card art and details scroll under it.
 * Attributes are a plain list, with nothing (age, date of birth) made prominent. The portrait isn't
 * on this page; it opens separately on request.
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

    ValidatopiaScaffold(
        title = documentInfo?.document?.displayName ?: "My details",
        onBack = onBack,
        scrollable = false,
    ) {
        NotForSharingBanner()
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (documentInfo == null) {
                Text("This document is no longer in the wallet.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Image(
                bitmap = documentInfo.cardArt,
                // The art's text (type, subtitle, short name) is in the image, so it's described.
                contentDescription = "Card for ${documentInfo.document.displayName ?: "this document"}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    // A faint edge, so dark cards stand out from a dark background.
                    .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
            )
            Text(
                text = "For your own reference. To prove who you are or how old you are, use Share on the " +
                    "home screen, or tap a reader.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (claimsOf(documentInfo).any { it.isPortrait() }) {
                PortraitPlaceholder(onClick = { onViewPortrait(documentId) })
            }
            StatusChip(model, documentInfo)
            if (claimsOf(documentInfo).any { it.isPassportData() }) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    DtcBadge()
                }
            }
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

/**
 * The holder's portrait, alone, behind the same warning: the full width of the page, centred in the
 * space below the warning, with the card art's rounded corners.
 */
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Your portrait",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Text("No portrait to show.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The DISTF-style warning that a viewing screen can't be relied on. Always visible, never colour alone. */
@Composable
private fun ColumnScope.NotForSharingBanner() {
    val colors = LocalValidatopiaStatusColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.warningContainer)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.onWarningContainer)
        Text(
            text = "Do not show this screen. This screen is just for you.",
            color = colors.onWarningContainer,
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

/**
 * Whether the credential can be used: its dates, and the issuer's revocation list, checked afresh
 * each time the page opens. An icon plus words, never colour alone: revoked in red; suspended,
 * expired or not yet valid in yellow; valid in green. Until the check answers, or if it can't, only
 * the dates count, with a line saying so.
 */
@Composable
private fun StatusChip(model: WalletModel, documentInfo: DocumentInfo) {
    val credential = documentInfo.credentialInfos.firstOrNull()?.credential ?: return
    val revocation by produceState<RevocationCheckResult?>(initialValue = null, credential.identifier) {
        value = model.credentialStatusChecker.check(credential)
    }
    val colors = LocalValidatopiaStatusColors.current
    val timeZone = TimeZone.currentSystemDefault()
    val from = credential.validFrom.toLocalDateTime(timeZone).date
    val until = credential.validUntil.toLocalDateTime(timeZone).date
    val status = CredentialStatus.of(credential.validFrom, credential.validUntil, revocation?.state)
    val (text, color) = when (status) {
        CredentialStatus.VALID -> "Valid until $until" to colors.success
        CredentialStatus.NOT_YET_VALID -> "Not valid until $from" to colors.warning
        CredentialStatus.EXPIRED -> "Expired on $until" to colors.warning
        CredentialStatus.SUSPENDED -> "Suspended" to colors.warning
        CredentialStatus.REVOKED -> "Revoked" to colors.error
    }
    val icon = when (status) {
        CredentialStatus.VALID -> Icons.Filled.CheckCircle
        CredentialStatus.REVOKED -> Icons.Filled.Error
        else -> Icons.Filled.Warning
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, color),
            modifier = Modifier
                .widthIn(min = 48.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = color)
                Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
        val note = when (revocation?.state) {
            null -> "Checking with the issuer…"
            RevocationCheckState.UNKNOWN -> "Couldn't check with the issuer, so this is from the dates only."
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsList(documentInfo: DocumentInfo) {
    val claims = claimsOf(documentInfo)
    val rest = claims.filterNot { it.isPassportData() }
    val timeZone = TimeZone.currentSystemDefault()
    for (row in DetailsRow.of(rest.filterNot { it.isPortrait() })) {
        Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            when (row) {
                is DetailsRow.Single -> {
                    val claim = row.claim
                    Text(claim.displayName, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = claim.render(timeZone),
                        style = MaterialTheme.typography.bodyLarge,
                        // Monospaced, so the MRZ's fixed-width lines line up as on a passport.
                        fontFamily = if (claim.isMrz()) FontFamily.Monospace else null,
                    )
                }
                is DetailsRow.AgeOverGroup -> {
                    Text("Age", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        for (ageOver in row.ages) {
                            AgeOverBadge(ageOver)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * Marks a Photo ID that carries the signed passport data it was issued from (SOD, DG1 and DG2),
 * making it an ICAO Digital Travel Credential of type 1. Styled like the status chip above it.
 */
@Composable
private fun DtcBadge() {
    val color = LocalValidatopiaStatusColors.current.success
    Surface(
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.epassport),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(width = 28.dp, height = 16.dp),
            )
            Text(
                text = "DTC Compliant (Type 1)",
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** "✓ Over 18" in green or "✗ Over 65" in red: an icon and words, with colour only as reinforcement. */
@Composable
private fun AgeOverBadge(ageOver: AgeOver) {
    val colors = LocalValidatopiaStatusColors.current
    val color = if (ageOver.isOver) colors.success else colors.error
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color, shape)
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "Over ${ageOver.age}: ${if (ageOver.isOver) "yes" else "no"}"
            },
    ) {
        Icon(
            imageVector = if (ageOver.isOver) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Over ${ageOver.age}",
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun claimsOf(documentInfo: DocumentInfo): List<Claim> =
    documentInfo.credentialInfos.firstOrNull()?.claims.orEmpty()

/** The passport's signed data (SOD, DG1, DG2), which only a Photo ID issued from a passport carries. */
private fun Claim.isPassportData(): Boolean = this is MdocClaim && namespaceName == PhotoID.DATAGROUPS_NAMESPACE

private fun Claim.isPortrait(): Boolean = this is MdocClaim && dataElementName == "portrait"

/** The Photo ID's machine-readable zone, copied from the passport. */
private fun Claim.isMrz(): Boolean = this is MdocClaim && dataElementName == "travel_document_mrz"
