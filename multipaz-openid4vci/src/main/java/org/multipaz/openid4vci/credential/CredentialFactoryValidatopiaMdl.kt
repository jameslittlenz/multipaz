package org.multipaz.openid4vci.credential

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import kotlin.time.Clock

/**
 * [CredentialFactory] for Validatopia [DrivingLicense] credentials in ISO mdoc format.
 *
 * Like [CredentialFactoryPhotoId], every claim comes from identity proofing's
 * `systemOfRecordData`: the holder's details from `core`, and the licence number and vehicle
 * category from `driving_licence`. Nothing falls back to sample or placeholder data.
 */
class CredentialFactoryValidatopiaMdl : CredentialFactory {
    override val configurationId: String
        get() = "validatopia_mdl"

    override val scope: String
        get() = "validatopia_mdl"

    override val format
        get() = FORMAT

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val acceptAndroidKeyAttestation: Boolean get() = true

    override val offeredAfterIdentityProofing: Boolean get() = true

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Validatopia Driver Licence"

    // The wallet draws its own card art (NZ DISTF flash pass guidance).
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
        val licenceData = systemOfRecordData["driving_licence"]

        val dateOfBirth = coreData["birth_date"].asDateString
        val issueDate = coreData["issue_date"].asDateString
        val expiryDate = coreData["expiry_date"].asDateString
        val (validFrom, validUntil) = validatopiaValidity(coreData, timeZone)

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(DrivingLicense.MDL_NAMESPACE) {
                addDataElement("family_name", coreData["family_name"])
                addDataElement("given_name", coreData["given_name"])
                addDataElement("birth_date", dateOfBirth.toDataItemFullDate())
                addDataElement("issue_date", issueDate.toDataItemFullDate())
                addDataElement("expiry_date", expiryDate.toDataItemFullDate())
                addDataElement("issuing_country", ValidatopiaCredentials.ISSUING_COUNTRY.toDataItem())
                addDataElement("issuing_authority", coreData["issuing_authority"])
                addDataElement("document_number", licenceData["document_number"])
                addDataElement("portrait", coreData["portrait"])
                addDataElement(
                    "driving_privileges",
                    buildCborArray {
                        addCborMap {
                            put("vehicle_category_code", licenceData["vehicle_category_code"])
                            put("issue_date", issueDate.toDataItemFullDate())
                            put("expiry_date", expiryDate.toDataItemFullDate())
                        }
                    }
                )
                addDataElement("un_distinguishing_sign", ValidatopiaCredentials.ISSUING_COUNTRY.toDataItem())
                addDataElement("sex", coreData["sex"])
                addDataElement("nationality", coreData["nationality"])
                addDataElement(
                    "age_in_years",
                    Uint(dateOfBirth.yearsUntil(now.toLocalDateTime(timeZone).date).toULong())
                )
                addDataElement("age_birth_year", Uint(dateOfBirth.year.toULong()))
                for (age in listOf(18, 21)) {
                    addDataElement(
                        "age_over_$age",
                        if (isAgeOver(dateOfBirth, age, now, timeZone)) Simple.TRUE else Simple.FALSE
                    )
                }
            }
        }

        return signMdoc(
            docType = DrivingLicense.MDL_DOCTYPE,
            issuerNamespaces = issuerNamespaces,
            validFrom = validFrom,
            validUntil = validUntil,
            authenticationKey = authenticationKey!!,
            credentialId = credentialId,
        )
    }

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(systemOfRecordData, "credential_validatopia_mdl")

    companion object {
        private val FORMAT = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE)
    }
}
