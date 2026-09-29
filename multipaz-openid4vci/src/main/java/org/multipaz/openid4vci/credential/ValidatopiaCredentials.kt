package org.multipaz.openid4vci.credential

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The credentials the Validatopia issuer offers once identity proofing succeeds, in the order the
 * wallet receives them: the Photo ID first, then the documents derived from the same proofing.
 */
object ValidatopiaCredentials {
    /** Creates the factories for every Validatopia credential. */
    fun createFactories(): List<CredentialFactory> = listOf(
        CredentialFactoryPhotoId(),
        CredentialFactoryValidatopiaMdl(),
        CredentialFactoryValidatopiaGymMembership(),
        CredentialFactoryValidatopiaAgeVerification(),
    )

    /** The Validatopia issuer's own `issuing_country`, a user-assigned ISO 3166-1 code. */
    internal const val ISSUING_COUNTRY = "XV"
}

/**
 * The validity period shared by every Validatopia credential: from the `core.issue_date` to the
 * `core.expiry_date` that identity proofing set (the earlier of the passport's expiry and the
 * configured Photo ID validity).
 */
internal fun validatopiaValidity(coreData: DataItem, timeZone: TimeZone): Pair<Instant, Instant> =
    Pair(
        coreData["issue_date"].asDateString.atStartOfDayIn(timeZone),
        coreData["expiry_date"].asDateString.atStartOfDayIn(timeZone),
    )

/**
 * Whether someone born on [dateOfBirth] is at least [age] years old at [now]. Calculated purely on
 * the calendar date, not the time zone of birth.
 */
internal fun isAgeOver(dateOfBirth: LocalDate, age: Int, now: Instant, timeZone: TimeZone): Boolean =
    now > dateOfBirth.atStartOfDayIn(timeZone).plus(age, DateTimeUnit.YEAR, timeZone)

/**
 * Signs [issuerNamespaces] as an ISO mdoc of [docType] bound to [authenticationKey], with a
 * status-list revocation entry for [credentialId].
 */
internal suspend fun CredentialFactory.signMdoc(
    docType: String,
    issuerNamespaces: IssuerNamespaces,
    validFrom: Instant,
    validUntil: Instant,
    authenticationKey: EcPublicKey,
    credentialId: CredentialId,
): MintedCredential {
    // Make sure to not use fractional seconds as 18013-5 calls for this (clauses 7.1
    // and 9.1.2.4).
    val timeSigned = Instant.fromEpochSeconds(Clock.System.now().epochSeconds, 0)

    val baseUrl = BackendEnvironment.getBaseUrl()
    val revocationStatus = RevocationStatus.StatusList(
        idx = credentialId.index,
        uri = "$baseUrl/status_list/${credentialId.bucket}",
        certificate = null
    )

    val mso = MobileSecurityObject(
        version = "1.0",
        docType = docType,
        signedAt = timeSigned,
        validFrom = validFrom,
        validUntil = validUntil,
        expectedUpdate = null,
        digestAlgorithm = Algorithm.SHA256,
        valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
        deviceKey = authenticationKey,
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
