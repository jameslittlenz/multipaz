package org.multipaz.samples.validatopia.shared.trust

import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.pa.CscaStore
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.trustmanagement.ConfigurableTrustManager
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.trustmanagement.TrustMetadata

/**
 * The bundled trust stores for the Validatopia apps, all built from [ValidatopiaTestPki] and all
 * marked TEST ([TrustMetadata.testOnly]).
 *
 * Importing ICAO master lists is not supported; the only CSCA is the Validatopia Test CSCA.
 */
object ValidatopiaTrust {
    /** Display name of the Photo ID issuer, as shown in the verifier's "Credential issuer" panel. */
    const val ISSUER_DISPLAY_NAME = "Validatopia Photo ID issuer"

    /** Display name of the passport issuer, as shown in the verifier's "Passport issuer (CSCA)" panel. */
    const val CSCA_DISPLAY_NAME = "Validatopia Test CSCA"

    /** Display name the wallet's consent sheet shows for requests signed by the Validatopia verifier. */
    const val VERIFIER_DISPLAY_NAME = "Validatopia Verify"

    val iacaCertificate: X509Cert by lazy { X509Cert.fromPem(ValidatopiaTestPki.IACA_PEM) }

    val testCscaCertificate: X509Cert by lazy { X509Cert.fromPem(ValidatopiaTestPki.TEST_CSCA_PEM) }

    val readerRootCertificate: X509Cert by lazy { X509Cert.fromPem(ValidatopiaTestPki.READER_ROOT_PEM) }

    /** Trust manager for Photo ID issuer certificate chains (verifier side). */
    fun createIssuerTrustManager(): TrustManagerInterface = ConfigurableTrustManager(
        identifier = "validatopia_issuers",
        entries = listOf(
            TrustEntryX509Cert(
                identifier = "validatopia_iaca",
                metadata = TrustMetadata(displayName = ISSUER_DISPLAY_NAME, testOnly = true),
                certificate = iacaCertificate,
            )
        )
    )

    /** Trust manager for reader-authentication certificate chains (wallet side). */
    fun createReaderTrustManager(): TrustManagerInterface = ConfigurableTrustManager(
        identifier = "validatopia_readers",
        entries = listOf(
            TrustEntryX509Cert(
                identifier = "validatopia_reader_root",
                metadata = TrustMetadata(displayName = VERIFIER_DISPLAY_NAME, testOnly = true),
                certificate = readerRootCertificate,
            )
        )
    )

    /** The CSCA store used for cross-border passive authentication (verifier side). */
    fun createCscaStore(): CscaStore = CscaStore.from(listOf(testCscaCertificate))

    /**
     * Resolves who is asking, for the wallet's consent sheet: the first of [requester]'s
     * reader-authentication chains that [readerTrustManager] trusts, or `null` if none is trusted
     * (the consent sheet then says the requester is unknown).
     */
    suspend fun resolveRequester(
        requester: Requester,
        readerTrustManager: TrustManagerInterface,
    ): TrustedRequesterIdentity? {
        for (identity in requester.requesterIdentities) {
            val result = readerTrustManager.verify(identity.certChain.certificates)
            val metadata = result.trustPoints.firstOrNull()?.metadata
            if (result.isTrusted && metadata != null) {
                return TrustedRequesterIdentity(identity, metadata)
            }
        }
        return null
    }

    /**
     * The verifier's reader-authentication key: a TEST key shipped in the app, so it identifies
     * "a Validatopia Verify build", not a particular device or operator.
     *
     * @throws IllegalArgumentException if the bundled key is malformed.
     */
    @Throws(IllegalArgumentException::class)
    fun readerKey(): AsymmetricKey.X509Certified =
        AsymmetricKey.parseExplicit(ValidatopiaTestPki.READER_KEY_JSON.trim()) as AsymmetricKey.X509Certified
}
