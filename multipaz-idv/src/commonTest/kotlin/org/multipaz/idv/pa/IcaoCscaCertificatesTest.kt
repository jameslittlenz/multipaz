package org.multipaz.idv.pa

import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509CertChainValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IcaoCscaCertificatesTest {
    private val certificates = IcaoCscaCertificates.certificates

    @Test
    fun everyCertificateParsesWithItsPublicKey() {
        assertEquals(52, certificates.size)
        for (certificate in certificates) {
            // Many CSCAs encode their EC curve as explicit parameters; reading the key must cope.
            certificate.publicKey
        }
    }

    @Test
    fun coversTheBundledCountries() {
        val countries = certificates.map { certificate ->
            certificate.subject.components.getValue("2.5.4.6").value.uppercase()
        }.toSet()
        assertEquals(setOf("NZ", "AU", "US", "CA", "GB", "KR", "JP"), countries)
    }

    @Test
    fun noDuplicates() {
        assertEquals(certificates.size, certificates.map { it.encoded }.toSet().size)
    }

    // Each certificate's signature checks out against the bundled CSCA holding the signing key: itself
    // for a root, another CSCA for a link certificate. That exercises every key type in the bundle,
    // explicit EC included. A link certificate signed by a CSCA that has since expired, and so isn't
    // bundled, is skipped.
    @Test
    fun everyCertificateVerifiesAgainstItsBundledSigner() = runTest {
        var checked = 0
        for (certificate in certificates) {
            val signer = certificates.firstOrNull {
                it.subject == certificate.issuer &&
                    it.subjectKeyIdentifier.contentEquals(certificate.authorityKeyIdentifier ?: certificate.subjectKeyIdentifier)
            } ?: continue
            X509CertChain(listOf(certificate, signer)).validate(validateValidity = false)
            checked++
        }
        // All but two GB link certificates, whose signers have expired. Both carry the same keys as
        // bundled GB roots.
        assertEquals(certificates.size - 2, checked)
    }

    // NZ's "Passport CSCA" keys use explicit EC parameters; one can't stand in for another.
    @Test
    fun aWrongSignerIsRejected() = runTest {
        val nzCscas = certificates.filter { it.subject.name.startsWith("CN=Passport CSCA") }
        val certificate = nzCscas.first()
        val other = nzCscas.first { !it.subjectKeyIdentifier.contentEquals(certificate.authorityKeyIdentifier) }
        assertFailsWith<X509CertChainValidationException> {
            X509CertChain(listOf(certificate, other)).validate(validateValidity = false)
        }
    }

    @Test
    fun storeFindsCertificatesBySubject() {
        val store = CscaStore.from(certificates)
        for (certificate in certificates) {
            assertTrue(store.findBySubject(certificate.subject).contains(certificate))
        }
    }
}
