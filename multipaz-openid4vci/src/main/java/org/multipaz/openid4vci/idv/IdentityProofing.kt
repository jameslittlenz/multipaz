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

    /** The trusted CSCA certificates: the Validatopia Test CSCA plus any admin-uploaded ones. */
    suspend fun listTrustedCsca(): List<TrustedCscaInfo>

    /**
     * Parses and adds one or more concatenated PEM-encoded CSCA certificates to the trust store
     * used by [proof]'s passive authentication, and returns the full updated list.
     *
     * @throws org.multipaz.rpc.handler.InvalidRequestException if [pem] contains no valid certificate.
     */
    suspend fun uploadTrustedCsca(pem: String): List<TrustedCscaInfo>

    /** Removes an admin-uploaded CSCA certificate by [fingerprintSha256Hex]. The Test CSCA can't be removed this way. */
    suspend fun deleteTrustedCsca(fingerprintSha256Hex: String)

    /** The Validatopia Test CSCA certificate, PEM-encoded, for the admin site's "download" action. */
    suspend fun testCscaPem(): String

    /**
     * Replaces the persona store from an uploaded `personas.json` plus its referenced portrait
     * JPEGs (keyed by filename, as referenced by each persona's `portrait` field).
     *
     * @throws org.multipaz.rpc.handler.InvalidRequestException if validation fails (see
     *   `PersonaStore.fromJson`) or a referenced portrait is missing from [portraits].
     */
    suspend fun uploadPersonas(personasJson: String, portraits: Map<String, ByteArray>): List<PersonaSummary>
}

/** One trusted CSCA certificate, as shown on the admin site's "Trust" page. */
data class TrustedCscaInfo(
    val fingerprintSha256Hex: String,
    val subject: String,
    val notBeforeEpochSeconds: Long,
    val notAfterEpochSeconds: Long,
    /** `true` for the built-in Validatopia Test CSCA, which can't be deleted via [IdentityProofing.deleteTrustedCsca]. */
    val builtIn: Boolean,
)

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
