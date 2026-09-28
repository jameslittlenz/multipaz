package org.multipaz.idv.backend.csca

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.pa.CscaStore
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec

/**
 * The Validatopia Test CSCA and Document Signer: software keys.
 *
 * Loaded from the `validatopia_test_csca` configuration value when present (the fixed TEST keys in
 * `multipaz-server-deployment/validatopia-test-keys/`, which the verifier app bundles), otherwise
 * generated once and persisted across restarts (unit tests, ad-hoc local runs).
 */
class ValidatopiaTestCsca(
    val cscaCertificate: X509Cert,
    val documentSignerCertificate: X509Cert,
    val documentSignerPrivateKey: PrivateKey,
    val signatureAlgorithm: Algorithm,
) {
    /** A [CscaStore] trusting only the Validatopia Test CSCA. */
    val cscaStore: CscaStore = CscaStore.from(listOf(cscaCertificate))

    companion object {
        private val tableSpec = StorageTableSpec(
            name = "ValidatopiaTestCsca",
            supportPartitions = false,
            supportExpiration = false
        )
        private const val KEY = "csca"
        private val ALGORITHM = Algorithm.ES256

        /**
         * Returns the configured Validatopia Test CSCA/DS if `validatopia_test_csca` is set,
         * otherwise loads the persisted one, generating and persisting a new one if none exists yet.
         *
         * @throws IllegalArgumentException if `validatopia_test_csca` is set but malformed.
         */
        @Throws(IllegalArgumentException::class)
        suspend fun getOrCreate(): ValidatopiaTestCsca {
            BackendEnvironment.getInterface(Configuration::class)?.getValue(CONFIG_KEY)?.let {
                return fromConfiguration(it)
            }
            val table = BackendEnvironment.getTable(tableSpec)
            table.get(KEY)?.let { return decode(it.toByteArray()) }
            val cscaKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val cscaCert = SyntheticPassportFactory.createCsca(cscaKey, ALGORITHM)
            val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val dsCert = SyntheticPassportFactory.createDocumentSigner(
                cscaCertificate = cscaCert,
                cscaPrivateKey = cscaKey,
                cscaSignatureAlgorithm = ALGORITHM,
                documentSignerPrivateKey = dsKey,
            )
            val stored = StoredTestCsca(
                cscaCertPem = cscaCert.toPem(),
                documentSignerCertPem = dsCert.toPem(),
                documentSignerKeyPem = dsKey.toPem(),
            )
            try {
                table.insert(key = KEY, data = ByteString(stored.toCbor()))
            } catch (_: Exception) {
                // Lost a race with another request; use whatever ended up persisted.
                return decode(table.get(KEY)!!.toByteArray())
            }
            return fromStored(stored)
        }

        private const val CONFIG_KEY = "validatopia_test_csca"

        private fun fromConfiguration(value: String): ValidatopiaTestCsca {
            val json = Json.parseToJsonElement(value).jsonObject
            fun field(name: String): String = json[name]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("'$CONFIG_KEY.$name' is missing")
            return fromStored(
                StoredTestCsca(
                    cscaCertPem = field("csca_cert"),
                    documentSignerCertPem = field("ds_cert"),
                    documentSignerKeyPem = field("ds_key"),
                )
            )
        }

        private fun decode(data: ByteArray): ValidatopiaTestCsca =
            fromStored(StoredTestCsca.fromCbor(data))

        private fun fromStored(stored: StoredTestCsca): ValidatopiaTestCsca {
            val dsCert = X509Cert.fromPem(stored.documentSignerCertPem)
            return ValidatopiaTestCsca(
                cscaCertificate = X509Cert.fromPem(stored.cscaCertPem),
                documentSignerCertificate = dsCert,
                documentSignerPrivateKey = EcPrivateKey.fromPem(stored.documentSignerKeyPem, dsCert.ecPublicKey),
                signatureAlgorithm = ALGORITHM,
            )
        }
    }
}

@CborSerializable
internal data class StoredTestCsca(
    val cscaCertPem: String,
    val documentSignerCertPem: String,
    val documentSignerKeyPem: String,
) {
    companion object
}
