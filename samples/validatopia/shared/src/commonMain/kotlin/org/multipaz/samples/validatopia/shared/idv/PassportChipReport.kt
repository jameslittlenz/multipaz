package org.multipaz.samples.validatopia.shared.idv

import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.RsaPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.cms.CmsException
import org.multipaz.idv.cms.SignedData
import org.multipaz.idv.lds.Dg2Face
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.lds.LdsException
import org.multipaz.idv.lds.LdsSecurityObject
import org.multipaz.idv.pa.CscaStore
import org.multipaz.idv.pa.PassiveAuthenticator

/** How the wallet got access to a passport chip. */
enum class ChipAccessProtocol(val displayName: String) {
    PACE("PACE"),
    BAC("Basic Access Control (BAC)"),
}

/**
 * A passport chip read: the files the issuer needs, plus how the chip was accessed.
 *
 * @property dataGroupsOnChip the data group numbers EF.COM lists, or empty if it couldn't be read.
 */
class PassportChipRead(
    val accessProtocol: ChipAccessProtocol,
    val sod: ByteArray,
    val dg1: ByteArray,
    val dg2: ByteArray,
    val dataGroupsOnChip: List<Int>,
)

/**
 * Technical details of a passport chip, for recording a country's passport profile
 * (`docs/validatopia/passport-profiles.md`).
 *
 * It holds no personal data: no names, dates or document numbers, and no images.
 *
 * @property rows label and value pairs, in display order.
 */
class PassportChipReport(val rows: List<Pair<String, String>>) {
    /** The report as plain text, one `label: value` line per row. */
    fun toText(): String = rows.joinToString("\n") { (label, value) -> "$label: $value" }

    companion object {
        /** Describes [read], passive-authenticating it against [cscaStore]. */
        suspend fun create(read: PassportChipRead, cscaStore: CscaStore): PassportChipReport {
            val rows = mutableListOf<Pair<String, String>>()
            rows += "Access control" to read.accessProtocol.displayName
            rows += "Data groups on chip (EF.COM)" to
                (read.dataGroupsOnChip.takeIf { it.isNotEmpty() }?.joinToString { "DG$it" } ?: "unknown")
            rows += "DG14 (Chip Authentication)" to presence(read.dataGroupsOnChip, 14)
            rows += "DG15 (Active Authentication)" to presence(read.dataGroupsOnChip, 15)

            try {
                rows += "SOD outer tag" to if (read.sod.firstOrNull() == 0x77.toByte()) "EF.SOD (0x77)" else
                    "none (0x${read.sod.firstOrNull()?.toInt()?.and(0xFF)?.toString(16)})"
                val signedData = SignedData.parse(read.sod)
                rows += "SOD digest algorithm" to hashName(signedData.digestAlgorithmOid)
                rows += "SOD signature algorithm" to signedData.signatureAlgorithm.name
                rows += "Certificates in SOD" to signedData.certificates.size.toString()
                rows += "SOD content digest" to when (val algorithm = SignedData.hashAlgorithmFromOid(signedData.digestAlgorithmOid)) {
                    null -> "not checked (unsupported algorithm)"
                    else -> if (Crypto.digest(algorithm, signedData.eContent).contentEquals(signedData.messageDigest)) {
                        "matches"
                    } else {
                        "doesn't match the signed messageDigest"
                    }
                }
                val securityObject = LdsSecurityObject.parse(signedData.eContent)
                rows += "Data group hash algorithm" to hashName(securityObject.hashAlgorithmOid)
                rows += "Data groups hashed in SOD" to
                    securityObject.dataGroupHashes.joinToString { "DG${it.dataGroupNumber}" }
                signedData.certificates.firstOrNull()?.let { documentSigner ->
                    rows += "Document Signer issuer" to documentSigner.issuer.name
                    rows += "Document Signer key" to describeKey(documentSigner)
                }
            } catch (e: CmsException) {
                rows += "SOD" to "couldn't be parsed: ${e.message}"
            } catch (e: IllegalArgumentException) {
                rows += "SOD" to "couldn't be parsed: ${e.message}"
            }

            val passiveAuthentication = PassiveAuthenticator.authenticate(
                sod = read.sod,
                dataGroups = mapOf(1 to read.dg1, 2 to read.dg2),
                cscaStore = cscaStore,
            )
            rows += "Passive authentication" to
                if (passiveAuthentication.trusted) {
                    "passed"
                } else {
                    "failed: ${passiveAuthentication.flags.joinToString()}" +
                        passiveAuthentication.details.joinToString("") { "\n  - $it" }
                }
            passiveAuthentication.documentSignerCertificate?.let { documentSigner ->
                val csca = cscaStore.findBySubject(documentSigner.issuer).firstOrNull()
                rows += "Matching CSCA" to (csca?.let { "${it.subject.name} (${describeKey(it)})" } ?: "none")
            }

            try {
                val face = Lds.parseDG2Face(read.dg2)
                rows += "DG2 encoding" to "ISO/IEC 19794-5"
                rows += "DG2 image format" to imageFormat(face)
                rows += "DG2 image size" to "${face.width} x ${face.height}, ${face.image.size} bytes"
                rows += "DG2 feature points" to face.featurePointCount.toString()
                rows += "DG2 templates and images" to "${face.templateCount} template(s), ${face.imageCount} image(s)"
            } catch (e: LdsException) {
                rows += "DG2" to "couldn't be parsed: ${e.message}"
            }
            rows += "DG2 file size" to "${read.dg2.size} bytes"
            return PassportChipReport(rows)
        }

        private fun presence(dataGroups: List<Int>, number: Int): String = when {
            dataGroups.isEmpty() -> "unknown"
            number in dataGroups -> "present"
            else -> "absent"
        }

        private fun hashName(oid: String): String =
            SignedData.hashAlgorithmFromOid(oid)?.name ?: "unsupported ($oid)"

        private fun describeKey(certificate: X509Cert): String = when (val key = certificate.publicKey) {
            is EcPublicKey -> "EC ${key.curve.name}"
            is RsaPublicKey -> "RSA ${bitLength(key.modulus)}-bit"
            else -> key::class.simpleName ?: "unknown"
        }

        private fun bitLength(modulus: ByteArray): Int {
            val firstNonZero = modulus.indexOfFirst { it != 0.toByte() }
            if (firstNonZero < 0) return 0
            val leading = modulus[firstNonZero].toInt() and 0xFF
            return (modulus.size - firstNonZero - 1) * 8 + (32 - leading.countLeadingZeroBits())
        }

        private fun imageFormat(face: Dg2Face): String {
            val image = face.image
            val magic = when {
                image.size >= 3 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() -> "JPEG"
                image.size >= 12 && image[4] == 'j'.code.toByte() && image[5] == 'P'.code.toByte() -> "JPEG 2000 (JP2 file)"
                image.size >= 4 && image[0] == 0xFF.toByte() && image[1] == 0x4F.toByte() -> "JPEG 2000 (codestream)"
                else -> "unrecognized"
            }
            val declared = when (face.imageDataType) {
                Dg2Face.IMAGE_DATA_TYPE_JPEG -> "JPEG"
                Dg2Face.IMAGE_DATA_TYPE_JPEG2000 -> "JPEG 2000"
                else -> "type ${face.imageDataType}"
            }
            return "$magic, declared $declared"
        }
    }
}
