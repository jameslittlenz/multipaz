package org.multipaz.idv.lds

import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject

/** Thrown when an LDS data group (DG1, DG2, ...) is malformed. */
class LdsException(message: String) : Exception(message)

/**
 * Parsing and building for the ICAO 9303 Logical Data Structure (LDS) data groups read from a
 * passport chip's DG1 (MRZ) and DG2 (facial image) elementary files.
 *
 * LDS data groups are encoded as BER-TLV, which is what [org.multipaz.asn1.ASN1] already decodes
 * (its tag classes cover APPLICATION and CONTEXT_SPECIFIC, not just UNIVERSAL), so this reuses it
 * rather than adding a second TLV decoder.
 */
object Lds {
    // Application-class tag numbers, per ICAO 9303-10 Table 1. The 0x61/0x75/0x7F61/0x7F60/0x5F2E
    // byte values often quoted in the spec are the encoded identifier octets; the numbers below are
    // the tag numbers ASN1TaggedObject.tag holds once the class/constructed bits are stripped off.
    private const val TAG_EF_DG1 = 1          // 0x61
    private const val TAG_MRZ_INFO = 31        // 0x5F1F
    private const val TAG_EF_DG2 = 21          // 0x75
    private const val TAG_BIOMETRIC_INFO_GROUP = 97   // 0x7F61
    private const val TAG_BIOMETRIC_INFO = 96          // 0x7F60
    private const val TAG_BIOMETRIC_HEADER_TEMPLATE = 1  // 0xA1 (context-specific, constructed)
    private const val TAG_BIOMETRIC_DATA_BLOCK = 46    // 0x5F2E
    private const val TAG_FORMAT_OWNER = 7             // 0x87 (context-specific, primitive)
    private const val TAG_FORMAT_TYPE = 8              // 0x88 (context-specific, primitive)

    // CBEFF format owner/type for an ISO/IEC 19794-5 face record, per the ISO/IEC 19785-3
    // biometric registry (also used this way by every ICAO-compliant passport).
    private const val FORMAT_OWNER_ISO_JTC1_SC37 = 0x0101
    private const val FORMAT_TYPE_FACE = 0x0008

    // The general-header magic ("FAC" + version "010" + NUL, all as bytes) that begins every
    // ISO/IEC 19794-5 face record, plus the fixed sizes of the headers preceding the image data.
    private val FORMAT_ID = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 0)
    private val VERSION_ID = byteArrayOf('0'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), 0)
    private const val GENERAL_HEADER_SIZE = 14
    private const val FACIAL_RECORD_HEADER_SIZE = 20
    private const val IMAGE_INFO_SIZE = 12
    private const val FACIAL_RECORD_PREFIX_SIZE =
        FACIAL_RECORD_HEADER_SIZE + IMAGE_INFO_SIZE

    /** Extracts the raw MRZ text from an EF.DG1 file. */
    fun parseDG1(dg1: ByteArray): String {
        val outer = decodeApplicationTag(dg1, TAG_EF_DG1, "EF.DG1")
        val inner = decodeApplicationTag(outer.content, TAG_MRZ_INFO, "MRZ_INFO")
        return inner.content.decodeToString()
    }

    /** Builds an EF.DG1 file containing the given raw MRZ text. */
    fun buildDG1(mrz: String): ByteArray {
        val mrzInfo = ASN1TaggedObject(
            ASN1TagClass.APPLICATION, ASN1Encoding.PRIMITIVE, TAG_MRZ_INFO, mrz.encodeToByteArray()
        )
        val dg1 = ASN1TaggedObject(
            ASN1TagClass.APPLICATION, ASN1Encoding.CONSTRUCTED, TAG_EF_DG1, ASN1.encode(mrzInfo)
        )
        return ASN1.encode(dg1)
    }

    /**
     * Extracts the portrait image bytes (JPEG or JPEG 2000, per the CBEFF format type) from an
     * EF.DG2 file built by [buildDG2].
     *
     * This only supports the legacy ISO/IEC 19794-5 encoding (a single facial record, no feature
     * points), which is what [buildDG2] produces. ISO/IEC 39794-5, the newer face-image standard
     * some passports use instead, isn't supported yet — real NZ/AU chips need to be inspected
     * (the deferred M1 discovery task) before adding it.
     */
    fun parseDG2(dg2: ByteArray): ByteArray {
        val ef = decodeApplicationTag(dg2, TAG_EF_DG2, "EF.DG2")
        val group = decodeApplicationTag(ef.content, TAG_BIOMETRIC_INFO_GROUP, "biometric info group")
        val groupElements = ASN1.decodeMultiple(group.content)
        if (groupElements.size < 2 || groupElements[0] !is ASN1Integer) {
            throw LdsException("Biometric info group is missing its instance count")
        }
        val info = requireApplicationTag(groupElements[1], TAG_BIOMETRIC_INFO, "biometric info")
        val infoElements = ASN1.decodeMultiple(info.content)
        val bdb = infoElements.filterIsInstance<ASN1TaggedObject>().firstOrNull {
            it.cls == ASN1TagClass.APPLICATION && it.tag == TAG_BIOMETRIC_DATA_BLOCK
        } ?: throw LdsException("Biometric info is missing its data block")
        return parseIso19794Face(bdb.content)
    }

    /** Builds an EF.DG2 file wrapping [imageBytes] (JPEG or JPEG 2000) as a single facial record. */
    fun buildDG2(imageBytes: ByteArray): ByteArray {
        val bdbContent = buildIso19794Face(imageBytes)
        val bdb = ASN1TaggedObject(
            ASN1TagClass.APPLICATION, ASN1Encoding.PRIMITIVE, TAG_BIOMETRIC_DATA_BLOCK, bdbContent
        )
        val formatOwner = ASN1TaggedObject(
            ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.PRIMITIVE, TAG_FORMAT_OWNER,
            twoByteBigEndian(FORMAT_OWNER_ISO_JTC1_SC37)
        )
        val formatType = ASN1TaggedObject(
            ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.PRIMITIVE, TAG_FORMAT_TYPE,
            twoByteBigEndian(FORMAT_TYPE_FACE)
        )
        val bht = ASN1TaggedObject(
            ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, TAG_BIOMETRIC_HEADER_TEMPLATE,
            concat(ASN1.encode(formatOwner), ASN1.encode(formatType))
        )
        val info = ASN1TaggedObject(
            ASN1TagClass.APPLICATION, ASN1Encoding.CONSTRUCTED, TAG_BIOMETRIC_INFO,
            concat(ASN1.encode(bht), ASN1.encode(bdb))
        )
        val group = ASN1TaggedObject(
            ASN1TagClass.APPLICATION, ASN1Encoding.CONSTRUCTED, TAG_BIOMETRIC_INFO_GROUP,
            concat(ASN1.encode(ASN1Integer(1)), ASN1.encode(info))
        )
        val ef = ASN1TaggedObject(
            ASN1TagClass.APPLICATION, ASN1Encoding.CONSTRUCTED, TAG_EF_DG2, ASN1.encode(group)
        )
        return ASN1.encode(ef)
    }

    private fun parseIso19794Face(record: ByteArray): ByteArray {
        if (record.size < GENERAL_HEADER_SIZE + FACIAL_RECORD_PREFIX_SIZE) {
            throw LdsException("ISO 19794-5 record is too short")
        }
        if (!record.copyOfRange(0, 4).contentEquals(FORMAT_ID)) {
            throw LdsException("ISO 19794-5 record has the wrong format identifier")
        }
        return record.copyOfRange(GENERAL_HEADER_SIZE + FACIAL_RECORD_PREFIX_SIZE, record.size)
    }

    private fun buildIso19794Face(imageBytes: ByteArray): ByteArray {
        val recordLength = GENERAL_HEADER_SIZE + FACIAL_RECORD_PREFIX_SIZE + imageBytes.size
        val generalHeader = concat(
            FORMAT_ID,
            VERSION_ID,
            fourByteBigEndian(recordLength),
            twoByteBigEndian(1), // number of facial images
        )
        val facialRecordHeader = concat(
            fourByteBigEndian(FACIAL_RECORD_PREFIX_SIZE + imageBytes.size),
            twoByteBigEndian(0), // number of feature points
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // gender..pose angle uncertainty
        )
        val imageInfo = concat(
            byteArrayOf(1, 0), // face image type (basic), image data type (0 = JPEG)
            twoByteBigEndian(0), // width (unknown for synthetic data)
            twoByteBigEndian(0), // height (unknown for synthetic data)
            byteArrayOf(1, 1), // image color space (RGB24), source type (static, unspecified)
            twoByteBigEndian(0), // device type (unknown)
            twoByteBigEndian(0), // quality (unspecified)
        )
        return concat(generalHeader, facialRecordHeader, imageInfo, imageBytes)
    }

    private fun decodeApplicationTag(bytes: ByteArray, tag: Int, name: String): ASN1TaggedObject {
        val obj = ASN1.decode(bytes) ?: throw LdsException("Failed to decode $name")
        return requireApplicationTag(obj, tag, name)
    }

    private fun requireApplicationTag(obj: ASN1Object, tag: Int, name: String): ASN1TaggedObject {
        val tagged = obj as? ASN1TaggedObject
            ?: throw LdsException("Expected $name to be an application-tagged object")
        if (tagged.cls != ASN1TagClass.APPLICATION || tagged.tag != tag) {
            throw LdsException("Expected $name (tag $tag), got tag ${tagged.tag} in class ${tagged.cls}")
        }
        return tagged
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val result = ByteArray(arrays.sumOf { it.size })
        var offset = 0
        for (array in arrays) {
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    private fun twoByteBigEndian(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun fourByteBigEndian(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()
    )
}
