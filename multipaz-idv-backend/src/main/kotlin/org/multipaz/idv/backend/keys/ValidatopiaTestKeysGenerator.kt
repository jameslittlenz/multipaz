package org.multipaz.idv.backend.keys

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.mdoc.util.MdocUtil
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Generates the fixed Validatopia TEST PKI that the Validatopia profile, the verifier and the
 * wallet all share, so that trust anchors stay the same across deployments instead of being
 * generated per server instance.
 *
 * Run once (re-running replaces every key, which invalidates all issued Photo IDs):
 *
 * ```
 * ./gradlew :multipaz-idv-backend:generateValidatopiaTestKeys
 * ```
 *
 * Arguments: `<keysDir> <kotlinFile>`. Writes into `keysDir`:
 * - `validatopia-keys.conf`: server configuration (JSON, merged via `-config`) holding the IACA
 *   as `root_identities.credential_signing` and the test CSCA/DS as `validatopia_test_csca`.
 *   **Contains private keys.**
 * - `validatopia-reader-key.json`: the verifier's reader-authentication key and chain.
 *   **Contains a private key.**
 * - `validatopia_iaca.pem`, `validatopia_test_csca.pem`, `validatopia_test_ds.pem`,
 *   `validatopia_reader_root.pem`: the public certificates.
 *
 * and writes `kotlinFile`, the same material as constants for the apps' shared module.
 */
object ValidatopiaTestKeysGenerator {
    private const val ISSUER_ALT_NAME_URL = "https://validatopia.test"
    private val SIGNING_ALGORITHM = Algorithm.ESP256

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "Usage: ValidatopiaTestKeysGenerator <keysDir> <kotlinFile>" }
        val keysDir = File(args[0])
        val kotlinFile = File(args[1])
        keysDir.mkdirs()

        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

        // Photo ID issuer (IACA). The server self-enrolls its short-lived DS certificate under it.
        val iacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey, SIGNING_ALGORITHM),
            subject = X500Name.fromName("CN=Validatopia TEST IACA,O=Validatopia,C=XV"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now,
            validUntil = now + (10 * 365).days,
            issuerAltNameUrl = ISSUER_ALT_NAME_URL,
            crlUrl = "$ISSUER_ALT_NAME_URL/crl/iaca",
        )

        // Passport issuer (test CSCA + DS) that signs every persona's synthetic passport.
        val cscaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val cscaCert = SyntheticPassportFactory.createCsca(
            privateKey = cscaKey,
            signatureAlgorithm = Algorithm.ES256,
            validFrom = now,
            validUntil = now + (15 * 365).days,
        )
        val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = SyntheticPassportFactory.createDocumentSigner(
            cscaCertificate = cscaCert,
            cscaPrivateKey = cscaKey,
            cscaSignatureAlgorithm = Algorithm.ES256,
            documentSignerPrivateKey = dsKey,
            validFrom = now,
            validUntil = now + (10 * 365).days,
        )

        // Verifier reader authentication, so the wallet's consent sheet can name the verifier.
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(readerRootKey, SIGNING_ALGORITHM),
            subject = X500Name.fromName("CN=Validatopia TEST Reader Root,O=Validatopia,C=XV"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now,
            validUntil = now + (10 * 365).days,
            crlUrl = "$ISSUER_ALT_NAME_URL/crl/reader_root",
        )
        val readerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                X509CertChain(listOf(readerRootCert)), readerRootKey, SIGNING_ALGORITHM
            ),
            readerKey = readerKey.publicKey,
            subject = X500Name.fromName("CN=Validatopia Verify,O=Validatopia,C=XV"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now,
            // ISO/IEC 18013-5 Annex B caps reader certificates at 1187 days.
            validUntil = now + 1187.days,
        )

        val serverConfig = buildJsonObject {
            put("root_identities", buildJsonObject {
                put("credential_signing", keyJson(iacaKey, X509CertChain(listOf(iacaCert))))
            })
            put("validatopia_test_csca", buildJsonObject {
                put("csca_cert", cscaCert.toPem())
                put("ds_cert", dsCert.toPem())
                put("ds_key", dsKey.toPem())
            })
        }
        val readerKeyJson = keyJson(readerKey, X509CertChain(listOf(readerCert, readerRootCert)))

        val prettyJson = Json { prettyPrint = true }
        File(keysDir, "validatopia-keys.conf").writeText(prettyJson.encodeToString(JsonObject.serializer(), serverConfig) + "\n")
        File(keysDir, "validatopia-reader-key.json").writeText(
            prettyJson.encodeToString(JsonObject.serializer(), readerKeyJson) + "\n"
        )
        File(keysDir, "validatopia_iaca.pem").writeText(iacaCert.toPem())
        File(keysDir, "validatopia_test_csca.pem").writeText(cscaCert.toPem())
        File(keysDir, "validatopia_test_ds.pem").writeText(dsCert.toPem())
        File(keysDir, "validatopia_reader_root.pem").writeText(readerRootCert.toPem())

        kotlinFile.parentFile.mkdirs()
        kotlinFile.writeText(
            kotlinConstants(
                iacaCert = iacaCert,
                cscaCert = cscaCert,
                readerRootCert = readerRootCert,
                readerKeyJson = Json.encodeToString(JsonObject.serializer(), readerKeyJson),
            )
        )

        for ((name, cert) in listOf(
            "IACA" to iacaCert,
            "Test CSCA" to cscaCert,
            "Test DS" to dsCert,
            "Reader root" to readerRootCert,
            "Reader" to readerCert,
        )) {
            val fingerprint = Crypto.digest(Algorithm.SHA256, cert.encoded.toByteArray())
                .joinToString(":") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() }
            println("$name: ${cert.subject.name}, valid until ${cert.validityNotAfter}, SHA-256 $fingerprint")
        }
    }

    private suspend fun keyJson(privateKey: EcPrivateKey, chain: X509CertChain): JsonObject {
        val jwk = privateKey.toJwk()
        return buildJsonObject {
            for ((key, value) in jwk) {
                put(key, value)
            }
            put("x5c", chain.toX5c(excludeRoot = false))
        }
    }

    private fun kotlinConstants(
        iacaCert: X509Cert,
        cscaCert: X509Cert,
        readerRootCert: X509Cert,
        readerKeyJson: String,
    ): String = buildString {
        appendLine("package org.multipaz.samples.validatopia.shared.trust")
        appendLine()
        appendLine("// GENERATED by ValidatopiaTestKeysGenerator (multipaz-idv-backend); do not edit by hand.")
        appendLine("// Source of truth: multipaz-server-deployment/validatopia-test-keys/. See its README.md.")
        appendLine()
        appendLine("/** The fixed Validatopia TEST PKI shared by the issuer profile, the verifier and the wallet. */")
        appendLine("object ValidatopiaTestPki {")
        appendConstant("IACA_PEM", "Validatopia TEST IACA: root of every Photo ID's issuer certificate chain.", iacaCert.toPem())
        appendConstant(
            "TEST_CSCA_PEM",
            "Validatopia Test CSCA: signs every persona's synthetic passport (SOD).",
            cscaCert.toPem()
        )
        appendConstant(
            "READER_ROOT_PEM",
            "Validatopia TEST Reader Root: the wallet trusts reader certificates chaining to this.",
            readerRootCert.toPem()
        )
        appendConstant(
            "READER_KEY_JSON",
            "The verifier's reader-authentication key (JWK plus x5c chain). A TEST key, public by design.",
            readerKeyJson
        )
        appendLine("}")
    }

    private fun StringBuilder.appendConstant(name: String, doc: String, value: String) {
        appendLine("    /** $doc */")
        appendLine("    const val $name = \"\"\"")
        for (line in value.trim().lines()) {
            appendLine(line)
        }
        appendLine("\"\"\"")
        appendLine()
    }
}
