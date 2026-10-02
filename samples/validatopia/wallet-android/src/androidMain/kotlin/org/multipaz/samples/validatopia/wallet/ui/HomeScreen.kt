package org.multipaz.samples.validatopia.wallet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaCardArt
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaTheme
import org.multipaz.samples.validatopia.shared.ui.PoweredByValid8
import org.multipaz.samples.validatopia.shared.ui.ValidatopiaScaffold
import org.multipaz.samples.validatopia.shared.wallet.ValidatopiaIssuance
import org.multipaz.samples.validatopia.wallet.DocumentCardArt
import org.multipaz.samples.validatopia.wallet.R
import org.multipaz.samples.validatopia.wallet.WalletModel

/**
 * The wallet's documents: Photo IDs and the Driver Licences, Gym Memberships and Age Verifications
 * issued with them, stacked under a button to share them by QR code or NFC.
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
    onShare: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val documentInfos by model.documentModel.documentInfos.collectAsState()
    val issuanceState by model.issuance.state.collectAsState()
    HomeContent(
        cards = documentInfos.map { HomeCard(it.document.identifier, it.document.displayName, it.cardArt) },
        issuanceState = issuanceState,
        onAddPhotoId = onAddPhotoId,
        onOpenDocument = onOpenDocument,
        onShare = onShare,
        onOpenSettings = onOpenSettings,
        onDismissIssuanceFailure = model.issuance::dismissFailure,
    )
}

/** A document as the home screen shows it. */
private data class HomeCard(val documentId: String, val displayName: String?, val cardArt: ImageBitmap)

/** [HomeScreen]'s layout, apart from the wallet model so it can be previewed. */
@Composable
private fun HomeContent(
    cards: List<HomeCard>,
    issuanceState: ValidatopiaIssuance.State,
    onAddPhotoId: () -> Unit,
    onOpenDocument: (documentId: String) -> Unit,
    onShare: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissIssuanceFailure: () -> Unit,
) {
    ValidatopiaScaffold(
        title = "Validatopia Wallet",
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) {
        if (cards.isEmpty()) {
            Text(
                text = "You don't have any documents yet.",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Get a Photo ID from the Validatopia issuer to prove who you are or how old you are. It " +
                    "comes with a Driver Licence, Gym Membership and Age Verification.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onAddPhotoId, modifier = Modifier.fillMaxWidth()) {
                Text("Add a Credential")
            }
        } else {
            ShareButton(onClick = onShare)
            DocumentCardStack(cards = cards, onOpenDocument = onOpenDocument)
            IssuanceStatus(issuanceState, onDismiss = onDismissIssuanceFailure)
            OutlinedButton(onClick = onAddPhotoId, modifier = Modifier.fillMaxWidth()) {
                Text("Add a Credential")
            }
        }
        PoweredByValid8(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/**
 * The large button to the share screen, its QR code, contactless and online icons above the label. Navy on
 * white, outlined, so it doesn't read as another card in the stack below it.
 */
@Composable
private fun ShareButton(onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colorScheme.surface,
            contentColor = colorScheme.onSurface,
        ),
        border = BorderStroke(2.dp, colorScheme.onSurface),
        contentPadding = PaddingValues(vertical = 20.dp),
        // White space above and below, setting it apart from the title and the card stack.
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(40.dp))
                Icon(painterResource(R.drawable.contactless_24), contentDescription = null, modifier = Modifier.size(40.dp))
                // Online presentation, to a website or app.
                Icon(painterResource(R.drawable.computer_24), contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Text("Share", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Progress, or a failure, in issuing the documents that come with a Photo ID. */
@Composable
private fun IssuanceStatus(state: ValidatopiaIssuance.State, onDismiss: () -> Unit) {
    when (state) {
        ValidatopiaIssuance.State.Idle -> {}
        is ValidatopiaIssuance.State.Issuing -> ProgressRow(
            if (state.remaining == 1) "Adding 1 more document…" else "Adding ${state.remaining} more documents…"
        )
        is ValidatopiaIssuance.State.Failed -> {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** The fraction of each card's height left showing in the stack: its title and "Validatopia". */
private const val CARD_PEEK_FRACTION = 0.32f

/**
 * The cards stacked top to bottom, each overlapping the one before it so only the top of the art
 * (the document type and "Validatopia") shows, apart from the last card, which shows in full.
 * Later cards are placed, and so drawn and hit-tested, over earlier ones.
 */
@Composable
private fun DocumentCardStack(cards: List<HomeCard>, onOpenDocument: (documentId: String) -> Unit) {
    Layout(
        content = {
            for (card in cards) {
                DocumentCard(card, onClick = { onOpenDocument(card.documentId) })
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val offsets = placeables.runningFold(0) { y, placeable ->
            y + (placeable.height * CARD_PEEK_FRACTION).roundToInt()
        }
        val height = placeables.lastOrNull()?.let { offsets[placeables.lastIndex] + it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(0, offsets[index]) }
        }
    }
}

/** A card in the stack: the art alone, as one labelled button. */
@Composable
private fun DocumentCard(card: HomeCard, onClick: () -> Unit) {
    val name = card.displayName ?: "Document"
    val shape = RoundedCornerShape(16.dp)
    Image(
        bitmap = card.cardArt,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 4.dp, shape = shape)
            .clip(shape)
            // A faint edge, so dark cards stand out from a dark background.
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f), shape)
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
    )
}

@Preview(name = "Home, with documents", showBackground = true, heightDp = 1000)
@Composable
private fun HomeContentPreview() {
    val cardArt = DocumentCardArt(LocalContext.current)
    val cards = remember {
        listOf(
            "Claudia's Photo ID" to ValidatopiaCardArt.photoId,
            "Claudia's Driver Licence" to ValidatopiaCardArt.driverLicence,
            "Claudia's Gym Membership" to ValidatopiaCardArt.gymMembership,
            "Claudia's Age Verification" to ValidatopiaCardArt.ageVerification,
        ).mapIndexed { index, (name, style) ->
            HomeCard("preview-$index", name, cardArt.image(style, "Claudia H."))
        }
    }
    ValidatopiaTheme(darkTheme = false) {
        HomeContent(
            cards = cards,
            issuanceState = ValidatopiaIssuance.State.Idle,
            onAddPhotoId = {},
            onOpenDocument = {},
            onShare = {},
            onOpenSettings = {},
            onDismissIssuanceFailure = {},
        )
    }
}

@Preview(name = "Home, empty", showBackground = true)
@Composable
private fun HomeContentEmptyPreview() {
    ValidatopiaTheme(darkTheme = false) {
        HomeContent(
            cards = emptyList(),
            issuanceState = ValidatopiaIssuance.State.Idle,
            onAddPhotoId = {},
            onOpenDocument = {},
            onShare = {},
            onOpenSettings = {},
            onDismissIssuanceFailure = {},
        )
    }
}
