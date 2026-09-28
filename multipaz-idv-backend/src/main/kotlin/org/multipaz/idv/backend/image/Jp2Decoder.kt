package org.multipaz.idv.backend.image

/** Thrown when [Jp2Decoder.toJpeg] is given image bytes it can't handle. */
class Jp2DecoderException(message: String) : Exception(message)

/**
 * Converts a DG2 portrait to JPEG for the Photo ID's `portrait` element.
 *
 * Real NZ/AU passports may encode DG2 as JPEG 2000 (ISO/IEC 15444-1), which needs an actual
 * JPEG 2000 codec to decode. Pulling one in (e.g. a `jai-imageio-jpeg2000` dependency) is
 * deferred until M6, when real NZ/AU chip reads are available to test against — see
 * `docs/validatopia/PLAN.md`'s M1 discovery task and M6 milestone. Until then, [toJpeg] passes
 * JPEG bytes through unchanged (all synthetic test passports use JPEG, per
 * `SyntheticPassportFactory`) and fails loudly on JPEG 2000 input rather than silently returning
 * bytes a viewer can't render.
 */
object Jp2Decoder {
    // JFIF/EXIF JPEG start-of-image marker.
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    // JP2 signature box, per ISO/IEC 15444-1 Annex I.
    private val JP2_MAGIC = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A
    )

    /**
     * Returns [imageBytes] as JPEG.
     *
     * @throws Jp2DecoderException if [imageBytes] is JPEG 2000 (decoding it is deferred to M6) or
     *   is neither JPEG nor JPEG 2000.
     */
    fun toJpeg(imageBytes: ByteArray): ByteArray {
        if (startsWith(imageBytes, JPEG_MAGIC)) {
            return imageBytes
        }
        if (startsWith(imageBytes, JP2_MAGIC)) {
            throw Jp2DecoderException(
                "JPEG 2000 decoding is deferred to M6, pending a real NZ/AU passport sample " +
                    "and a vetted JPEG 2000 codec dependency"
            )
        }
        throw Jp2DecoderException("DG2 image is neither JPEG nor JPEG 2000")
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}
