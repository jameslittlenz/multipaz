package org.multipaz.openid4vci.idv

/**
 * Identity-proofing backend for the Validatopia issuer: verifies a passport's chip data (or a
 * dummy persona) and produces system-of-record data ready for `CredentialFactoryPhotoId` to mint.
 *
 * The implementation (`PassportIdentityProofing` in `multipaz-idv-backend`) depends on
 * `multipaz-idv` and a face-matching library; this interface keeps those dependencies out of
 * `multipaz-openid4vci`, which other, non-Validatopia servers also use. An implementation is
 * injected into [org.multipaz.rpc.backend.BackendEnvironment] by the server's `Main`.
 *
 * See `docs/validatopia/PLAN.md`'s Component C and D.
 */
interface IdentityProofing {
    /** Verifies [evidence] (passive authentication + face match) and returns the outcome. */
    suspend fun proof(evidence: PassportEvidence): IdvResult

    /** Whether dummy-persona issuance is currently enabled. */
    suspend fun dummyIssuanceEnabled(): Boolean

    /** The dummy personas available for issuance, or an empty list if disabled. */
    suspend fun listPersonas(): List<PersonaSummary>

    /**
     * Issues system-of-record data for the dummy persona with the given id.
     *
     * @throws org.multipaz.rpc.handler.InvalidRequestException if [personaId] is unknown or
     *   dummy issuance is disabled.
     */
    suspend fun proofPersona(personaId: String): IdvResult

    /** The current admin-editable settings. */
    suspend fun getSettings(): IdvSettingsData

    /** Updates the admin-editable settings and returns the new values. */
    suspend fun updateSettings(settings: IdvSettingsData): IdvSettingsData

    /** Lists issuance audit entries in insertion order, most recently inserted last. */
    suspend fun listAudit(afterId: String?, limit: Int): List<IdvAuditEntry>
}

/** A dummy persona available for issuance via `/idv/persona`. */
data class PersonaSummary(
    val id: String,
    val givenName: String,
    val familyName: String,
)

/**
 * Admin-editable Validatopia IDV settings (see `docs/validatopia/PLAN.md`'s Component C).
 *
 * Rate limiting is deferred to M3's nginx `limit_req` rules and admin-account hardening, so it
 * isn't represented here yet.
 */
data class IdvSettingsData(
    val faceMatchThreshold: Double,
    val requireActiveAuth: Boolean,
    val acceptUntrustedCsca: Boolean,
    val offerTtlSeconds: Long,
    val photoIdValidityDays: Long,
    val dataRetentionDays: Long,
    val dummyIssuanceEnabled: Boolean,
)

/** One row of the issuance audit log; never contains the selfie. */
data class IdvAuditEntry(
    val id: String,
    val timestampEpochSeconds: Long,
    val method: String,
    val accepted: Boolean,
    val nationality: String?,
    val maskedDocumentNumber: String?,
    val faceScore: Double?,
    val flags: List<String>,
    val sessionId: String?,
)
