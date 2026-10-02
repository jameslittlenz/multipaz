package org.multipaz.idv.backend.image

import colorspace.ColorSpace
import jj2000.j2k.codestream.HeaderInfo
import jj2000.j2k.codestream.reader.BitstreamReaderAgent
import jj2000.j2k.codestream.reader.HeaderDecoder
import jj2000.j2k.decoder.Decoder
import jj2000.j2k.fileformat.reader.FileFormatReader
import jj2000.j2k.image.BlkImgDataSrc
import jj2000.j2k.image.DataBlkInt
import jj2000.j2k.image.ImgDataConverter
import jj2000.j2k.image.invcomptransf.InvCompTransf
import jj2000.j2k.io.RandomAccessIO
import jj2000.j2k.util.ISRandomAccessIO
import jj2000.j2k.util.ParameterList
import jj2000.j2k.wavelet.synthesis.InverseWT
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Thrown when [Jp2Decoder.toJpeg] is given image bytes it can't handle. */
class Jp2DecoderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Converts a DG2 portrait to JPEG for the Photo ID's `portrait` element.
 *
 * Passports encode DG2 as JPEG or JPEG 2000 (ISO/IEC 15444-1), the latter either as a JP2 file
 * or as a bare codestream. JPEG passes through unchanged. JPEG 2000 is decoded with JJ2000, the
 * codec `multipaz-compose` uses on Android, and re-encoded as JPEG.
 */
object Jp2Decoder {
    // JFIF/EXIF JPEG start-of-image marker.
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    // JP2 signature box, per ISO/IEC 15444-1 Annex I.
    private val JP2_MAGIC = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A
    )

    // A bare JPEG 2000 codestream: the SOC marker followed by the SIZ marker.
    private val J2K_MAGIC = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

    // Passport portraits are small (typically 15-30 KB as JPEG 2000), so keep detail.
    private const val JPEG_QUALITY = 0.9f

    // ICAO 9303 portraits are a few hundred pixels across; anything far larger is not a portrait,
    // and decoding it would cost memory a public server shouldn't spend on one request.
    private const val MAX_DIMENSION = 4096

    /**
     * Returns [imageBytes] as JPEG.
     *
     * @throws Jp2DecoderException if [imageBytes] is neither JPEG nor JPEG 2000, or is JPEG 2000
     *   that can't be decoded.
     */
    fun toJpeg(imageBytes: ByteArray): ByteArray {
        if (startsWith(imageBytes, JPEG_MAGIC)) {
            return imageBytes
        }
        if (startsWith(imageBytes, JP2_MAGIC) || startsWith(imageBytes, J2K_MAGIC)) {
            val image = try {
                decodeJpeg2000(imageBytes)
            } catch (e: Jp2DecoderException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                // JJ2000 reports malformed input with runtime and I/O exceptions, and with plain
                // java.lang.Error (e.g. "EOF reached before finding Contiguous Codestream Box").
                throw Jp2DecoderException("Failed to decode the JPEG 2000 portrait", e)
            }
            return encodeJpeg(image)
        }
        throw Jp2DecoderException("DG2 image is neither JPEG nor JPEG 2000")
    }

    private fun decodeJpeg2000(bytes: ByteArray): BufferedImage {
        val defaults = ParameterList()
        Decoder.getAllParameters()?.forEach { parameter ->
            if (parameter[3] != null) {
                defaults[parameter[0]] = parameter[3]
            }
        }
        val parameters = ParameterList(defaults)
        val input = ISRandomAccessIO(ByteArrayInputStream(bytes))
        val fileFormat = FileFormatReader(input)
        fileFormat.readFileFormat()
        val stream: RandomAccessIO = if (fileFormat.JP2FFUsed) {
            ISRandomAccessIO(
                ByteArrayInputStream(bytes, fileFormat.firstCodeStreamPos, fileFormat.firstCodeStreamLength)
            )
        } else {
            input
        }

        val headerInfo = HeaderInfo()
        val header = HeaderDecoder(stream, parameters, headerInfo)
        val componentCount = header.numComps
        if (componentCount != 1 && componentCount < 3) {
            throw Jp2DecoderException("Unsupported JPEG 2000 portrait with $componentCount components")
        }
        val decoderSpecs = header.decoderSpecs
        val bitstream = BitstreamReaderAgent.createInstance(stream, header, parameters, decoderSpecs, false, headerInfo)
        val depths = IntArray(componentCount) { header.getOriginalBitDepth(it) }
        val entropyDecoder = header.createEntropyDecoder(bitstream, parameters)
        val roiDescaler = header.createROIDeScaler(entropyDecoder, parameters, decoderSpecs)
        val dequantizer = header.createDequantizer(roiDescaler, depths, decoderSpecs)
        val inverseWavelet = InverseWT.createInstance(dequantizer, decoderSpecs)
        inverseWavelet.setImgResLevel(bitstream.imgRes)
        val inverseComponents = InvCompTransf(ImgDataConverter(inverseWavelet, 0), decoderSpecs, depths, parameters)
        inverseComponents.setTile(0, 0)

        var source: BlkImgDataSrc = inverseComponents
        if (fileFormat.JP2FFUsed) {
            val colorSpace = ColorSpace(input, header, parameters)
            source = header.createChannelDefinitionMapper(source, colorSpace)
            source = header.createResampler(source, colorSpace)
            source = header.createPalettizedColorSpaceMapper(source, colorSpace)
            source = header.createColorSpaceMapper(source, colorSpace)
        }

        val width = source.imgWidth
        val height = source.imgHeight
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            throw Jp2DecoderException("JPEG 2000 portrait is ${width}x$height, outside 1-$MAX_DIMENSION")
        }
        // Grey images repeat their one component for red, green and blue.
        val channels = if (componentCount >= 3) intArrayOf(0, 1, 2) else intArrayOf(0, 0, 0)
        val fixedPoints = IntArray(3) { source.getFixedPoint(channels[it]) }
        // Unsigned samples are stored centred on zero; this undoes the DC level shift.
        val levelShifts = IntArray(3) {
            val component = channels[it]
            if (header.isOriginalSigned(component)) 0 else 1 shl (header.getOriginalBitDepth(component) - 1)
        }
        // Scale samples deeper than 8 bits down to 8.
        val depthShifts = IntArray(3) { maxOf(0, header.getOriginalBitDepth(channels[it]) - 8) }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(width)
        val blocks = Array(3) { DataBlkInt() }
        for (y in 0 until height) {
            val samples = Array(3) { channel ->
                if (channel > 0 && channels[channel] == channels[0]) {
                    null
                } else {
                    val block = blocks[channel]
                    block.ulx = 0
                    block.uly = y
                    block.w = width
                    block.h = 1
                    source.getInternCompData(block, channels[channel]) as DataBlkInt
                }
            }
            for (x in 0 until width) {
                var rgb = 0
                for (channel in 0 until 3) {
                    val block = samples[channel] ?: samples[0]!!
                    val raw = block.data[block.offset + x]
                    val value = ((raw shr fixedPoints[channel]) + levelShifts[channel]) shr depthShifts[channel]
                    rgb = (rgb shl 8) or value.coerceIn(0, 255)
                }
                row[x] = rgb
            }
            image.setRGB(0, y, width, 1, row, 0, width)
        }
        return image
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam
                parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameters.compressionQuality = JPEG_QUALITY
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}
