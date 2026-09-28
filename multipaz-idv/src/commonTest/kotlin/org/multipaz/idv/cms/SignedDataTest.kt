package org.multipaz.idv.cms

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.setUpBouncyCastleIfNeeded
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class SignedDataTest {
    @BeforeTest
    fun setup() = setUpBouncyCastleIfNeeded()

    private suspend fun selfSignedCert(algorithm: Algorithm): Pair<X509Cert, PrivateKey> {
        val privateKey = if (algorithm == Algorithm.RS256 || algorithm == Algorithm.PS256) {
            Crypto.createRsaPrivateKey(2048)
        } else {
            Crypto.createEcPrivateKey(EcCurve.P256)
        }
        val subject = X500Name.fromName("CN=Test Signer")
        val now = Clock.System.now()
        val cert = X509Cert.Builder(
            publicKey = privateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(privateKey, algorithm),
            serialNumber = ASN1Integer(1),
            subject = subject,
            issuer = subject,
            validFrom = now - 1.days,
            validUntil = now + 1.days,
        ).build()
        return Pair(cert, privateKey)
    }

    private fun roundTrip(algorithm: Algorithm) = runTest {
        val (cert, privateKey) = selfSignedCert(algorithm)
        val eContent = "hello lds security object".encodeToByteArray()

        val sodBytes = SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = eContent,
            digestAlgorithm = Algorithm.SHA256,
            signerCertificate = cert,
            signingKey = privateKey,
            signatureAlgorithm = algorithm,
        )

        val parsed = SignedData.parse(sodBytes)
        assertEquals(OID_LDS_SECURITY_OBJECT, parsed.eContentType)
        assertContentEquals(eContent, parsed.eContent)
        assertEquals(1, parsed.certificates.size)
        assertEquals(cert, parsed.certificates[0])
        assertContentEquals(Crypto.digest(Algorithm.SHA256, eContent), parsed.messageDigest)

        // Should not throw.
        parsed.verifySignature(cert.publicKey)
    }

    @Test fun roundTripEc() = roundTrip(Algorithm.ES256)
    @Test fun roundTripRsa() = roundTrip(Algorithm.RS256)
    @Test fun roundTripRsaPss() = roundTrip(Algorithm.PS256)

    @Test
    fun tamperedSignedAttributesFailVerification() = runTest {
        val (cert, privateKey) = selfSignedCert(Algorithm.ES256)
        val eContent = "original content".encodeToByteArray()
        val sodBytes = SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = eContent,
            digestAlgorithm = Algorithm.SHA256,
            signerCertificate = cert,
            signingKey = privateKey,
            signatureAlgorithm = Algorithm.ES256,
        )
        val parsed = SignedData.parse(sodBytes)
        val tampered = parsed.copy(
            signedAttributesForVerification = ByteArray(parsed.signedAttributesForVerification.size)
        )
        assertFailsWith<SignatureVerificationException> { tampered.verifySignature(cert.publicKey) }
    }
}
