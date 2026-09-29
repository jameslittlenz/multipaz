package org.multipaz.samples.validatopia.shared.usecase

import org.multipaz.cbor.DataItem
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequest

/** A single Photo ID data element: namespace plus identifier. */
data class PhotoIdElement(val namespace: String, val identifier: String) {
    /** The element's human-readable name from the Photo ID document type, or its identifier. */
    val displayName: String
        get() = photoIdDocumentType.mdocDocumentType?.namespaces?.get(namespace)
            ?.dataElements?.get(identifier)?.attribute?.displayName ?: identifier
}

private val photoIdDocumentType by lazy { PhotoID.getDocumentType() }

/** A requested element and whether the verifier declares an intent to retain it. */
data class RequestedElement(val element: PhotoIdElement, val intentToRetain: Boolean)

private fun core(identifier: String) = PhotoIdElement(PhotoID.ISO_23220_2_NAMESPACE, identifier)
private fun photoId(identifier: String) = PhotoIdElement(PhotoID.PHOTO_ID_NAMESPACE, identifier)
private fun datagroup(identifier: String) = PhotoIdElement(PhotoID.DATAGROUPS_NAMESPACE, identifier)

/**
 * The verifier's use cases (`docs/validatopia/PLAN.md`, Component H, use cases 1–5). Use case 6,
 * the zero-knowledge age proof, is a stretch goal and not implemented yet.
 *
 * @property title short name shown in the use-case picker.
 * @property purpose one-sentence description of why the verifier asks for this data.
 * @property requested the elements requested, in display order.
 */
enum class PhotoIdUseCase(
    val title: String,
    val purpose: String,
    val requested: List<RequestedElement>,
) {
    VENUE_ENTRY(
        title = "Venue entry",
        purpose = "Checks the holder is 18 or over. Nothing else is requested.",
        requested = listOf(RequestedElement(core("age_over_18"), false)),
    ),
    LIQUOR_STORE(
        title = "Liquor store",
        purpose = "Checks the holder is 18 or over, and shows their photo to match against the person.",
        requested = listOf(
            RequestedElement(core("age_over_18"), false),
            RequestedElement(core("portrait"), false),
        ),
    ),
    PARCEL_PICKUP(
        title = "Parcel pickup",
        purpose = "Checks the name on the parcel and shows the holder's photo.",
        requested = listOf(
            RequestedElement(core("given_name"), false),
            RequestedElement(core("family_name"), false),
            RequestedElement(core("portrait"), false),
        ),
    ),
    HOTEL_CHECK_IN(
        title = "Hotel check-in / KYC",
        purpose = "Records the guest's identity. Nationality and document number are kept on file.",
        requested = listOf(
            RequestedElement(core("family_name"), false),
            RequestedElement(core("given_name"), false),
            RequestedElement(core("birth_date"), false),
            RequestedElement(core("portrait"), false),
            RequestedElement(core("issue_date"), false),
            RequestedElement(core("expiry_date"), false),
            RequestedElement(core("issuing_authority"), false),
            RequestedElement(core("issuing_country"), false),
            RequestedElement(core("nationality"), true),
            RequestedElement(core("document_number"), true),
        ),
    ),
    CROSS_BORDER(
        title = "Cross-border travel",
        purpose = "Checks the Photo ID and, independently, the passport data it carries against the passport's " +
            "issuing country. Everything is kept on file.",
        requested = listOf(
            RequestedElement(core("portrait"), true),
            RequestedElement(core("family_name"), true),
            RequestedElement(core("given_name"), true),
            RequestedElement(core("birth_date"), true),
            RequestedElement(core("sex"), true),
            RequestedElement(core("nationality"), true),
            RequestedElement(photoId("travel_document_type"), true),
            RequestedElement(photoId("travel_document_number"), true),
            RequestedElement(core("expiry_date"), true),
            RequestedElement(datagroup("version"), true),
            RequestedElement(datagroup("sod"), true),
            RequestedElement(datagroup("dg1"), true),
            RequestedElement(datagroup("dg2"), true),
        ),
    );

    /** Whether this use case asks for the passport data groups and so runs the cross-border checks. */
    val checksPassport: Boolean
        get() = requested.any { it.element.namespace == PhotoID.DATAGROUPS_NAMESPACE }

    /** The requested elements in [DeviceRequest] form: namespace → element → intent to retain. */
    val nameSpaces: Map<String, Map<String, Boolean>>
        get() = requested.groupBy { it.element.namespace }.mapValues { (_, elements) ->
            elements.associate { it.element.identifier to it.intentToRetain }
        }

    /**
     * Builds the [DeviceRequest] for this use case, signed with [readerKey] when given so the
     * wallet can show who is asking.
     *
     * @param sessionTranscript the session transcript for the engagement.
     * @param readerKey the reader-authentication key, or `null` to send an unsigned request.
     */
    suspend fun buildRequest(sessionTranscript: DataItem, readerKey: AsymmetricKey.X509Compatible?): DeviceRequest =
        buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = nameSpaces,
                docRequestInfo = null,
                readerKey = readerKey,
            )
        }
}

/**
 * Every element a Validatopia Photo ID carries, as minted by the issuer's
 * `CredentialFactoryPhotoId`. The verifier's "Not shared" panel lists the ones that weren't
 * returned. It is limited to this profile, not the whole ISO/IEC 23220-4 doctype, because
 * elements the credential never has (`dg3`–`dg16`, addresses and so on) aren't being withheld.
 */
object ValidatopiaPhotoIdProfile {
    private val AGE_THRESHOLDS = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)

    val elements: List<PhotoIdElement> = buildList {
        for (identifier in listOf(
            "family_name", "given_name", "birth_date", "portrait", "issue_date", "expiry_date",
            "issuing_authority", "issuing_country", "nationality", "sex", "document_number",
            "age_in_years", "age_birth_year",
        )) {
            add(core(identifier))
        }
        for (age in AGE_THRESHOLDS) {
            add(core(if (age < 10) "age_over_0$age" else "age_over_$age"))
        }
        add(photoId("travel_document_type"))
        add(photoId("travel_document_number"))
        add(photoId("travel_document_mrz"))
        add(datagroup("version"))
        add(datagroup("sod"))
        add(datagroup("dg1"))
        add(datagroup("dg2"))
    }
}
