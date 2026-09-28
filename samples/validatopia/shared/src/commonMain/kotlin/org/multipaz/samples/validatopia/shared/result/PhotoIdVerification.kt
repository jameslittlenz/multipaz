package org.multipaz.samples.validatopia.shared.result

import org.multipaz.samples.validatopia.shared.crossborder.PassportCheckResult
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdElement
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import kotlin.time.Instant

/** Outcome of a single check, as shown by a trust badge (always rendered with an icon and text). */
enum class CheckOutcome {
    /** The check passed. */
    PASSED,

    /** The check passed, but only against TEST trust anchors, or with a caveat worth showing. */
    WARNING,

    /** The check failed. */
    FAILED,

    /** The check couldn't be performed (for example no network for revocation). */
    UNKNOWN,
}

/**
 * One row in a trust panel.
 *
 * @property label what was checked, for example "Issuer" or "Signature and digests".
 * @property outcome the result.
 * @property detail a short human-readable explanation of the result.
 */
data class TrustCheck(
    val label: String,
    val outcome: CheckOutcome,
    val detail: String,
)

/**
 * A trust panel: "Credential issuer" or "Passport issuer (CSCA)".
 *
 * @property title the panel heading.
 * @property checks the rows, in display order.
 */
data class TrustPanel(
    val title: String,
    val checks: List<TrustCheck>,
) {
    /** The worst outcome across [checks], used for the panel's summary badge. */
    val overall: CheckOutcome
        get() = when {
            checks.any { it.outcome == CheckOutcome.FAILED } -> CheckOutcome.FAILED
            checks.any { it.outcome == CheckOutcome.UNKNOWN } -> CheckOutcome.UNKNOWN
            checks.any { it.outcome == CheckOutcome.WARNING } -> CheckOutcome.WARNING
            else -> CheckOutcome.PASSED
        }
}

/** A disclosed value, ready for display. */
sealed class ClaimValue {
    /** A value rendered as text. */
    data class Text(val text: String) : ClaimValue()

    /** An image (JPEG or JPEG 2000). */
    class Image(val bytes: ByteArray) : ClaimValue()

    /** Opaque binary data, shown by size only (e.g. the SOD). */
    data class Binary(val size: Int) : ClaimValue()
}

/**
 * A data element the holder shared.
 *
 * @property element which element.
 * @property displayName the element's human-readable name.
 * @property value the value.
 * @property intentToRetain whether the verifier said it would keep this element.
 */
data class DisclosedClaim(
    val element: PhotoIdElement,
    val displayName: String,
    val value: ClaimValue,
    val intentToRetain: Boolean,
)

/**
 * A Photo ID element that wasn't shared.
 *
 * @property element which element.
 * @property displayName the element's human-readable name.
 * @property wasRequested `true` if the verifier asked for it and the wallet withheld it.
 */
data class NotSharedElement(
    val element: PhotoIdElement,
    val displayName: String,
    val wasRequested: Boolean,
)

/**
 * A field that DG1 discloses as a whole, for the "DG1 reveals" note.
 *
 * @property label the field name.
 * @property value its value, if DG1 could be parsed.
 */
data class Dg1Field(val label: String, val value: String?)

/**
 * Everything the verifier's result screen shows for one presentation.
 *
 * @property useCase the use case the request was made for.
 * @property verifiedAt when the response was checked.
 * @property credentialIssuer the "Credential issuer" trust panel.
 * @property disclosed the shared elements, in request order.
 * @property notShared the Photo ID elements that weren't shared.
 * @property dg1Reveals every field DG1 discloses, if `dg1` was shared, else `null`.
 * @property passportIssuer the "Passport issuer (CSCA)" trust panel, for the cross-border use case.
 * @property passportCheck the detailed cross-border result, for the cross-border use case.
 */
data class PhotoIdVerification(
    val useCase: PhotoIdUseCase,
    val verifiedAt: Instant,
    val credentialIssuer: TrustPanel,
    val disclosed: List<DisclosedClaim>,
    val notShared: List<NotSharedElement>,
    val dg1Reveals: List<Dg1Field>?,
    val passportIssuer: TrustPanel?,
    val passportCheck: PassportCheckResult?,
) {
    /** The disclosed value of [element], if shared. */
    fun valueOf(element: PhotoIdElement): ClaimValue? = disclosed.firstOrNull { it.element == element }?.value
}

/** The response couldn't be used at all, for example it held no Photo ID. */
class PhotoIdVerificationException(message: String, cause: Throwable? = null) : Exception(message, cause)
