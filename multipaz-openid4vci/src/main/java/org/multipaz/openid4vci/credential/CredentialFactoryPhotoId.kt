package org.multipaz.openid4vci.credential

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.PhotoID
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Uint
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat

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

    override val offeredAfterIdentityProofing: Boolean get() = true

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
        val (validFrom, validUntil) = validatopiaValidity(coreData, timeZone)

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(PhotoID.ISO_23220_2_NAMESPACE) {
                addDataElement("family_name", coreData["family_name"])
                addDataElement("given_name", coreData["given_name"])
                addDataElement("birth_date", dateOfBirth.toDataItemFullDate())
                addDataElement("portrait", coreData["portrait"])
                addDataElement("issue_date", coreData["issue_date"].asDateString.toDataItemFullDate())
                addDataElement("expiry_date", coreData["expiry_date"].asDateString.toDataItemFullDate())
                addDataElement("issuing_authority", coreData["issuing_authority"])
                addDataElement("issuing_country", ValidatopiaCredentials.ISSUING_COUNTRY.toDataItem())
                addDataElement("nationality", coreData["nationality"])
                addDataElement("sex", coreData["sex"])
                addDataElement("document_number", coreData["document_number"])
                addDataElement(
                    "age_in_years",
                    Uint(dateOfBirth.yearsUntil(now.toLocalDateTime(timeZone).date).toULong())
                )
                addDataElement("age_birth_year", Uint(dateOfBirth.year.toULong()))
                for (age in AGE_THRESHOLDS) {
                    val identifier = if (age < 10) "age_over_0$age" else "age_over_$age"
                    addDataElement(
                        identifier,
                        if (isAgeOver(dateOfBirth, age, now, timeZone)) Simple.TRUE else Simple.FALSE
                    )
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

        return signMdoc(
            docType = PhotoID.PHOTO_ID_DOCTYPE,
            issuerNamespaces = issuerNamespaces,
            validFrom = validFrom,
            validUntil = validUntil,
            authenticationKey = authenticationKey!!,
            credentialId = credentialId,
        )
    }

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(systemOfRecordData, "credential_photo_id")

    companion object {
        // MSO size for Longfellow-ZK is limited (~2200 bytes); mirror the curated threshold list
        // PhotoID's own sample data uses instead of provisioning all 99 age_over_NN claims.
        private val AGE_THRESHOLDS = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)
        private val FORMAT = CredentialFormat.Mdoc(PhotoID.PHOTO_ID_DOCTYPE)
    }
}
