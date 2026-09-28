package org.multipaz.idv.pa

import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert

/**
 * A set of trusted Country Signing Certificate Authority (CSCA) certificates, indexed by subject
 * so [org.multipaz.idv.pa.PassiveAuthenticator] can look up candidates for a Document Signer's
 * issuer.
 *
 * Loading an ICAO Master List (itself a CMS `SignedData`, like an `EF.SOD`, but containing CSCA
 * certificates instead of data group hashes) isn't supported yet: it isn't needed until real CSCA
 * distribution is wired up, which requires real certificates to test against (see
 * `docs/validatopia/PLAN.md`'s M1 discovery task, deferred while only synthetic data is used).
 */
class CscaStore private constructor(private val bySubject: Map<X500Name, List<X509Cert>>) {
    /** The CSCA certificates whose subject matches [subject], usually a Document Signer's issuer. */
    fun findBySubject(subject: X500Name): List<X509Cert> = bySubject[subject] ?: emptyList()

    /** All CSCA certificates in this store. */
    val certificates: List<X509Cert> get() = bySubject.values.flatten()

    companion object {
        /** Builds a store from a list of trusted CSCA certificates. */
        fun from(certificates: List<X509Cert>): CscaStore = CscaStore(certificates.groupBy { it.subject })

        /** Parses one or more concatenated PEM-encoded certificates. */
        fun fromPem(pem: String): CscaStore = from(splitPemCertificates(pem).map { X509Cert.fromPem(it) })

        private const val BEGIN = "-----BEGIN CERTIFICATE-----"
        private const val END = "-----END CERTIFICATE-----"

        private fun splitPemCertificates(pem: String): List<String> {
            val blocks = mutableListOf<String>()
            var start = pem.indexOf(BEGIN)
            while (start >= 0) {
                val end = pem.indexOf(END, start)
                if (end < 0) break
                val blockEnd = end + END.length
                blocks.add(pem.substring(start, blockEnd))
                start = pem.indexOf(BEGIN, blockEnd)
            }
            return blocks
        }
    }
}
