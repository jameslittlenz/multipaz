package org.multipaz.samples.validatopia.shared.crossborder

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.idv.IcaoCountryCodes
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.lds.LdsException
import org.multipaz.idv.mrz.Mrz
import org.multipaz.idv.mrz.MrzException
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.mrz.MrzTd3
import org.multipaz.idv.pa.CscaStore
import org.multipaz.idv.pa.PassiveAuthenticationFlag
import org.multipaz.idv.pa.PassiveAuthenticationResult
import org.multipaz.idv.pa.PassiveAuthenticator
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One field compared between the Photo ID's claims and the passport's DG1 MRZ.
 *
 * @property label human-readable field name.
 * @property credentialValue the value from the Photo ID's claims, or `null` if it wasn't shared.
 * @property passportValue the value from DG1.
 */
data class FieldComparison(
    val label: String,
    val credentialValue: String?,
    val passportValue: String,
) {
    /** `true` if both are present and equal, `false` if they differ, `null` if the claim wasn't shared. */
    val matches: Boolean? get() = credentialValue?.let { normalize(it) == normalize(passportValue) }

    private fun normalize(value: String) = value.trim().uppercase().replace(Regex("\\s+"), " ")
}

/**
 * The Photo ID's claims that the passport check compares against DG1, already rendered as
 * strings. Each is `null` if it wasn't shared.
 */
data class PhotoIdClaimsForPassportCheck(
    val familyName: String?,
    val givenName: String?,
    val birthDate: LocalDate?,
    val sex: Int?,
    val nationalityAlpha2: String?,
    val travelDocumentNumber: String?,
)

/**
 * The outcome of checking the passport data groups carried in a Photo ID
 * (`docs/validatopia/PLAN.md`, use case 5).
 *
 * @property passiveAuthentication the passive-authentication result for the SOD, DG1 and DG2.
 * @property cscaSubject subject of the CSCA the Document Signer chains to, if one was found.
 * @property mrz the parsed DG1 MRZ, or `null` if DG1 couldn't be parsed.
 * @property mrzError why DG1 couldn't be parsed, if it couldn't.
 * @property comparisons DG1 fields compared against the Photo ID's claims.
 * @property faceImage the DG2 face image (JPEG or JPEG 2000), or `null` if DG2 couldn't be parsed.
 * @property passportExpired whether the passport itself has expired.
 */
data class PassportCheckResult(
    val passiveAuthentication: PassiveAuthenticationResult,
    val cscaSubject: String?,
    val mrz: MrzTd3?,
    val mrzError: String?,
    val comparisons: List<FieldComparison>,
    val faceImage: ByteArray?,
    val passportExpired: Boolean,
) {
    /** The passport data is authentic: signed by a trusted CSCA, with DG1 and DG2 hashes matching. */
    val authentic: Boolean
        get() = passiveAuthentication.trusted &&
            passiveAuthentication.dataGroupHashMatches[1] == true &&
            passiveAuthentication.dataGroupHashMatches[2] == true

    /** Every shared claim that has a DG1 counterpart agrees with it. */
    val claimsMatch: Boolean
        get() = mrz != null && comparisons.none { it.matches == false }
}

/** Passive authentication plus DG1↔claims checking for the cross-border use case. */
object PassportCheck {
    /**
     * Checks the passport data carried in a Photo ID.
     *
     * @param sod the `sod` data element.
     * @param dg1 the `dg1` data element.
     * @param dg2 the `dg2` data element.
     * @param claims the Photo ID claims to compare against DG1.
     * @param cscaStore the trusted CSCAs.
     * @param at the time to check validity at.
     */
    suspend fun check(
        sod: ByteArray,
        dg1: ByteArray,
        dg2: ByteArray,
        claims: PhotoIdClaimsForPassportCheck,
        cscaStore: CscaStore,
        at: Instant = Clock.System.now(),
    ): PassportCheckResult {
        val passiveAuthentication = PassiveAuthenticator.authenticate(
            sod = sod,
            dataGroups = mapOf(1 to dg1, 2 to dg2),
            cscaStore = cscaStore,
            at = at,
        )
        val cscaSubject = passiveAuthentication.documentSignerCertificate
            ?.takeUnless { PassiveAuthenticationFlag.UNTRUSTED_CSCA in passiveAuthentication.flags }
            ?.issuer?.name

        var mrzError: String? = null
        val mrz = try {
            Mrz.parseTd3(Lds.parseDG1(dg1))
        } catch (e: LdsException) {
            mrzError = e.message
            null
        } catch (e: MrzException) {
            mrzError = e.message
            null
        } catch (e: IllegalArgumentException) {
            // Malformed ASN.1 from the holder's device: report it, don't abort the whole check.
            mrzError = e.message
            null
        }
        val faceImage = try {
            Lds.parseDG2(dg2)
        } catch (e: LdsException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        val comparisons = mrz?.let { compare(it, claims) } ?: emptyList()
        val today = at.toLocalDateTime(TimeZone.UTC).date
        return PassportCheckResult(
            passiveAuthentication = passiveAuthentication,
            cscaSubject = cscaSubject,
            mrz = mrz,
            mrzError = mrzError,
            comparisons = comparisons,
            faceImage = faceImage,
            passportExpired = mrz != null && mrz.expiryDate < today,
        )
    }

    private fun compare(mrz: MrzTd3, claims: PhotoIdClaimsForPassportCheck): List<FieldComparison> = listOf(
        FieldComparison("Family name", claims.familyName, mrz.primaryIdentifier),
        FieldComparison("Given names", claims.givenName, mrz.secondaryIdentifier),
        FieldComparison("Date of birth", claims.birthDate?.toString(), mrz.birthDate.toString()),
        FieldComparison("Sex", claims.sex?.let { sexLabel(it) }, sexLabel(mrz.sex)),
        FieldComparison(
            "Nationality",
            claims.nationalityAlpha2,
            IcaoCountryCodes.toAlpha2(mrz.nationality) ?: mrz.nationality
        ),
        FieldComparison("Passport number", claims.travelDocumentNumber, mrz.documentNumber),
    )

    /** ISO/IEC 5218 sex code, as used by the Photo ID `sex` element, rendered for display. */
    fun sexLabel(code: Int): String = when (code) {
        1 -> "Male"
        2 -> "Female"
        9 -> "Not applicable"
        else -> "Not specified"
    }

    private fun sexLabel(sex: MrzSex): String = when (sex) {
        MrzSex.MALE -> sexLabel(1)
        MrzSex.FEMALE -> sexLabel(2)
        MrzSex.UNSPECIFIED -> sexLabel(0)
    }
}
