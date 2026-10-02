package org.multipaz.idv.backend.image

import jj2000.j2k.encoder.Encoder
import jj2000.j2k.util.ParameterList
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Jp2DecoderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun jpegPassesThroughUnchanged() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        assertSame(jpeg, Jp2Decoder.toJpeg(jpeg))
    }

    @Test
    fun jp2FileIsConvertedToJpeg() {
        assertDecodesToTestImage(Jp2Decoder.toJpeg(encodeJpeg2000(jp2FileFormat = true)))
    }

    @Test
    fun bareCodestreamIsConvertedToJpeg() {
        val codestream = encodeJpeg2000(jp2FileFormat = false)
        assertEquals(0xFF.toByte(), codestream[0])
        assertEquals(0x4F.toByte(), codestream[1])
        assertDecodesToTestImage(Jp2Decoder.toJpeg(codestream))
    }

    @Test
    fun truncatedJpeg2000IsRejected() {
        val jp2 = encodeJpeg2000(jp2FileFormat = true)
        assertFailsWith<Jp2DecoderException> { Jp2Decoder.toJpeg(jp2.copyOf(40)) }
    }

    @Test
    fun unknownFormatIsRejected() {
        assertFailsWith<Jp2DecoderException> { Jp2Decoder.toJpeg(byteArrayOf(1, 2, 3, 4)) }
    }

    private fun assertDecodesToTestImage(jpeg: ByteArray) {
        val image = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(WIDTH, image.width)
        assertEquals(HEIGHT, image.height)
        // Lossless JPEG 2000, then JPEG at high quality: each quadrant keeps its colour.
        for ((x, y) in listOf(WIDTH / 4 to HEIGHT / 4, 3 * WIDTH / 4 to 3 * HEIGHT / 4)) {
            val expected = testColour(x, y)
            val actual = image.getRGB(x, y)
            for (shift in listOf(16, 8, 0)) {
                val difference = abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF))
                assertTrue(difference <= 12, "pixel ($x, $y): expected ${expected.toString(16)}, got ${actual.toString(16)}")
            }
        }
    }

    /** Encodes the test image, a red top-left and a blue bottom-right half, as JPEG 2000. */
    private fun encodeJpeg2000(jp2FileFormat: Boolean): ByteArray {
        val ppm = temporaryFolder.newFile("portrait.ppm")
        ppm.outputStream().use { out ->
            out.write("P6\n$WIDTH $HEIGHT\n255\n".encodeToByteArray())
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val rgb = testColour(x, y)
                    out.write(byteArrayOf((rgb shr 16).toByte(), (rgb shr 8).toByte(), rgb.toByte()))
                }
            }
        }
        val output = File(temporaryFolder.root, if (jp2FileFormat) "portrait.jp2" else "portrait.j2k")
        val defaults = ParameterList()
        Encoder.getAllParameters().forEach { parameter ->
            if (parameter[3] != null) {
                defaults[parameter[0]] = parameter[3]
            }
        }
        val parameters = ParameterList(defaults)
        parameters["i"] = ppm.absolutePath
        parameters["o"] = output.absolutePath
        parameters["lossless"] = "on"
        parameters["file_format"] = if (jp2FileFormat) "on" else "off"
        parameters["verbose"] = "off"
        val encoder = Encoder(parameters)
        encoder.run()
        assertEquals(0, encoder.exitCode)
        return output.readBytes()
    }

    private fun testColour(x: Int, y: Int): Int = if (x + y < WIDTH) 0xC03020 else 0x2040C0

    companion object {
        private const val WIDTH = 64
        private const val HEIGHT = 64
    }
}
