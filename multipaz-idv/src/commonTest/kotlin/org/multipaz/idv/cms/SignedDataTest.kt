package org.multipaz.idv.cms

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TaggedObject
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

    @Test
    fun parsesEfSodWrapperAsReadFromAChip() = runTest {
        val (cert, privateKey) = selfSignedCert(Algorithm.ES256)
        val eContent = "wrapped lds security object".encodeToByteArray()
        val contentInfo = SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = eContent,
            digestAlgorithm = Algorithm.SHA256,
            signerCertificate = cert,
            signingKey = privateKey,
            signatureAlgorithm = Algorithm.ES256,
        )
        val efSod = SignedData.wrapEfSod(contentInfo)
        assertEquals(0x77.toByte(), efSod[0])
        val parsed = SignedData.parse(efSod)
        assertContentEquals(eContent, parsed.eContent)
        parsed.verifySignature(cert.publicKey)
    }

    @Test
    fun signerInfoNamingOnlyTheKeyTypeTakesTheHashFromTheDigestAlgorithm() = runTest {
        val (cert, privateKey) = selfSignedCert(Algorithm.RS256)
        val sodBytes = SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = "key type only".encodeToByteArray(),
            digestAlgorithm = Algorithm.SHA256,
            signerCertificate = cert,
            signingKey = privateKey,
            signatureAlgorithm = Algorithm.RS256,
        )
        // Swap the SignerInfo's sha256WithRSAEncryption OID (its last occurrence, after the
        // certificate) for plain rsaEncryption, as some passports encode it; both are 11 bytes.
        val sha256WithRsa = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
        val position = (sodBytes.size - sha256WithRsa.size downTo 0).first { start ->
            sha256WithRsa.indices.all { sodBytes[start + it] == sha256WithRsa[it] }
        }
        sodBytes[position + sha256WithRsa.size - 1] = 0x01
        val parsed = SignedData.parse(sodBytes)
        assertEquals(Algorithm.RS256, parsed.signatureAlgorithm)
        parsed.verifySignature(cert.publicKey)
    }

    @Test
    fun parsesIndefiniteLengthsAsNewZealandPassportsUseThem() = runTest {
        val (cert, privateKey) = selfSignedCert(Algorithm.ES256)
        val eContent = "indefinite lengths".encodeToByteArray()
        val contentInfo = SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = eContent,
            digestAlgorithm = Algorithm.SHA256,
            signerCertificate = cert,
            signingKey = privateKey,
            signatureAlgorithm = Algorithm.ES256,
        )
        // Re-encode the ContentInfo SEQUENCE and its [0] content with indefinite lengths (0x80,
        // closed by 00 00), keeping the SignedData inside exactly as it was.
        val decoded = ASN1.decode(contentInfo) as ASN1Sequence
        val contentType = ASN1.encode(decoded.elements[0])
        val signedData = (decoded.elements[1] as ASN1TaggedObject).content
        val indefinite = byteArrayOf(0x30, 0x80.toByte()) + contentType +
            byteArrayOf(0xA0.toByte(), 0x80.toByte()) + signedData + byteArrayOf(0, 0, 0, 0)
        val efSod = SignedData.wrapEfSod(indefinite)

        val parsed = SignedData.parse(efSod)
        assertContentEquals(eContent, parsed.eContent)
        parsed.verifySignature(cert.publicKey)
    }

    @Test
    fun truncatedIndefiniteLengthIsACmsException() {
        assertFailsWith<CmsException> { SignedData.parse(byteArrayOf(0x30, 0x80.toByte(), 0x02, 0x01, 0x05)) }
    }

    @Test
    fun malformedStructureIsACmsException() {
        // A SEQUENCE holding a single INTEGER rather than a ContentInfo.
        assertFailsWith<CmsException> { SignedData.parse(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x05)) }
        // The EF.SOD tag around something that isn't a ContentInfo.
        assertFailsWith<CmsException> { SignedData.parse(byteArrayOf(0x77, 0x03, 0x02, 0x01, 0x05)) }
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
