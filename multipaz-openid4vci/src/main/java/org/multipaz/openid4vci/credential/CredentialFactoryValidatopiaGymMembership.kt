package org.multipaz.openid4vci.credential

import kotlinx.datetime.TimeZone
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.utopia.knowntypes.Loyalty

/**
 * [CredentialFactory] for Validatopia gym membership cards, modelled on the [Loyalty] card type
 * in ISO mdoc format.
 *
 * Like [CredentialFactoryPhotoId], every claim comes from identity proofing's
 * `systemOfRecordData`: the holder's name and portrait from `core`, and the membership number and
 * tier from `gym_membership`. Nothing falls back to sample or placeholder data.
 */
class CredentialFactoryValidatopiaGymMembership : CredentialFactory {
    override val configurationId: String
        get() = "validatopia_gym_membership"

    override val scope: String
        get() = "validatopia_gym_membership"

    override val format
        get() = FORMAT

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val acceptAndroidKeyAttestation: Boolean get() = true

    override val offeredAfterIdentityProofing: Boolean get() = true

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Validatopia Gym Membership"

    // The wallet draws its own card art (NZ DISTF flash pass guidance).
    override val logo: String?
        get() = null

    override suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        val coreData = systemOfRecordData["core"]
        val membershipData = systemOfRecordData["gym_membership"]
        val (validFrom, validUntil) = validatopiaValidity(coreData, TimeZone.currentSystemDefault())

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(Loyalty.LOYALTY_NAMESPACE) {
                addDataElement("family_name", coreData["family_name"])
                addDataElement("given_name", coreData["given_name"])
                addDataElement("portrait", coreData["portrait"])
                addDataElement("membership_number", membershipData["membership_number"])
                addDataElement("tier", membershipData["tier"])
                addDataElement("issue_date", coreData["issue_date"].asDateString.toDataItemFullDate())
                addDataElement("expiry_date", coreData["expiry_date"].asDateString.toDataItemFullDate())
            }
        }

        return signMdoc(
            docType = Loyalty.LOYALTY_DOCTYPE,
            issuerNamespaces = issuerNamespaces,
            validFrom = validFrom,
            validUntil = validUntil,
            authenticationKey = authenticationKey!!,
            credentialId = credentialId,
        )
    }

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(systemOfRecordData, "credential_validatopia_gym_membership")

    companion object {
        private val FORMAT = CredentialFormat.Mdoc(Loyalty.LOYALTY_DOCTYPE)
    }
}
