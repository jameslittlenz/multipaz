package org.multipaz.idv.pa

import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509CertChainValidationException
import org.multipaz.idv.cms.CmsException
import org.multipaz.idv.cms.SignedData
import org.multipaz.idv.lds.LdsSecurityObject
import kotlin.time.Clock
import kotlin.time.Instant

/** A concern flagged by [PassiveAuthenticator.authenticate]. Any flag means the SOD isn't fully trusted. */
enum class PassiveAuthenticationFlag {
    /** The `EF.SOD` itself couldn't be parsed as a CMS `SignedData`, or is missing required fields. */
    MALFORMED_SOD,

    /** The Document Signer certificate's signature algorithm can't be checked on this platform (e.g. brainpool on iOS). */
    ALGORITHM_UNSUPPORTED_ON_DEVICE,

    /** The `SignedData` signature doesn't verify against the Document Signer's public key. */
    SIGNATURE_INVALID,

    /** No CSCA in the trust store has a subject matching the Document Signer's issuer. */
    UNTRUSTED_CSCA,

    /** A CSCA candidate exists, but the Document Signer -> CSCA chain doesn't validate. */
    CHAIN_INVALID,

    /** The Document Signer certificate is expired or not yet valid at the check time. */
    DOCUMENT_SIGNER_VALIDITY,

    /** A supplied data group's hash doesn't match the `LDSSecurityObject`'s recorded hash for it. */
    HASH_MISMATCH,
}

/**
 * The result of [PassiveAuthenticator.authenticate].
 *
 * @property trusted `true` iff [flags] is empty and every data group in [dataGroupHashMatches] matched.
 * @property flags the concerns found; empty means full passive authentication succeeded.
 * @property dataGroupHashMatches for each data group number passed to [PassiveAuthenticator.authenticate],
 *   whether its hash matched the `LDSSecurityObject`'s recorded value for it.
 * @property documentSignerCertificate the Document Signer certificate from the SOD, if it parsed.
 */
data class PassiveAuthenticationResult(
    val trusted: Boolean,
    val flags: Set<PassiveAuthenticationFlag>,
    val dataGroupHashMatches: Map<Int, Boolean>,
    val documentSignerCertificate: X509Cert?,
)

/**
 * Passive authentication (ICAO 9303-11 Section 4.1): checks that a passport's `EF.SOD` is signed by
 * a Document Signer that chains to a trusted CSCA, and that the supplied data groups' hashes match
 * what the SOD's `LDSSecurityObject` records for them.
 */
object PassiveAuthenticator {
    /**
     * @param sod the raw bytes of `EF.SOD`.
     * @param dataGroups the raw bytes of each data group to check, keyed by data group number
     *   (e.g. `1` for DG1, `2` for DG2).
     * @param cscaStore the trusted CSCA certificates to validate the Document Signer against.
     * @param at the time to validate certificate validity periods at.
     * @param supportedCurves the EC curves this platform's [Crypto] can verify signatures for.
     *   Defaults to [Crypto.supportedCurves]; a narrower set can be injected in tests to exercise
     *   [PassiveAuthenticationFlag.ALGORITHM_UNSUPPORTED_ON_DEVICE] deterministically on any host.
     */
    suspend fun authenticate(
        sod: ByteArray,
        dataGroups: Map<Int, ByteArray>,
        cscaStore: CscaStore,
        at: Instant = Clock.System.now(),
        supportedCurves: Set<EcCurve> = Crypto.supportedCurves,
    ): PassiveAuthenticationResult {
        val flags = mutableSetOf<PassiveAuthenticationFlag>()

        val signedData = try {
            SignedData.parse(sod)
        } catch (e: CmsException) {
            return PassiveAuthenticationResult(
                trusted = false,
                flags = setOf(PassiveAuthenticationFlag.MALFORMED_SOD),
                dataGroupHashMatches = emptyMap(),
                documentSignerCertificate = null,
            )
        }

        val digestAlgorithm = SignedData.hashAlgorithmFromOid(signedData.digestAlgorithmOid)
        if (digestAlgorithm == null) {
            flags.add(PassiveAuthenticationFlag.ALGORITHM_UNSUPPORTED_ON_DEVICE)
        } else if (!Crypto.digest(digestAlgorithm, signedData.eContent).contentEquals(signedData.messageDigest)) {
            flags.add(PassiveAuthenticationFlag.MALFORMED_SOD)
        }

        val documentSignerCertificate = signedData.certificates.firstOrNull()
        if (documentSignerCertificate == null) {
            flags.add(PassiveAuthenticationFlag.MALFORMED_SOD)
        } else {
            if (at < documentSignerCertificate.validityNotBefore || at > documentSignerCertificate.validityNotAfter) {
                flags.add(PassiveAuthenticationFlag.DOCUMENT_SIGNER_VALIDITY)
            }

            if (!isSupported(documentSignerCertificate, supportedCurves)) {
                flags.add(PassiveAuthenticationFlag.ALGORITHM_UNSUPPORTED_ON_DEVICE)
            } else {
                try {
                    signedData.verifySignature(documentSignerCertificate.publicKey)
                } catch (e: SignatureVerificationException) {
                    flags.add(PassiveAuthenticationFlag.SIGNATURE_INVALID)
                }
            }

            val cscaCandidates = cscaStore.findBySubject(documentSignerCertificate.issuer)
            if (cscaCandidates.isEmpty()) {
                flags.add(PassiveAuthenticationFlag.UNTRUSTED_CSCA)
            } else {
                val supportedCandidates = cscaCandidates.filter { isSupported(it, supportedCurves) }
                if (supportedCandidates.isEmpty()) {
                    flags.add(PassiveAuthenticationFlag.ALGORITHM_UNSUPPORTED_ON_DEVICE)
                } else {
                    var chainValidated = false
                    for (csca in supportedCandidates) {
                        try {
                            // The Document Signer's own validity is already reflected in
                            // DOCUMENT_SIGNER_VALIDITY above; don't have it also surface as
                            // CHAIN_INVALID here.
                            X509CertChain(listOf(documentSignerCertificate, csca))
                                .validate(validateAt = at, validateValidity = false)
                            chainValidated = true
                            break
                        } catch (e: X509CertChainValidationException) {
                            // Try the next candidate with the same subject, if any.
                        }
                    }
                    if (!chainValidated) {
                        flags.add(PassiveAuthenticationFlag.CHAIN_INVALID)
                    }
                }
            }
        }

        val ldsSecurityObject = if (digestAlgorithm != null) {
            try {
                LdsSecurityObject.parse(signedData.eContent)
            } catch (e: CmsException) {
                flags.add(PassiveAuthenticationFlag.MALFORMED_SOD)
                null
            }
        } else {
            null
        }

        val dataGroupHashMatches = dataGroups.mapValues { (number, bytes) ->
            val expected = ldsSecurityObject?.hashFor(number)
            expected != null && digestAlgorithm != null &&
                    expected.contentEquals(Crypto.digest(digestAlgorithm, bytes))
        }
        if (dataGroupHashMatches.values.any { !it }) {
            flags.add(PassiveAuthenticationFlag.HASH_MISMATCH)
        }

        return PassiveAuthenticationResult(
            trusted = flags.isEmpty() && dataGroupHashMatches.values.all { it },
            flags = flags,
            dataGroupHashMatches = dataGroupHashMatches,
            documentSignerCertificate = documentSignerCertificate,
        )
    }

    private fun isSupported(certificate: X509Cert, supportedCurves: Set<EcCurve>): Boolean {
        val publicKey = certificate.publicKey
        return if (publicKey is EcPublicKey) supportedCurves.contains(publicKey.curve) else true
    }
}
