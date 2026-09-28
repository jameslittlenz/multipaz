package org.multipaz.idv.backend.csca

import kotlinx.io.bytestring.ByteString
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
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec

/** The Validatopia Test CSCA and Document Signer: software keys, persisted across restarts. */
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
         * Loads the persisted Validatopia Test CSCA/DS, generating and persisting a new one if
         * none exists yet.
         */
        suspend fun getOrCreate(): ValidatopiaTestCsca {
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
