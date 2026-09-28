package org.multipaz.openid4vci.credential

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.PhotoID
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.toBase64Url

/**
 * [CredentialFactory] for Validatopia [PhotoID] credentials in ISO mdoc format.
 *
 * Every claim comes from [org.multipaz.openid4vci.idv.IdentityProofing]'s `systemOfRecordData`,
 * which is derived from a passport chip read (or a dummy persona) and already carries the final
 * `issue_date`/`expiry_date` and `issuing_authority`; this factory does not fall back to sample
 * or placeholder data for any element (see `docs/validatopia/PLAN.md`'s Component D).
 */
class CredentialFactoryPhotoId : CredentialFactory {
    override val configurationId: String
        get() = "photo_id_mdoc"

    override val scope: String
        get() = "photo_id"

    override val format
        get() = FORMAT

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val acceptAndroidKeyAttestation: Boolean get() = true

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Validatopia Photo ID"

    // Card art in Validatopia branding is Component H (apps), deferred until then.
    override val logo: String?
        get() = null

    override suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()

        val coreData = systemOfRecordData["core"]
        val datagroupsData = systemOfRecordData["datagroups"]

        val dateOfBirth = coreData["birth_date"].asDateString
        val issueDate = coreData["issue_date"].asDateString
        val expiryDate = coreData["expiry_date"].asDateString

        // Make sure to not use fractional seconds as 18013-5 calls for this (clauses 7.1
        // and 9.1.2.4).
        val timeSigned = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validFrom = issueDate.atStartOfDayIn(timeZone)
        val validUntil = expiryDate.atStartOfDayIn(timeZone)

        val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(PhotoID.ISO_23220_2_NAMESPACE) {
                addDataElement("family_name", coreData["family_name"])
                addDataElement("given_name", coreData["given_name"])
                addDataElement("birth_date", dateOfBirth.toDataItemFullDate())
                addDataElement("portrait", coreData["portrait"])
                addDataElement("issue_date", issueDate.toDataItemFullDate())
                addDataElement("expiry_date", expiryDate.toDataItemFullDate())
                addDataElement("issuing_authority", coreData["issuing_authority"])
                addDataElement("issuing_country", ISSUING_COUNTRY.toDataItem())
                addDataElement("nationality", coreData["nationality"])
                addDataElement("sex", coreData["sex"])
                addDataElement("document_number", coreData["document_number"])
                addDataElement(
                    "age_in_years",
                    Uint(dateOfBirth.yearsUntil(now.toLocalDateTime(timeZone).date).toULong())
                )
                addDataElement("age_birth_year", Uint(dateOfBirth.year.toULong()))
                for (age in AGE_THRESHOLDS) {
                    val ageOver = now > dateOfBirthInstant.plus(age, DateTimeUnit.YEAR, timeZone)
                    val identifier = if (age < 10) "age_over_0$age" else "age_over_$age"
                    addDataElement(identifier, if (ageOver) Simple.TRUE else Simple.FALSE)
                }
            }
            addNamespace(PhotoID.PHOTO_ID_NAMESPACE) {
                addDataElement("travel_document_type", coreData["travel_document_type"])
                addDataElement("travel_document_number", coreData["travel_document_number"])
                addDataElement("travel_document_mrz", coreData["travel_document_mrz"])
            }
            addNamespace(PhotoID.DATAGROUPS_NAMESPACE) {
                addDataElement("version", datagroupsData["version"])
                addDataElement("sod", datagroupsData["sod"])
                addDataElement("dg1", datagroupsData["dg1"])
                addDataElement("dg2", datagroupsData["dg2"])
            }
        }

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        // Generate an MSO and issuer-signed data for this authentication key.
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = PhotoID.PHOTO_ID_DOCTYPE,
            signedAt = timeSigned,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = authenticationKey!!,
            revocationStatus = revocationStatus,
        )
        val taggedEncodedMso = Cbor.encode(Tagged(
            Tagged.ENCODED_CBOR,
            Bstr(Cbor.encode(mso.toDataItem())))
        )

        // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
        //
        // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
        //
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
            )
        )
        val signingKey = getSigningKey()
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                signingKey.certChain.toCoseX5Chain()
            )
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey,
                taggedEncodedMso,
                true,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )
        val issuerProvidedAuthenticationData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
        )

        return MintedCredential(
            credential = issuerProvidedAuthenticationData.toBase64Url(),
            creation = validFrom,
            expiration = validUntil
        )
    }

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(systemOfRecordData, "credential_photo_id")

    companion object {
        // MSO size for Longfellow-ZK is limited (~2200 bytes); mirror the curated threshold list
        // PhotoID's own sample data uses instead of provisioning all 99 age_over_NN claims.
        private val AGE_THRESHOLDS = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)
        private const val ISSUING_COUNTRY = "XV"
        private val FORMAT = CredentialFormat.Mdoc(PhotoID.PHOTO_ID_DOCTYPE)
    }
}
