package org.multipaz.samples.validatopia.shared.result

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.lds.LdsException
import org.multipaz.idv.mrz.Mrz
import org.multipaz.idv.mrz.MrzException
import org.multipaz.idv.pa.CscaStore
import org.multipaz.idv.pa.PassiveAuthenticationFlag
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.revocation.RevocationCheckState
import org.multipaz.revocation.RevocationChecker
import org.multipaz.samples.validatopia.shared.crossborder.PassportCheck
import org.multipaz.samples.validatopia.shared.crossborder.PassportCheckResult
import org.multipaz.samples.validatopia.shared.crossborder.PhotoIdClaimsForPassportCheck
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdElement
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.samples.validatopia.shared.usecase.ValidatopiaPhotoIdProfile
import org.multipaz.trustmanagement.TrustManagerInterface
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Turns a Photo ID [DeviceResponse] into a [PhotoIdVerification]: verifies it, evaluates issuer
 * trust, works out what was and wasn't shared and, for the cross-border use case, checks the
 * embedded passport data.
 *
 * @param issuerTrustManager trust anchors for Photo ID issuers.
 * @param cscaStore trusted CSCAs, for the cross-border use case.
 * @param revocationChecker checks the credential's status list, or `null` to skip revocation.
 * @param documentType the Photo ID document type, used for element display names.
 */
class PhotoIdVerifier(
    private val issuerTrustManager: TrustManagerInterface,
    private val cscaStore: CscaStore,
    private val revocationChecker: RevocationChecker?,
    private val documentType: DocumentType = PhotoID.getDocumentType(),
) {
    /**
     * Verifies [deviceResponse], which answered a request for [useCase] in the session with
     * [sessionTranscript].
     *
     * @throws PhotoIdVerificationException if the response doesn't hold a Photo ID at all.
     */
    @Throws(PhotoIdVerificationException::class, CancellationException::class)
    suspend fun verify(
        useCase: PhotoIdUseCase,
        deviceResponse: DeviceResponse,
        sessionTranscript: DataItem,
        at: Instant = Clock.System.now(),
    ): PhotoIdVerification {
        // DeviceResponse only exposes its documents once verify() has been called, pass or fail.
        val verifyNowError = try {
            deviceResponse.verify(sessionTranscript = sessionTranscript, atTime = at)
            null
        } catch (e: IllegalStateException) {
            e
        }
        val document = deviceResponse.documents.firstOrNull { it.docType == PhotoID.PHOTO_ID_DOCTYPE }
            ?: throw PhotoIdVerificationException(
                if (deviceResponse.documents.isEmpty()) {
                    "The wallet didn't share a Photo ID (status ${deviceResponse.status})"
                } else {
                    "The wallet shared a ${deviceResponse.documents.first().docType} document, not a Photo ID"
                }
            )

        val credentialIssuer = TrustPanel(
            title = "Credential issuer",
            checks = listOf(
                issuerCheck(document, at),
                cryptographyCheck(deviceResponse, document, sessionTranscript, verifyNowError),
                validityCheck(document, at),
                revocationCheck(document, at),
            )
        )

        val returned = document.issuerNamespaces.data
        val requested = useCase.requested.associateBy { it.element }
        val disclosed = buildList {
            // Requested elements first, in request order, then anything unrequested.
            val order = useCase.requested.map { it.element } +
                returned.flatMap { (ns, items) -> items.keys.map { PhotoIdElement(ns, it) } }
            for (element in order.distinct()) {
                val item = returned[element.namespace]?.get(element.identifier) ?: continue
                add(
                    DisclosedClaim(
                        element = element,
                        displayName = displayName(element),
                        value = render(element, item.dataElementValue),
                        intentToRetain = requested[element]?.intentToRetain ?: false,
                    )
                )
            }
        }
        val disclosedElements = disclosed.map { it.element }.toSet()
        val notShared = (useCase.requested.map { it.element } + ValidatopiaPhotoIdProfile.elements)
            .distinct()
            .filter { it !in disclosedElements }
            .map { NotSharedElement(it, displayName(it), wasRequested = it in requested) }

        val sod = bytesOf(document, DATAGROUP_SOD)
        val dg1 = bytesOf(document, DATAGROUP_DG1)
        val dg2 = bytesOf(document, DATAGROUP_DG2)

        val passportCheck = if (useCase.checksPassport && sod != null && dg1 != null && dg2 != null) {
            PassportCheck.check(
                sod = sod,
                dg1 = dg1,
                dg2 = dg2,
                claims = claimsForPassportCheck(document),
                cscaStore = cscaStore,
                at = at,
            )
        } else {
            null
        }
        val passportIssuer = if (useCase.checksPassport) passportPanel(passportCheck) else null

        return PhotoIdVerification(
            useCase = useCase,
            verifiedAt = at,
            credentialIssuer = credentialIssuer,
            disclosed = disclosed,
            notShared = notShared,
            dg1Reveals = dg1?.let { dg1Reveals(it) },
            passportIssuer = passportIssuer,
            passportCheck = passportCheck,
        )
    }

    private suspend fun issuerCheck(document: MdocDocument, at: Instant): TrustCheck {
        val result = issuerTrustManager.verify(document.issuerCertChain.certificates, at)
        if (!result.isTrusted) {
            return TrustCheck(
                label = "Issuer",
                outcome = CheckOutcome.FAILED,
                detail = "Unknown issuer: ${document.issuerCertChain.certificates.first().issuer.name}",
            )
        }
        val metadata = result.trustPoints.firstOrNull()?.metadata
        val name = metadata?.displayName ?: result.trustChain?.certificates?.last()?.subject?.name ?: "Trusted issuer"
        return if (metadata?.testOnly == true) {
            TrustCheck("Issuer", CheckOutcome.WARNING, "$name (TEST: not a real issuer)")
        } else {
            TrustCheck("Issuer", CheckOutcome.PASSED, name)
        }
    }

    private suspend fun cryptographyCheck(
        deviceResponse: DeviceResponse,
        document: MdocDocument,
        sessionTranscript: DataItem,
        verifyNowError: IllegalStateException?,
    ): TrustCheck {
        val passed = TrustCheck(
            "Signature and digests",
            CheckOutcome.PASSED,
            "Issuer signature, data digests and device binding verified"
        )
        if (verifyNowError == null) {
            return passed
        }
        // Failing now may only mean the MSO is outside its validity period, which has a row of its
        // own. Re-verify as of the MSO's signing time so this row reports the issuer signature,
        // value digests and device authentication alone.
        return try {
            deviceResponse.verify(sessionTranscript = sessionTranscript, atTime = document.mso.signedAt)
            passed
        } catch (e: IllegalStateException) {
            TrustCheck("Signature and digests", CheckOutcome.FAILED, e.message ?: "Verification failed")
        }
    }

    private fun validityCheck(document: MdocDocument, at: Instant): TrustCheck {
        val mso = document.mso
        val until = mso.validUntil.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return when {
            at < mso.validFrom -> TrustCheck(
                "Validity",
                CheckOutcome.FAILED,
                "Not valid until ${mso.validFrom.toLocalDateTime(TimeZone.currentSystemDefault()).date}"
            )
            at > mso.validUntil -> TrustCheck("Validity", CheckOutcome.FAILED, "Expired on $until")
            else -> TrustCheck("Validity", CheckOutcome.PASSED, "Valid until $until")
        }
    }

    private suspend fun revocationCheck(document: MdocDocument, at: Instant): TrustCheck {
        val status = document.mso.revocationStatus
            ?: return TrustCheck("Revocation", CheckOutcome.UNKNOWN, "The credential has no revocation information")
        val checker = revocationChecker
            ?: return TrustCheck("Revocation", CheckOutcome.UNKNOWN, "Not checked")
        val result = checker.check(
            revocationStatus = status,
            issuerCert = document.issuerCertChain.certificates.first(),
            onlyTrusted = false,
            atTime = at,
            bypassCache = true,
        )
        return when (result.state) {
            RevocationCheckState.VALID -> TrustCheck("Revocation", CheckOutcome.PASSED, "Not revoked")
            RevocationCheckState.INVALID -> TrustCheck("Revocation", CheckOutcome.FAILED, "Revoked by the issuer")
            RevocationCheckState.SUSPENDED -> TrustCheck("Revocation", CheckOutcome.FAILED, "Suspended by the issuer")
            RevocationCheckState.UNKNOWN -> TrustCheck(
                "Revocation",
                CheckOutcome.UNKNOWN,
                "Couldn't check: ${result.error?.message ?: "status unavailable"}"
            )
        }
    }

    private fun passportPanel(check: PassportCheckResult?): TrustPanel {
        if (check == null) {
            return TrustPanel(
                title = "Passport issuer (CSCA)",
                checks = listOf(
                    TrustCheck("Passport data", CheckOutcome.FAILED, "The SOD, DG1 and DG2 were not all shared")
                )
            )
        }
        val pa = check.passiveAuthentication
        val flags = pa.flags
        val checks = buildList {
            add(
                when {
                    PassiveAuthenticationFlag.UNTRUSTED_CSCA in flags -> TrustCheck(
                        "Passport issuer",
                        CheckOutcome.FAILED,
                        "Unknown CSCA: ${pa.documentSignerCertificate?.issuer?.name ?: "none"}"
                    )
                    PassiveAuthenticationFlag.CHAIN_INVALID in flags ->
                        TrustCheck("Passport issuer", CheckOutcome.FAILED, "Document Signer doesn't chain to the CSCA")
                    PassiveAuthenticationFlag.ALGORITHM_UNSUPPORTED_ON_DEVICE in flags ->
                        TrustCheck("Passport issuer", CheckOutcome.UNKNOWN, "Algorithm not supported on this device")
                    else -> TrustCheck(
                        "Passport issuer",
                        // Only the Validatopia Test CSCA is bundled, so a trusted chain is always TEST.
                        CheckOutcome.WARNING,
                        "${check.cscaSubject ?: "Trusted CSCA"} (TEST: not a real passport issuer)"
                    )
                }
            )
            add(
                if (PassiveAuthenticationFlag.SIGNATURE_INVALID in flags || PassiveAuthenticationFlag.MALFORMED_SOD in flags) {
                    TrustCheck("SOD signature", CheckOutcome.FAILED, "The SOD's signature is invalid or malformed")
                } else {
                    TrustCheck("SOD signature", CheckOutcome.PASSED, "Signed by the Document Signer")
                }
            )
            add(
                if (PassiveAuthenticationFlag.DOCUMENT_SIGNER_VALIDITY in flags) {
                    TrustCheck("Document Signer", CheckOutcome.FAILED, "Outside its validity period")
                } else {
                    TrustCheck("Document Signer", CheckOutcome.PASSED, "Within its validity period")
                }
            )
            for ((dg, label) in listOf(1 to "DG1 (MRZ) hash", 2 to "DG2 (face) hash")) {
                add(
                    when (pa.dataGroupHashMatches[dg]) {
                        true -> TrustCheck(label, CheckOutcome.PASSED, "Matches the SOD")
                        false -> TrustCheck(label, CheckOutcome.FAILED, "Doesn't match the SOD: the data was altered")
                        null -> TrustCheck(label, CheckOutcome.UNKNOWN, "Not checked")
                    }
                )
            }
            add(
                when {
                    check.mrz == null ->
                        TrustCheck("DG1 matches claims", CheckOutcome.FAILED, "DG1 couldn't be read: ${check.mrzError}")
                    check.comparisons.any { it.matches == false } -> TrustCheck(
                        "DG1 matches claims",
                        CheckOutcome.FAILED,
                        "Mismatch in: " + check.comparisons.filter { it.matches == false }.joinToString { it.label }
                    )
                    else -> TrustCheck("DG1 matches claims", CheckOutcome.PASSED, "Every shared claim agrees with DG1")
                }
            )
            if (check.mrz != null) {
                add(
                    if (check.passportExpired) {
                        TrustCheck("Passport expiry", CheckOutcome.FAILED, "Expired on ${check.mrz.expiryDate}")
                    } else {
                        TrustCheck("Passport expiry", CheckOutcome.PASSED, "Valid until ${check.mrz.expiryDate}")
                    }
                )
            }
        }
        return TrustPanel(title = "Passport issuer (CSCA)", checks = checks)
    }

    private fun claimsForPassportCheck(document: MdocDocument): PhotoIdClaimsForPassportCheck {
        fun value(namespace: String, identifier: String): DataItem? =
            document.issuerNamespaces.data[namespace]?.get(identifier)?.dataElementValue
        val core = PhotoID.ISO_23220_2_NAMESPACE
        return PhotoIdClaimsForPassportCheck(
            familyName = (value(core, "family_name") as? Tstr)?.value,
            givenName = (value(core, "given_name") as? Tstr)?.value,
            birthDate = value(core, "birth_date")?.let { dateOf(it) },
            sex = (value(core, "sex") as? Uint)?.value?.toInt(),
            nationalityAlpha2 = (value(core, "nationality") as? Tstr)?.value,
            travelDocumentNumber = (value(PhotoID.PHOTO_ID_NAMESPACE, "travel_document_number") as? Tstr)?.value,
        )
    }

    private fun dg1Reveals(dg1: ByteArray): List<Dg1Field> {
        val mrz = try {
            Mrz.parseTd3(Lds.parseDG1(dg1))
        } catch (e: LdsException) {
            null
        } catch (e: MrzException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        return listOf(
            Dg1Field("Full name", mrz?.let { "${it.secondaryIdentifier} ${it.primaryIdentifier}".trim() }),
            Dg1Field("Date of birth", mrz?.birthDate?.toString()),
            Dg1Field("Sex", mrz?.sex?.name?.lowercase()?.replaceFirstChar { it.uppercase() }),
            Dg1Field("Nationality", mrz?.nationality),
            Dg1Field("Passport number", mrz?.documentNumber),
            Dg1Field("Passport expiry date", mrz?.expiryDate?.toString()),
            Dg1Field("Issuing country", mrz?.issuingState),
        )
    }

    private fun displayName(element: PhotoIdElement): String =
        documentType.mdocDocumentType?.namespaces?.get(element.namespace)
            ?.dataElements?.get(element.identifier)?.attribute?.displayName
            ?: element.identifier

    private fun render(element: PhotoIdElement, value: DataItem): ClaimValue = when {
        // DG2 stays binary here: it's an ISO 19794-5 record, whose face image the passport check extracts.
        element.identifier == "portrait" && value is Bstr -> ClaimValue.Image(value.value)
        value is Bstr -> ClaimValue.Binary(value.value.size)
        value is Tstr -> ClaimValue.Text(value.value)
        value == Simple.TRUE -> ClaimValue.Text("Yes")
        value == Simple.FALSE -> ClaimValue.Text("No")
        element.identifier == "sex" && value is Uint -> ClaimValue.Text(PassportCheck.sexLabel(value.value.toInt()))
        value is Uint -> ClaimValue.Text(value.value.toString())
        value is Nint -> ClaimValue.Text("-${value.value}")
        value is Tagged -> dateOf(value)?.let { ClaimValue.Text(it.toString()) } ?: ClaimValue.Text(value.toString())
        else -> ClaimValue.Text(value.toString())
    }

    private fun dateOf(value: DataItem): LocalDate? = try {
        value.asDateStringOrDateTimeString
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun bytesOf(document: MdocDocument, element: PhotoIdElement): ByteArray? =
        (document.issuerNamespaces.data[element.namespace]?.get(element.identifier)?.dataElementValue as? Bstr)?.value

    companion object {
        private val DATAGROUP_SOD = PhotoIdElement(PhotoID.DATAGROUPS_NAMESPACE, "sod")
        private val DATAGROUP_DG1 = PhotoIdElement(PhotoID.DATAGROUPS_NAMESPACE, "dg1")
        private val DATAGROUP_DG2 = PhotoIdElement(PhotoID.DATAGROUPS_NAMESPACE, "dg2")
    }
}
