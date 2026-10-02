package org.multipaz.samples.validatopia.wallet.passport

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.samples.validatopia.shared.idv.DetectedFace
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/** ML Kit analysis of camera frames, with its models bundled in the app (nothing leaves the phone). */
object CameraAnalysis {
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()
        )
    }

    private const val SELFIE_MAX_DIMENSION = 1024
    private const val SELFIE_JPEG_QUALITY = 90

    /**
     * Returns the text ML Kit reads in [frame], one line per line of text.
     *
     * @param binarize first reduce the frame to black and white with a local threshold, which
     *   strips the light, patterned security printing behind a passport's MRZ.
     */
    suspend fun recognizeText(frame: CameraFrame, binarize: Boolean): String {
        val proxy = frame.cameraImage.imageProxy
        val image = if (binarize) {
            InputImage.fromBitmap(binarized(proxy.toBitmap()), proxy.imageInfo.rotationDegrees)
        } else {
            inputImage(proxy)
        }
        val text = textRecognizer.process(image).await()
        return text.textBlocks.flatMap { block -> block.lines.map { it.text } }.joinToString("\n")
    }

    /**
     * Black where a pixel is clearly darker than its surroundings, white elsewhere (adaptive mean
     * thresholding over a window an eighth of the width across, using an integral image).
     */
    private fun binarized(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        // integral[(y + 1) * (width + 1) + (x + 1)] is the sum of luminance above and left of (x, y).
        val stride = width + 1
        val integral = LongArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += luminance[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }
        val half = maxOf(8, width / 16)
        for (y in 0 until height) {
            val top = maxOf(0, y - half)
            val bottom = min(height, y + half + 1)
            for (x in 0 until width) {
                val left = maxOf(0, x - half)
                val right = min(width, x + half + 1)
                val sum = integral[bottom * stride + right] - integral[top * stride + right] -
                    integral[bottom * stride + left] + integral[top * stride + left]
                val mean = sum / ((bottom - top) * (right - left))
                pixels[y * width + x] = if (luminance[y * width + x] * 100 < mean * THRESHOLD_PERCENT) BLACK else WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private const val THRESHOLD_PERCENT = 80
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Returns the faces in [frame]. */
    suspend fun detectFaces(frame: CameraFrame): List<DetectedFace> {
        val proxy = frame.cameraImage.imageProxy
        val faces = faceDetector.process(inputImage(proxy)).await()
        // Face widths are in the upright image, whose width depends on the rotation.
        val rotation = proxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        return faces.map { face ->
            DetectedFace(
                trackingId = face.trackingId,
                leftEyeOpen = face.leftEyeOpenProbability,
                rightEyeOpen = face.rightEyeOpenProbability,
                yawDegrees = face.headEulerAngleY,
                rollDegrees = face.headEulerAngleZ,
                widthFraction = face.boundingBox.width().toFloat() / uprightWidth,
            )
        }
    }

    /**
     * Returns [frame] as an upright JPEG no larger than [SELFIE_MAX_DIMENSION] pixels across. The
     * pixels are rotated, rather than tagged with an orientation, since the issuer ignores EXIF.
     */
    fun uprightJpeg(frame: CameraFrame): ByteArray {
        val proxy = frame.cameraImage.imageProxy
        val bitmap = proxy.toBitmap()
        val scale = minOf(1f, SELFIE_MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            postScale(scale, scale)
        }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return ByteArrayOutputStream().use { output ->
            upright.compress(Bitmap.CompressFormat.JPEG, SELFIE_JPEG_QUALITY, output)
            output.toByteArray()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun inputImage(proxy: ImageProxy): InputImage =
        InputImage.fromMediaImage(proxy.image!!, proxy.imageInfo.rotationDegrees)

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
