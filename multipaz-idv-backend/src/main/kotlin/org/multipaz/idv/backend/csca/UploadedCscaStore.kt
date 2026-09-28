package org.multipaz.idv.backend.csca

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X509Cert
import org.multipaz.openid4vci.idv.TrustedCscaInfo
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toHex

/**
 * Admin-uploaded CSCA certificates (`docs/validatopia/PLAN.md`'s Component F "Trust" page: "upload
 * CSCA PEMs ... list with expiry"), stored alongside the built-in Validatopia Test CSCA.
 *
 * Parsing an ICAO Master List (a CMS `SignedData` containing CSCA certificates) isn't supported:
 * `CscaStore`'s own doc comment notes this was deferred until real CSCA distribution is wired up
 * (M6), since it isn't needed while only synthetic data is used. Only PEM upload is offered here.
 */
object UploadedCscaStore {
    private val tableSpec = StorageTableSpec(
        name = "UploadedCsca",
        supportPartitions = false,
        supportExpiration = false
    )

    suspend fun list(): List<X509Cert> =
        BackendEnvironment.getTable(tableSpec).enumerateWithData()
            .map { (_, data) -> X509Cert.fromPem(StoredCsca.fromCbor(data.toByteArray()).pem) }

    /** Parses and stores one or more concatenated PEM certificates, returning the newly added ones. */
    suspend fun add(pem: String): List<X509Cert> {
        val certs = parsePemCertificates(pem)
        if (certs.isEmpty()) {
            throw InvalidRequestException("No PEM-encoded certificate found")
        }
        val table = BackendEnvironment.getTable(tableSpec)
        for (cert in certs) {
            val key = fingerprint(cert)
            val data = ByteString(StoredCsca(cert.toPem()).toCbor())
            if (table.get(key) == null) {
                table.insert(key = key, data = data)
            } else {
                table.update(key = key, data = data)
            }
        }
        return certs
    }

    suspend fun delete(fingerprintSha256Hex: String): Boolean =
        BackendEnvironment.getTable(tableSpec).delete(fingerprintSha256Hex.lowercase())

    suspend fun fingerprint(cert: X509Cert): String =
        Crypto.digest(Algorithm.SHA256, cert.encoded.toByteArray()).toHex()

    suspend fun toInfo(cert: X509Cert, builtIn: Boolean): TrustedCscaInfo = TrustedCscaInfo(
        fingerprintSha256Hex = fingerprint(cert),
        subject = cert.subject.name,
        notBeforeEpochSeconds = cert.validityNotBefore.epochSeconds,
        notAfterEpochSeconds = cert.validityNotAfter.epochSeconds,
        builtIn = builtIn,
    )

    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"

    private fun parsePemCertificates(pem: String): List<X509Cert> {
        val certs = mutableListOf<X509Cert>()
        var start = pem.indexOf(PEM_BEGIN)
        while (start >= 0) {
            val end = pem.indexOf(PEM_END, start)
            if (end < 0) break
            val blockEnd = end + PEM_END.length
            certs.add(X509Cert.fromPem(pem.substring(start, blockEnd)))
            start = pem.indexOf(PEM_BEGIN, blockEnd)
        }
        return certs
    }
}

@CborSerializable
internal data class StoredCsca(val pem: String) {
    companion object
}
