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
 * The first facial image in an EF.DG2 file, encoded per ISO/IEC 19794-5.
 *
 * @property image the image bytes, JPEG or JPEG 2000.
 * @property templateCount the number of biometric templates in DG2.
 * @property imageCount the number of facial images in the first template's record.
 * @property featurePointCount the number of feature points before the image.
 * @property imageDataType the record's image data type: [IMAGE_DATA_TYPE_JPEG] or
 *   [IMAGE_DATA_TYPE_JPEG2000].
 * @property width the image width the record states, or 0 if unstated.
 * @property height the image height the record states, or 0 if unstated.
 */
class Dg2Face(
    val image: ByteArray,
    val templateCount: Int,
    val imageCount: Int,
    val featurePointCount: Int,
    val imageDataType: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        const val IMAGE_DATA_TYPE_JPEG = 0
        const val IMAGE_DATA_TYPE_JPEG2000 = 1
    }
}

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
    private const val FEATURE_POINT_SIZE = 8
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
     * Extracts the portrait image bytes (JPEG or JPEG 2000, per the record's image data type) from
     * an EF.DG2 file. See [parseDG2Face].
     */
    fun parseDG2(dg2: ByteArray): ByteArray = parseDG2Face(dg2).image

    /**
     * Parses the first facial image of an EF.DG2 file.
     *
     * This supports the legacy ISO/IEC 19794-5 encoding, taking the first facial image of the
     * first biometric template and skipping any feature points before its image. ISO/IEC 39794-5,
     * the newer face-image standard some passports use instead, isn't supported yet.
     */
    fun parseDG2Face(dg2: ByteArray): Dg2Face {
        val ef = decodeApplicationTag(dg2, TAG_EF_DG2, "EF.DG2")
        val group = decodeApplicationTag(ef.content, TAG_BIOMETRIC_INFO_GROUP, "biometric info group")
        val groupElements = ASN1.decodeMultiple(group.content)
        if (groupElements.size < 2 || groupElements[0] !is ASN1Integer) {
            throw LdsException("Biometric info group is missing its instance count")
        }
        val instanceCount = (groupElements[0] as ASN1Integer).toLong().toInt()
        val info = requireApplicationTag(groupElements[1], TAG_BIOMETRIC_INFO, "biometric info")
        val infoElements = ASN1.decodeMultiple(info.content)
        val bdb = infoElements.filterIsInstance<ASN1TaggedObject>().firstOrNull {
            it.cls == ASN1TagClass.APPLICATION && it.tag == TAG_BIOMETRIC_DATA_BLOCK
        } ?: throw LdsException("Biometric info is missing its data block")
        if (bdb.enc == ASN1Encoding.CONSTRUCTED) {
            // 0x7F2E rather than 0x5F2E: an ISO/IEC 39794-5 data block.
            throw LdsException("ISO/IEC 39794-5 face data isn't supported")
        }
        return parseIso19794Face(bdb.content, instanceCount)
    }

    /**
     * Builds an EF.DG2 file wrapping [imageBytes] (JPEG or JPEG 2000) as a single facial record.
     *
     * @param featurePointCount the number of (zeroed) feature points to put before the image, as
     *   some passports do.
     */
    fun buildDG2(imageBytes: ByteArray, featurePointCount: Int = 0): ByteArray {
        val bdbContent = buildIso19794Face(imageBytes, featurePointCount)
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

    private fun parseIso19794Face(record: ByteArray, templateCount: Int): Dg2Face {
        if (record.size < GENERAL_HEADER_SIZE + FACIAL_RECORD_PREFIX_SIZE) {
            throw LdsException("ISO 19794-5 record is too short")
        }
        if (!record.copyOfRange(0, 4).contentEquals(FORMAT_ID)) {
            throw LdsException("ISO 19794-5 record has the wrong format identifier")
        }
        val imageCount = readTwoBytes(record, GENERAL_HEADER_SIZE - 2)
        if (imageCount < 1) {
            throw LdsException("ISO 19794-5 record has no facial images")
        }
        // The first facial record: its header, then the feature points, then the image
        // information, then the image itself, which runs to the end of the facial record.
        val facialRecordStart = GENERAL_HEADER_SIZE
        val facialRecordLength = readFourBytes(record, facialRecordStart)
        val featurePointCount = readTwoBytes(record, facialRecordStart + 4)
        val imageInfoStart = facialRecordStart + FACIAL_RECORD_HEADER_SIZE + featurePointCount * FEATURE_POINT_SIZE
        val imageStart = imageInfoStart + IMAGE_INFO_SIZE
        val imageEnd = facialRecordStart.toLong() + facialRecordLength
        if (imageEnd > record.size || imageStart > imageEnd) {
            throw LdsException(
                "ISO 19794-5 facial record length $facialRecordLength with $featurePointCount " +
                    "feature points doesn't fit the ${record.size}-byte record"
            )
        }
        return Dg2Face(
            image = record.copyOfRange(imageStart, imageEnd.toInt()),
            templateCount = templateCount,
            imageCount = imageCount,
            featurePointCount = featurePointCount,
            imageDataType = record[imageInfoStart + 1].toInt() and 0xFF,
            width = readTwoBytes(record, imageInfoStart + 2),
            height = readTwoBytes(record, imageInfoStart + 4),
        )
    }

    private fun buildIso19794Face(imageBytes: ByteArray, featurePointCount: Int): ByteArray {
        val featurePoints = ByteArray(featurePointCount * FEATURE_POINT_SIZE)
        val facialRecordLength = FACIAL_RECORD_PREFIX_SIZE + featurePoints.size + imageBytes.size
        val recordLength = GENERAL_HEADER_SIZE + facialRecordLength
        val generalHeader = concat(
            FORMAT_ID,
            VERSION_ID,
            fourByteBigEndian(recordLength),
            twoByteBigEndian(1), // number of facial images
        )
        val facialRecordHeader = concat(
            fourByteBigEndian(facialRecordLength),
            twoByteBigEndian(featurePointCount),
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
        return concat(generalHeader, facialRecordHeader, featurePoints, imageInfo, imageBytes)
    }

    private fun readTwoBytes(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readFourBytes(bytes: ByteArray, offset: Int): Long =
        (readTwoBytes(bytes, offset).toLong() shl 16) or readTwoBytes(bytes, offset + 2).toLong()

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
