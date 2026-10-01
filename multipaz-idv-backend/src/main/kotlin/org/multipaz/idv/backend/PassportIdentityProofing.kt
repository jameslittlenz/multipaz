package org.multipaz.idv.backend

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.IcaoCountryCodes
import org.multipaz.idv.backend.audit.AdminAuditRecord
import org.multipaz.idv.backend.audit.IssuanceAuditRecord
import org.multipaz.idv.backend.audit.IssuanceMethod
import org.multipaz.idv.backend.audit.toEntry
import org.multipaz.idv.backend.csca.UploadedCscaStore
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.backend.face.FaceMatcher
import org.multipaz.idv.backend.image.Jp2Decoder
import org.multipaz.idv.backend.image.Jp2DecoderException
import org.multipaz.idv.backend.persona.PersonaStore
import org.multipaz.idv.backend.persona.PersonaStorePersistence
import org.multipaz.idv.backend.settings.IdvSettingsRecord
import org.multipaz.idv.backend.settings.toData
import org.multipaz.idv.backend.settings.toRecord
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.lds.LdsException
import org.multipaz.idv.mrz.Mrz
import org.multipaz.idv.mrz.MrzException
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.mrz.MrzTd3
import org.multipaz.idv.pa.CscaStore
import org.multipaz.idv.pa.IcaoCscaCertificates
import org.multipaz.idv.pa.PassiveAuthenticationFlag
import org.multipaz.idv.pa.PassiveAuthenticator
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.openid4vci.idv.IdvAuditEntry
import org.multipaz.openid4vci.idv.IdvResult
import org.multipaz.openid4vci.idv.IdvSettingsData
import org.multipaz.openid4vci.idv.PassportEvidence
import org.multipaz.openid4vci.idv.PersonaSummary
import org.multipaz.openid4vci.idv.TrustedCscaInfo
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.toBase64Url
import kotlin.time.Clock

/**
 * The Validatopia [IdentityProofing] implementation: passive authentication (via `multipaz-idv`)
 * plus server-side face matching, for both the real passport path and dummy personas.
 *
 * See `docs/validatopia/PLAN.md`'s Component C and D.
 */
class PassportIdentityProofing(
    private val faceMatcher: FaceMatcher,
    private val testCsca: ValidatopiaTestCsca,
    private val personaStore: PersonaStore,
) : IdentityProofing {

    override suspend fun proof(evidence: PassportEvidence): IdvResult {
        val settings = IdvSettingsRecord.get()
        val sod = evidence.sod.toByteArray()
        val dg1 = evidence.dg1.toByteArray()
        val dg2 = evidence.dg2.toByteArray()

        val paResult = PassiveAuthenticator.authenticate(
            sod = sod,
            dataGroups = mapOf(1 to dg1, 2 to dg2),
            cscaStore = combinedCscaStore(),
        )
        val flags = mutableListOf<String>()
        flags.addAll(paResult.flags.map { it.name })

        // Demo mode: accept an otherwise-fully-verified SOD whose only concern is that its CSCA
        // isn't in the trust store (the flag is still recorded).
        val cscaOk = paResult.trusted ||
            (settings.acceptUntrustedCsca && paResult.flags == setOf(PassiveAuthenticationFlag.UNTRUSTED_CSCA))

        if (settings.requireActiveAuth) {
            // Active Authentication verification was deferred in M1 (aa/ActiveAuthVerifier.kt was
            // never built). Fail closed rather than silently skip a check an admin explicitly
            // asked for.
            flags.add("ACTIVE_AUTH_NOT_IMPLEMENTED")
        }

        val mrz = try {
            Mrz.parseTd3(Lds.parseDG1(dg1))
        } catch (e: Exception) {
            when (e) {
                is MrzException, is LdsException -> {
                    flags.add("MALFORMED_DG1")
                    return reject(flags, sessionId = evidence.sessionId, nationality = null, documentNumber = null)
                }
                else -> throw e
            }
        }

        val portraitJpeg = try {
            Jp2Decoder.toJpeg(Lds.parseDG2(dg2))
        } catch (e: Exception) {
            when (e) {
                is LdsException, is Jp2DecoderException -> {
                    flags.add("PORTRAIT_UNAVAILABLE")
                    return reject(flags, evidence.sessionId, mrz)
                }
                else -> throw e
            }
        }

        val faceScore = faceMatcher.score(evidence.selfie.toByteArray(), portraitJpeg)
        val faceOk = faceScore >= settings.faceMatchThreshold
        if (!faceOk) {
            flags.add("FACE_MATCH_BELOW_THRESHOLD")
        }

        val today = todayUtc()
        val expired = mrz.expiryDate < today
        if (expired) {
            flags.add("DOCUMENT_EXPIRED")
        }

        val accepted = cscaOk && faceOk && !expired && !settings.requireActiveAuth

        val systemOfRecordData = if (accepted) {
            buildSystemOfRecordData(
                mrz = mrz,
                portraitJpeg = portraitJpeg,
                sod = sod,
                dg1 = dg1,
                dg2 = dg2,
                issuingAuthority = "Validatopia Issuer",
                settings = settings,
                today = today,
            )
        } else {
            null
        }

        recordAudit(
            method = IssuanceMethod.PASSPORT,
            accepted = accepted,
            nationality = IcaoCountryCodes.toAlpha2(mrz.nationality),
            documentNumber = mrz.documentNumber,
            faceScore = faceScore,
            flags = flags,
            sessionId = evidence.sessionId,
        )

        return IdvResult(accepted, flags, faceScore, systemOfRecordData)
    }

    override suspend fun dummyIssuanceEnabled(): Boolean = IdvSettingsRecord.get().dummyIssuanceEnabled

    override suspend fun listPersonas(): List<PersonaSummary> {
        if (!dummyIssuanceEnabled()) {
            return emptyList()
        }
        return activePersonaStore().list().map { PersonaSummary(it.id, it.givenName, it.familyName) }
    }

    override suspend fun proofPersona(personaId: String): IdvResult {
        val settings = IdvSettingsRecord.get()
        if (!settings.dummyIssuanceEnabled) {
            throw InvalidRequestException("Dummy issuance is disabled")
        }
        val store = activePersonaStore()
        val persona = store.find(personaId)
            ?: throw InvalidRequestException("Unknown persona '$personaId'")

        val passport = SyntheticPassportFactory.createPassport(
            documentSignerCertificate = testCsca.documentSignerCertificate,
            documentSignerPrivateKey = testCsca.documentSignerPrivateKey,
            documentSignerSignatureAlgorithm = testCsca.signatureAlgorithm,
            // The passport's own issuing state/nationality come from the persona (e.g. a persona
            // can represent an NZL passport holder); this is independent of who issues the Photo
            // ID itself, which stays Validatopia regardless (see issuingAuthority below and
            // CredentialFactoryPhotoId's fixed issuing_country). The Document Signer is always
            // the Validatopia Test CSCA — there's no real NZL/AUS CSCA in this synthetic sandbox.
            issuingState = persona.nationality,
            primaryIdentifier = persona.familyName,
            secondaryIdentifier = persona.givenName,
            documentNumber = persona.documentNumber,
            nationality = persona.nationality,
            birthDate = persona.birthDate,
            sex = isoSexToMrzSex(persona.sex),
            expiryDate = persona.expiryDate,
            portraitBytes = store.portraitFor(persona),
        )

        val today = todayUtc()
        val systemOfRecordData = buildSystemOfRecordData(
            mrz = passport.mrz,
            portraitJpeg = Jp2Decoder.toJpeg(store.portraitFor(persona)),
            sod = passport.sod,
            dg1 = passport.dg1,
            dg2 = passport.dg2,
            issuingAuthority = "Validatopia Test Issuance",
            settings = settings,
            today = today,
        )

        recordAudit(
            method = IssuanceMethod.PERSONA,
            accepted = true,
            nationality = IcaoCountryCodes.toAlpha2(persona.nationality),
            documentNumber = persona.documentNumber,
            faceScore = null,
            flags = emptyList(),
            sessionId = personaId,
        )

        return IdvResult(accepted = true, flags = emptyList(), faceScore = null, systemOfRecordData = systemOfRecordData)
    }

    override suspend fun getSettings(): IdvSettingsData = IdvSettingsRecord.get().toData()

    override suspend fun updateSettings(settings: IdvSettingsData): IdvSettingsData {
        val saved = IdvSettingsRecord.update(settings.toRecord()).toData()
        AdminAuditRecord.record(action = "settings_updated", detail = saved.toString())
        return saved
    }

    override suspend fun listAudit(afterId: String?, limit: Int): List<IdvAuditEntry> =
        IssuanceAuditRecord.list(afterId, limit).map { (id, record) -> record.toEntry(id) }

    override suspend fun listTrustedCsca(): List<TrustedCscaInfo> =
        builtInCscas().map { UploadedCscaStore.toInfo(it, builtIn = true) } +
            UploadedCscaStore.list().map { UploadedCscaStore.toInfo(it, builtIn = false) }

    override suspend fun uploadTrustedCsca(pem: String): List<TrustedCscaInfo> {
        UploadedCscaStore.add(pem)
        return listTrustedCsca()
    }

    override suspend fun deleteTrustedCsca(fingerprintSha256Hex: String) {
        if (builtInCscas().any { fingerprintSha256Hex.equals(UploadedCscaStore.fingerprint(it), ignoreCase = true) }) {
            throw InvalidRequestException("Built-in CSCAs can't be deleted")
        }
        UploadedCscaStore.delete(fingerprintSha256Hex)
    }

    override suspend fun testCscaPem(): String = testCsca.cscaCertificate.toPem()

    override suspend fun uploadPersonas(personasJson: String, portraits: Map<String, ByteArray>): List<PersonaSummary> {
        val store = PersonaStorePersistence.save(personasJson, portraits)
        return store.list().map { PersonaSummary(it.id, it.givenName, it.familyName) }
    }

    /** The Validatopia Test CSCA and the real passport CSCAs in [IcaoCscaCertificates]. */
    private fun builtInCscas(): List<X509Cert> = listOf(testCsca.cscaCertificate) + IcaoCscaCertificates.certificates

    private suspend fun combinedCscaStore(): CscaStore = CscaStore.from(builtInCscas() + UploadedCscaStore.list())

    private suspend fun activePersonaStore(): PersonaStore = PersonaStorePersistence.load() ?: personaStore

    private suspend fun reject(
        flags: List<String>,
        sessionId: String,
        mrz: MrzTd3,
    ): IdvResult = reject(flags, sessionId, IcaoCountryCodes.toAlpha2(mrz.nationality), mrz.documentNumber)

    private suspend fun reject(
        flags: List<String>,
        sessionId: String,
        nationality: String?,
        documentNumber: String?,
    ): IdvResult {
        recordAudit(
            method = IssuanceMethod.PASSPORT,
            accepted = false,
            nationality = nationality,
            documentNumber = documentNumber,
            faceScore = null,
            flags = flags,
            sessionId = sessionId,
        )
        return IdvResult(accepted = false, flags = flags, faceScore = null, systemOfRecordData = null)
    }

    private suspend fun recordAudit(
        method: IssuanceMethod,
        accepted: Boolean,
        nationality: String?,
        documentNumber: String?,
        faceScore: Double?,
        flags: List<String>,
        sessionId: String?,
    ) {
        IssuanceAuditRecord.record(
            IssuanceAuditRecord(
                timestamp = Clock.System.now(),
                method = method.name,
                accepted = accepted,
                nationality = nationality,
                maskedDocumentNumber = documentNumber?.let { IssuanceAuditRecord.mask(it) },
                faceScore = faceScore,
                flags = flags,
                sessionId = sessionId,
            )
        )
    }

    private fun buildSystemOfRecordData(
        mrz: MrzTd3,
        portraitJpeg: ByteArray,
        sod: ByteArray,
        dg1: ByteArray,
        dg2: ByteArray,
        issuingAuthority: String,
        settings: IdvSettingsRecord,
        today: LocalDate,
    ): DataItem {
        val nationality = IcaoCountryCodes.toAlpha2(mrz.nationality) ?: UNKNOWN_COUNTRY
        val computedExpiry = today.plus(settings.photoIdValidityDays.toInt(), DateTimeUnit.DAY)
        val expiryDate = if (mrz.expiryDate < computedExpiry) mrz.expiryDate else computedExpiry
        return buildCborMap {
            putCborMap("core") {
                put("given_name", mrz.secondaryIdentifier)
                put("family_name", mrz.primaryIdentifier)
                put("birth_date", mrz.birthDate.toDataItemFullDate())
                put("sex", mrzSexToIsoSex(mrz.sex))
                put("nationality", nationality)
                put("document_number", generateDocumentNumber())
                put("issue_date", today.toDataItemFullDate())
                put("expiry_date", expiryDate.toDataItemFullDate())
                put("issuing_authority", issuingAuthority)
                put("portrait", portraitJpeg)
                put("travel_document_type", "P")
                put("travel_document_number", mrz.documentNumber)
                put("travel_document_mrz", mrz.raw)
            }
            putCborMap("datagroups") {
                put("version", "1.0")
                put("sod", sod)
                put("dg1", dg1)
                put("dg2", dg2)
            }
            // The other documents issued alongside the Photo ID, from the same proofing.
            putCborMap("driving_licence") {
                put("document_number", generateDocumentNumber("VDL"))
                put("vehicle_category_code", "B")
            }
            putCborMap("gym_membership") {
                put("membership_number", generateMembershipNumber())
                put("tier", "basic")
            }
        }
    }

    private fun generateDocumentNumber(prefix: String = "VPI"): String =
        prefix + Crypto.secureRandom.nextBytes(6).toBase64Url().filter { it.isLetterOrDigit() }.take(8).uppercase()

    private fun generateMembershipNumber(): String =
        (10_000_000 + Crypto.secureRandom.nextInt(90_000_000)).toString()

    private fun mrzSexToIsoSex(sex: MrzSex): Int = when (sex) {
        MrzSex.MALE -> 1
        MrzSex.FEMALE -> 2
        MrzSex.UNSPECIFIED -> 0
    }

    private fun isoSexToMrzSex(sex: Int): MrzSex = when (sex) {
        1 -> MrzSex.MALE
        2 -> MrzSex.FEMALE
        else -> MrzSex.UNSPECIFIED
    }

    companion object {
        private const val UNKNOWN_COUNTRY = "XX"

        private fun todayUtc(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
    }
}
