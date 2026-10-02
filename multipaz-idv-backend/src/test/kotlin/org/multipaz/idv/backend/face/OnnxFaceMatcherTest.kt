package org.multipaz.idv.backend.face

import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.multipaz.idv.backend.settings.IdvSettingsRecord
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.RescaleOp
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnnxFaceMatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun similarityTransformRecoversRotationScaleAndTranslation() {
        val angle = 0.3
        val scale = 1.7
        val a = scale * cos(angle)
        val b = scale * sin(angle)
        val from = floatArrayOf(10f, 20f, 50f, 22f, 30f, 45f, 15f, 70f, 48f, 68f)
        val to = FloatArray(from.size) { i ->
            val x = from[i - i % 2]
            val y = from[i - i % 2 + 1]
            (if (i % 2 == 0) a * x - b * y + 5.0 else b * x + a * y - 7.0).toFloat()
        }
        val transform = OnnxFaceMatcher.similarityTransform(from, to)
        val expected = doubleArrayOf(a, b, 5.0, -7.0)
        for (i in expected.indices) {
            assertEquals(expected[i], transform[i], 1e-4)
        }
    }

    @Test
    fun modelWithWrongHashIsRefused() {
        val directory = temporaryFolder.newFolder()
        File(directory, FaceModel.DETECTOR.fileName).writeText("not the model")
        assertFailsWith<FaceModelException> { OnnxFaceMatcher.load(directory) }
    }

    @Test
    fun missingModelIsRefused() {
        assertFailsWith<FaceModelException> { OnnxFaceMatcher.load(temporaryFolder.newFolder()) }
    }

    @Test
    fun samePersonScoresHigh() = runTest {
        val claudia = persona("claudia.jpg")
        val score = matcher().score(selfie = png(rotatedAndBrightened(claudia)), portrait = claudia)
        // OpenCV's own FaceDetectorYN and FaceRecognizerSF score this pair 0.925.
        assertTrue(score > 0.85, "score $score")
    }

    @Test
    fun differentPeopleScoreBelowTheDefaultThreshold() = runTest {
        val score = matcher().score(selfie = persona("richard.jpg"), portrait = persona("claudia.jpg"))
        // OpenCV scores this pair 0.363, its own suggested threshold, which is why the default
        // threshold is higher.
        assertTrue(score < IdvSettingsRecord.DEFAULT_FACE_MATCH_THRESHOLD, "score $score")
    }

    @Test
    fun imageWithoutAFaceIsRejected() = runTest {
        val blank = BufferedImage(400, 500, BufferedImage.TYPE_INT_RGB)
        blank.createGraphics().apply { color = Color.LIGHT_GRAY; fillRect(0, 0, 400, 500); dispose() }
        val error = assertFailsWith<FaceMatchException> {
            matcher().score(selfie = png(blank), portrait = persona("claudia.jpg"))
        }
        assertEquals(FaceMatchException.NO_FACE_IN_SELFIE, error.flag)
    }

    @Test
    fun unreadableImageIsRejected() {
        val error = assertFailsWith<FaceMatchException> {
            OnnxFaceMatcher.decode(byteArrayOf(1, 2, 3, 4), FaceMatchException.SELFIE_UNREADABLE)
        }
        assertEquals(FaceMatchException.SELFIE_UNREADABLE, error.flag)
    }

    @Test
    fun oversizedImageIsRejectedBeforeDecoding() {
        val error = assertFailsWith<FaceMatchException> {
            OnnxFaceMatcher.decode(png(BufferedImage(7000, 8, BufferedImage.TYPE_INT_RGB)), FaceMatchException.PORTRAIT_UNREADABLE)
        }
        assertEquals(FaceMatchException.PORTRAIT_UNREADABLE, error.flag)
    }

    /** A different-looking photo of the same face: rotated, smaller, offset and brighter. */
    private fun rotatedAndBrightened(jpeg: ByteArray): BufferedImage {
        val source = ImageIO.read(jpeg.inputStream())
        val result = BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB)
        val graphics = result.createGraphics()
        graphics.color = Color.GRAY
        graphics.fillRect(0, 0, result.width, result.height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val transform = AffineTransform()
        transform.translate(60.0, 80.0)
        transform.rotate(Math.toRadians(-10.0), source.width * 0.3, source.height * 0.3)
        transform.scale(0.6, 0.6)
        graphics.drawImage(source, transform, null)
        graphics.dispose()
        return RescaleOp(1.1f, 15f, null).filter(result, null)
    }

    private fun persona(name: String): ByteArray = File(System.getProperty("personasDir"), name).readBytes()

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun matcher(): OnnxFaceMatcher {
        val directory = File(System.getProperty("faceModelsDir") ?: "")
        assumeTrue(
            "Face models not downloaded; run :multipaz-idv-backend:downloadFaceModels",
            FaceModel.entries.all { File(directory, it.fileName).isFile }
        )
        return sharedMatcher ?: OnnxFaceMatcher.load(directory).also { sharedMatcher = it }
    }

    companion object {
        private var sharedMatcher: OnnxFaceMatcher? = null

        @JvmStatic
        @AfterClass
        fun closeMatcher() {
            sharedMatcher?.close()
        }
    }
}
