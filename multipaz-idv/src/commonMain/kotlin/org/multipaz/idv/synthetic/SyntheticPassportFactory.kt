package org.multipaz.idv.synthetic

import kotlinx.datetime.LocalDate
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.idv.cms.OID_LDS_SECURITY_OBJECT
import org.multipaz.idv.cms.SignedData
import org.multipaz.idv.lds.DataGroupHash
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.lds.LdsSecurityObject
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.mrz.MrzTd3
import org.multipaz.idv.mrz.Mrz
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Builds synthetic test CSCA/Document Signer certificate chains and passports (DG1, DG2 and SOD),
 * for tests and for dummy persona issuance, without any real passport data or real CSCA
 * certificates. See `docs/validatopia/PLAN.md`'s Component A.
 */
object SyntheticPassportFactory {
    /** A self-signed test CSCA certificate. */
    suspend fun createCsca(
        privateKey: PrivateKey,
        signatureAlgorithm: Algorithm,
        subject: X500Name = X500Name.fromName("CN=Validatopia Test CSCA,O=Validatopia,C=XV"),
        validFrom: Instant = Clock.System.now() - 1.days,
        validUntil: Instant = Clock.System.now() + 3650.days,
    ): X509Cert = X509Cert.Builder(
        publicKey = privateKey.publicKey,
        signingKey = AsymmetricKey.anonymous(privateKey, signatureAlgorithm),
        serialNumber = ASN1Integer.fromRandom(64),
        subject = subject,
        issuer = subject,
        validFrom = validFrom,
        validUntil = validUntil,
    )
        .includeSubjectKeyIdentifier()
        .includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN))
        .setBasicConstraints(ca = true, pathLenConstraint = 0)
        .build()

    /** A test Document Signer certificate, signed by [cscaPrivateKey]. */
    suspend fun createDocumentSigner(
        cscaCertificate: X509Cert,
        cscaPrivateKey: PrivateKey,
        cscaSignatureAlgorithm: Algorithm,
        documentSignerPrivateKey: PrivateKey,
        subject: X500Name = X500Name.fromName("CN=Validatopia Test DS,O=Validatopia,C=XV"),
        validFrom: Instant = Clock.System.now() - 1.days,
        validUntil: Instant = Clock.System.now() + 1095.days,
    ): X509Cert = X509Cert.Builder(
        publicKey = documentSignerPrivateKey.publicKey,
        signingKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(cscaCertificate)), cscaPrivateKey, cscaSignatureAlgorithm
        ),
        serialNumber = ASN1Integer.fromRandom(64),
        subject = subject,
        issuer = cscaCertificate.subject,
        validFrom = validFrom,
        validUntil = validUntil,
    )
        .includeSubjectKeyIdentifier()
        .setAuthorityKeyIdentifierToCertificate(cscaCertificate)
        .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
        .build()

    /**
     * Builds an `EF.SOD` for the given data groups, signed by [documentSignerPrivateKey].
     *
     * @param dataGroups the raw bytes of each data group to include, keyed by data group number.
     */
    suspend fun createSod(
        documentSignerCertificate: X509Cert,
        documentSignerPrivateKey: PrivateKey,
        documentSignerSignatureAlgorithm: Algorithm,
        dataGroups: Map<Int, ByteArray>,
        hashAlgorithm: Algorithm = Algorithm.SHA256,
    ): ByteArray {
        val dataGroupHashes = dataGroups.map { (number, bytes) ->
            DataGroupHash(number, Crypto.digest(hashAlgorithm, bytes))
        }
        val eContent = LdsSecurityObject.build(hashAlgorithm, dataGroupHashes)
        return SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = eContent,
            digestAlgorithm = hashAlgorithm,
            signerCertificate = documentSignerCertificate,
            signingKey = documentSignerPrivateKey,
            signatureAlgorithm = documentSignerSignatureAlgorithm,
        )
    }

    /**
     * A synthetic passport: its MRZ, DG1, DG2 and SOD.
     *
     * @property mrz the passport's MRZ fields.
     * @property dg1 the raw bytes of EF.DG1.
     * @property dg2 the raw bytes of EF.DG2.
     * @property sod the raw bytes of EF.SOD.
     */
    data class SyntheticPassport(
        val mrz: MrzTd3,
        val dg1: ByteArray,
        val dg2: ByteArray,
        val sod: ByteArray,
    )

    /**
     * Builds a full synthetic passport (DG1, DG2 and a matching SOD) for a test identity.
     *
     * @param portraitBytes stand-in image bytes for the DG2 portrait; these are never decoded as an
     *   image in `multipaz-idv` (only hashed), so they don't need to be a real JPEG.
     */
    suspend fun createPassport(
        documentSignerCertificate: X509Cert,
        documentSignerPrivateKey: PrivateKey,
        documentSignerSignatureAlgorithm: Algorithm,
        issuingState: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        documentNumber: String,
        nationality: String,
        birthDate: LocalDate,
        sex: MrzSex,
        expiryDate: LocalDate,
        portraitBytes: ByteArray = DEFAULT_PORTRAIT_BYTES,
        hashAlgorithm: Algorithm = Algorithm.SHA256,
    ): SyntheticPassport {
        val mrz = Mrz.buildTd3(
            documentCode = "P",
            issuingState = issuingState,
            primaryIdentifier = primaryIdentifier,
            secondaryIdentifier = secondaryIdentifier,
            documentNumber = documentNumber,
            nationality = nationality,
            birthDate = birthDate,
            sex = sex,
            expiryDate = expiryDate,
        )
        val dg1 = Lds.buildDG1(mrz.raw)
        val dg2 = Lds.buildDG2(portraitBytes)
        val sod = createSod(
            documentSignerCertificate = documentSignerCertificate,
            documentSignerPrivateKey = documentSignerPrivateKey,
            documentSignerSignatureAlgorithm = documentSignerSignatureAlgorithm,
            dataGroups = mapOf(1 to dg1, 2 to dg2),
            hashAlgorithm = hashAlgorithm,
        )
        return SyntheticPassport(mrz = mrz, dg1 = dg1, dg2 = dg2, sod = sod)
    }

    // Not a real JPEG; DG2 content is only ever hashed, never decoded, until the wallet UI
    // (an M4 concern) needs to display a portrait.
    private val DEFAULT_PORTRAIT_BYTES = "not a real JPEG, just synthetic test bytes".encodeToByteArray()
}
