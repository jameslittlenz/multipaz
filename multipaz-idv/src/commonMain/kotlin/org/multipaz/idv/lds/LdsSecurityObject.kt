package org.multipaz.idv.lds

import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Null
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.crypto.Algorithm
import org.multipaz.idv.cms.CmsException

/**
 * One entry of an [LdsSecurityObject]: the hash of one data group's raw file contents.
 *
 * @property dataGroupNumber the data group number, e.g. `1` for DG1.
 * @property hashValue the hash of that data group's raw bytes.
 */
data class DataGroupHash(val dataGroupNumber: Int, val hashValue: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is DataGroupHash && dataGroupNumber == other.dataGroupNumber && hashValue.contentEquals(other.hashValue)

    override fun hashCode(): Int = 31 * dataGroupNumber + hashValue.contentHashCode()
}

/**
 * The `LDSSecurityObject` (ICAO 9303-10 Section 4.6.2): the content signed inside a passport's
 * `EF.SOD` (see [org.multipaz.idv.cms.SignedData]), listing the expected hash of each data group.
 *
 * Only the `v0` form is supported (a bare list of data group hashes, no `ldsVersionInfo`), which is
 * what the overwhelming majority of e-passports use; `v1` (which adds LDS/Unicode version info) can
 * be added once real chips are inspected and are found to need it.
 *
 * @property hashAlgorithmOid the OID of the hash algorithm used for every [dataGroupHashes] entry.
 * @property dataGroupHashes the expected hash of each data group covered by the SOD.
 */
data class LdsSecurityObject(
    val hashAlgorithmOid: String,
    val dataGroupHashes: List<DataGroupHash>,
) {
    /** The hash for [dataGroupNumber], or `null` if that data group isn't listed. */
    fun hashFor(dataGroupNumber: Int): ByteArray? =
        dataGroupHashes.firstOrNull { it.dataGroupNumber == dataGroupNumber }?.hashValue

    companion object {
        private const val VERSION_V0 = 0L

        /**
         * Parses an `LDSSecurityObject` from its DER encoding (a `SignedData`'s `eContent`). An
         * LDS 1.8 `ldsVersionInfo` after the hashes is ignored.
         *
         * @throws CmsException if [bytes] isn't a well-formed `LDSSecurityObject`.
         */
        fun parse(bytes: ByteArray): LdsSecurityObject = try {
            parseSequence(bytes)
        } catch (e: CmsException) {
            throw e
        } catch (e: RuntimeException) {
            throw CmsException("Malformed LDSSecurityObject: ${e.message ?: e::class.simpleName}")
        }

        private fun parseSequence(bytes: ByteArray): LdsSecurityObject {
            val seq = ASN1.decode(bytes) as? ASN1Sequence
                ?: throw CmsException("LDSSecurityObject is not a SEQUENCE")
            val hashAlgorithmOid = ((seq.elements[1] as ASN1Sequence).elements[0] as ASN1ObjectIdentifier).oid
            val dataGroupHashSeqs = (seq.elements[2] as ASN1Sequence).elements
            val dataGroupHashes = dataGroupHashSeqs.map {
                val hashSeq = it as ASN1Sequence
                DataGroupHash(
                    dataGroupNumber = (hashSeq.elements[0] as ASN1Integer).toLong().toInt(),
                    hashValue = (hashSeq.elements[1] as ASN1OctetString).value,
                )
            }
            return LdsSecurityObject(hashAlgorithmOid, dataGroupHashes)
        }

        /** Builds the DER encoding of an `LDSSecurityObject` (a `SignedData`'s `eContent`). */
        fun build(hashAlgorithm: Algorithm, dataGroupHashes: List<DataGroupHash>): ByteArray {
            val hashAlgorithmOid = when (hashAlgorithm) {
                Algorithm.SHA256 -> OID.SHA256.oid
                Algorithm.SHA384 -> OID.SHA384.oid
                Algorithm.SHA512 -> OID.SHA512.oid
                else -> throw CmsException("Unsupported hash algorithm $hashAlgorithm")
            }
            val seq = ASN1Sequence(listOf(
                ASN1Integer(VERSION_V0),
                ASN1Sequence(listOf(ASN1ObjectIdentifier(hashAlgorithmOid), ASN1Null())),
                ASN1Sequence(dataGroupHashes.sortedBy { it.dataGroupNumber }.map {
                    ASN1Sequence(listOf(
                        ASN1Integer(it.dataGroupNumber.toLong()),
                        ASN1OctetString(it.hashValue),
                    ))
                }),
            ))
            return ASN1.encode(seq)
        }
    }
}
