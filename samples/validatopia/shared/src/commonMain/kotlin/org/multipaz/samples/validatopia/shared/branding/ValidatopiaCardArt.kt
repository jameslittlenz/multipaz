package org.multipaz.samples.validatopia.shared.branding

import org.multipaz.cbor.Tstr
import org.multipaz.document.Document
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.utopia.knowntypes.Loyalty

/**
 * The colors, title and subtitle of one document's card art.
 *
 * @property titleLead the start of the title, in regular weight (including any trailing space).
 * @property titleEmphasis the rest of the title, in bold.
 * @property background the card's background color.
 * @property hills the color of the three hills; the nearest is opaque, the others tints of it.
 * @property title the title's color.
 * @property subtitle the color of the subtitle line under the title.
 * @property subtitleText the subtitle line: who the document is from, or what it's for.
 */
data class CardArtStyle(
    val titleLead: String,
    val titleEmphasis: String,
    val background: String,
    val hills: String,
    val title: String,
    val subtitle: String,
    val subtitleText: String,
)

/**
 * Card art for the documents Validatopia Wallet holds, drawn on the device (the issuer supplies
 * none) by `DocumentCardArt` on Android and iOS from these styles.
 *
 * Every document shares the Photo ID's design: a dark background with three see-through rolling
 * hills, the title naming the type, a subtitle under it and a "Powered by" credit on the
 * nearest hill. Each type has its own pairing of navy, teal and green so it can be told apart at
 * a glance; the title always names the type, so color is never the only cue.
 *
 * At the bottom left the card shows the holder's shortened name ("Claudia H."), from
 * [holderShortName]. This is a deliberate departure from the NZ DISTF "flash pass" guidance
 * (https://github.com/nz-trust-framework/DISTF-reference-architecture/blob/main/guidance/FLASH-PASS.md),
 * which asks for no identifying information on a card that appears on presenting screens and
 * consent sheets. Documents that carry no name, like the Age Verification, show none.
 */
object ValidatopiaCardArt {
    private const val WHITE = "#FFFFFF"

    /** Hill opacities, far to near. */
    val hillAlphas: List<Double> = listOf(0.22, 0.40, 0.70)

    /** The "Powered by" credit and Valid8 logo are white, on the nearest hill. */
    const val CREDIT = WHITE

    val photoId = CardArtStyle(
        titleLead = "Photo ",
        titleEmphasis = "ID",
        background = ValidatopiaColors.NAVY,
        hills = ValidatopiaColors.GREEN,
        title = WHITE,
        subtitle = WHITE,
        subtitleText = "DTC Compliant",
    )

    // Subtitles are white: on the teal backgrounds the hill colors are too faint (under 4.5:1).
    val driverLicence = CardArtStyle(
        titleLead = "Driver ",
        titleEmphasis = "Licence",
        background = ValidatopiaColors.TEAL,
        hills = ValidatopiaColors.NAVY,
        title = WHITE,
        subtitle = WHITE,
        subtitleText = "Validatopia DMV",
    )

    val gymMembership = CardArtStyle(
        titleLead = "Gym ",
        titleEmphasis = "Membership",
        background = ValidatopiaColors.NAVY,
        hills = ValidatopiaColors.TEAL,
        title = WHITE,
        subtitle = WHITE,
        subtitleText = "Validatopia Fitness Center",
    )

    val ageVerification = CardArtStyle(
        titleLead = "Age ",
        titleEmphasis = "Verification",
        background = ValidatopiaColors.TEAL,
        hills = ValidatopiaColors.GREEN,
        title = WHITE,
        subtitle = WHITE,
        subtitleText = "For Online Pseudonymous Use",
    )

    /** For a document whose type isn't known yet, e.g. before its credentials are created. */
    val unknown = CardArtStyle(
        titleLead = "",
        titleEmphasis = "Document",
        background = ValidatopiaColors.NAVY,
        hills = ValidatopiaColors.GREEN,
        title = WHITE,
        subtitle = ValidatopiaColors.GREEN,
        subtitleText = "Validatopia",
    )

    /** Every style, for checking them all. */
    val all: List<CardArtStyle> = listOf(photoId, driverLicence, gymMembership, ageVerification, unknown)

    /** The style for an mdoc of [docType], or [unknown]. */
    fun styleFor(docType: String?): CardArtStyle = when (docType) {
        PhotoID.PHOTO_ID_DOCTYPE -> photoId
        DrivingLicense.MDL_DOCTYPE -> driverLicence
        Loyalty.LOYALTY_DOCTYPE -> gymMembership
        AgeVerification.AV_DOCTYPE -> ageVerification
        else -> unknown
    }

    /**
     * The style for [document], by the doctype of its credentials (pending or certified), or
     * [unknown] if it has none yet.
     */
    suspend fun styleFor(document: Document): CardArtStyle =
        styleFor(document.getCredentials().filterIsInstance<MdocCredential>().firstOrNull()?.docType)

    /**
     * The holder's shortened name for [document]'s card, from the `given_name` and `family_name`
     * of its first certified mdoc credential, or `null` if it has none yet or carries no name.
     */
    suspend fun holderShortName(document: Document): String? {
        val credential = document.getCertifiedCredentials().filterIsInstance<MdocCredential>().firstOrNull()
            ?: return null
        for (elements in credential.issuerNamespaces.data.values) {
            val givenName = (elements["given_name"]?.dataElementValue as? Tstr)?.value ?: continue
            val familyName = (elements["family_name"]?.dataElementValue as? Tstr)?.value
            return shortName(givenName, familyName)
        }
        return null
    }

    /**
     * The first given name and the family name's initial, as in "Claudia H.". Names in capitals
     * (as read from a passport's MRZ) are shown in title case.
     */
    fun shortName(givenName: String, familyName: String?): String? {
        val first = givenName.trim().split(Regex("\\s+")).first().toDisplayCase()
        if (first.isEmpty()) {
            return null
        }
        val initial = familyName?.trim()?.firstOrNull()?.uppercaseChar() ?: return first
        return "$first $initial."
    }

    private fun String.toDisplayCase(): String =
        if (this == uppercase()) lowercase().replaceFirstChar { it.uppercaseChar() } else this
}
