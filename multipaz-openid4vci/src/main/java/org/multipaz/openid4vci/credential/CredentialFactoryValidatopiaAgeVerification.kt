package org.multipaz.openid4vci.credential

import kotlinx.datetime.TimeZone
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import kotlin.time.Clock

/**
 * [CredentialFactory] for Validatopia [AgeVerification] credentials in ISO mdoc format.
 *
 * Carries only `age_over_18` and `age_over_21`, derived from identity proofing's
 * `core.birth_date`; nothing else about the holder.
 */
class CredentialFactoryValidatopiaAgeVerification : CredentialFactory {
    override val configurationId: String
        get() = "validatopia_age_verification"

    override val scope: String
        get() = "validatopia_age_verification"

    override val format
        get() = FORMAT

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val acceptAndroidKeyAttestation: Boolean get() = true

    override val offeredAfterIdentityProofing: Boolean get() = true

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Validatopia Age Verification"

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
        val dateOfBirth = coreData["birth_date"].asDateString
        val (validFrom, validUntil) = validatopiaValidity(coreData, timeZone)

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(AgeVerification.AV_NAMESPACE) {
                for (age in AGE_THRESHOLDS) {
                    addDataElement(
                        "age_over_$age",
                        if (isAgeOver(dateOfBirth, age, now, timeZone)) Simple.TRUE else Simple.FALSE
                    )
                }
            }
        }

        return signMdoc(
            docType = AgeVerification.AV_DOCTYPE,
            issuerNamespaces = issuerNamespaces,
            validFrom = validFrom,
            validUntil = validUntil,
            authenticationKey = authenticationKey!!,
            credentialId = credentialId,
        )
    }

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(systemOfRecordData, "credential_validatopia_age_verification")

    companion object {
        private val AGE_THRESHOLDS = listOf(18, 21)
        private val FORMAT = CredentialFormat.Mdoc(AgeVerification.AV_DOCTYPE)
    }
}
