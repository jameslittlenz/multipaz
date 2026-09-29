package org.multipaz.samples.validatopia.shared.branding

import org.multipaz.document.Document
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.utopia.knowntypes.Loyalty

/**
 * The colors and title of one document's card art.
 *
 * @property titleLead the start of the title, in regular weight (including any trailing space).
 * @property titleEmphasis the rest of the title, in bold.
 * @property background the card's background color.
 * @property hills the color of the three hills; the nearest is opaque, the others tints of it.
 * @property title the title's color.
 * @property subtitle the color of the "Validatopia" line under the title.
 */
data class CardArtStyle(
    val titleLead: String,
    val titleEmphasis: String,
    val background: String,
    val hills: String,
    val title: String,
    val subtitle: String,
)

/**
 * Card art for the documents Validatopia Wallet holds, drawn on the device (the issuer supplies
 * none) by `PhotoIdCardArt` on Android and iOS from these styles.
 *
 * It follows the NZ DISTF "flash pass" guidance
 * (https://github.com/nz-trust-framework/DISTF-reference-architecture/blob/main/guidance/FLASH-PASS.md):
 * the card appears on the presenting screen and in consent sheets, so it shows only the document
 * type and its provider, with no name, portrait or other identifying information. Every document
 * shares one design, a background with three rolling hills, and each type has its own pairing of
 * navy, teal and white so it can be told apart at a glance. The title always names the type, so
 * color is never the only cue.
 */
object ValidatopiaCardArt {
    private const val WHITE = "#FFFFFF"

    /** Hill opacities, far to near. The nearest is opaque: the white "Powered by" credit sits on it. */
    val hillAlphas: List<Double> = listOf(0.22, 0.45, 1.0)

    /** The "Powered by" credit and Valid8 logo are white, on the nearest hill. */
    const val CREDIT = WHITE

    val photoId = CardArtStyle(
        titleLead = "Photo ",
        titleEmphasis = "ID",
        background = ValidatopiaColors.NAVY,
        hills = ValidatopiaColors.TEAL,
        title = WHITE,
        subtitle = WHITE,
    )

    val driverLicence = CardArtStyle(
        titleLead = "Driver ",
        titleEmphasis = "Licence",
        background = ValidatopiaColors.TEAL,
        hills = ValidatopiaColors.NAVY,
        title = WHITE,
        subtitle = WHITE,
    )

    val gymMembership = CardArtStyle(
        titleLead = "Gym ",
        titleEmphasis = "Membership",
        background = WHITE,
        hills = ValidatopiaColors.TEAL,
        title = ValidatopiaColors.NAVY,
        subtitle = ValidatopiaColors.TEAL,
    )

    val ageVerification = CardArtStyle(
        titleLead = "Age ",
        titleEmphasis = "Verification",
        background = WHITE,
        hills = ValidatopiaColors.NAVY,
        title = ValidatopiaColors.NAVY,
        subtitle = ValidatopiaColors.NAVY,
    )

    /** For a document whose type isn't known yet, e.g. before its credentials are created. */
    val unknown = CardArtStyle(
        titleLead = "",
        titleEmphasis = "Document",
        background = ValidatopiaColors.NAVY,
        hills = ValidatopiaColors.TEAL,
        title = WHITE,
        subtitle = WHITE,
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
}
