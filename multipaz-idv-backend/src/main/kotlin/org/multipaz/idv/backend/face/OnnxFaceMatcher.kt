package org.multipaz.idv.backend.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A [FaceMatcher] that runs the OpenCV model zoo's YuNet detector and SFace recognizer with ONNX
 * Runtime, following OpenCV's `FaceDetectorYN` and `FaceRecognizerSF`:
 *
 * 1. YuNet finds the most confident face in each image, with five landmarks (eyes, nose tip,
 *    mouth corners).
 * 2. A similarity transform (rotation, uniform scale, translation) maps those landmarks onto
 *    SFace's reference positions, and the face is resampled into a 112x112 crop.
 * 3. SFace turns each crop into an embedding, and the score is the cosine similarity of the two
 *    embeddings. OpenCV suggests 0.363 as the same-person threshold for cosine similarity.
 *
 * Images must be upright: EXIF orientation is ignored.
 */
class OnnxFaceMatcher private constructor(
    private val environment: OrtEnvironment,
    private val detector: OrtSession,
    private val recognizer: OrtSession,
) : FaceMatcher, AutoCloseable {

    /** A detected face: its confidence and its five landmarks as (x, y) pairs, in image pixels. */
    internal class Face(val score: Float, val landmarks: FloatArray)

    override suspend fun score(selfie: ByteArray, portrait: ByteArray): Double = withContext(Dispatchers.Default) {
        val selfieImage = decode(selfie, FaceMatchException.SELFIE_UNREADABLE)
        val portraitImage = decode(portrait, FaceMatchException.PORTRAIT_UNREADABLE)
        val selfieFace = detect(selfieImage)
            ?: throw FaceMatchException(FaceMatchException.NO_FACE_IN_SELFIE, "No face found in the selfie")
        val portraitFace = detect(portraitImage)
            ?: throw FaceMatchException(FaceMatchException.NO_FACE_IN_PORTRAIT, "No face found in the portrait")
        cosineSimilarity(embed(selfieImage, selfieFace), embed(portraitImage, portraitFace))
    }

    /** Returns the most confident face in [image], or `null` if none reaches [MIN_FACE_SCORE]. */
    internal fun detect(image: BufferedImage): Face? {
        // Shrink to fit YuNet's fixed input, keeping the aspect ratio; the rest stays black. Like
        // OpenCV, which pads rather than resizes, smaller images aren't enlarged.
        val scale = min(1f, min(DETECTOR_SIZE.toFloat() / image.width, DETECTOR_SIZE.toFloat() / image.height))
        val input = BufferedImage(DETECTOR_SIZE, DETECTOR_SIZE, BufferedImage.TYPE_INT_RGB)
        val graphics = input.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(image, 0, 0, (image.width * scale).toInt(), (image.height * scale).toInt(), null)
        graphics.dispose()

        // YuNet takes raw 0-255 values in BGR order (OpenCV's blobFromImage defaults).
        val pixels = input.getRGB(0, 0, DETECTOR_SIZE, DETECTOR_SIZE, null, 0, DETECTOR_SIZE)
        val tensor = toTensor(pixels, DETECTOR_SIZE, bgr = true)

        var best: Face? = null
        OnnxTensor.createTensor(environment, tensor, longArrayOf(1, 3, DETECTOR_SIZE.toLong(), DETECTOR_SIZE.toLong()))
            .use { inputTensor ->
                detector.run(mapOf(DETECTOR_INPUT to inputTensor)).use { outputs ->
                    for (stride in STRIDES) {
                        val cls = outputs.floats("cls_$stride")
                        val obj = outputs.floats("obj_$stride")
                        val kps = outputs.floats("kps_$stride")
                        val columns = DETECTOR_SIZE / stride
                        for (index in cls.indices) {
                            val score = sqrt(cls[index].coerceIn(0f, 1f) * obj[index].coerceIn(0f, 1f))
                            if (score < MIN_FACE_SCORE || score <= (best?.score ?: 0f)) {
                                continue
                            }
                            val row = index / columns
                            val column = index % columns
                            val landmarks = FloatArray(10) { n ->
                                val cell = if (n % 2 == 0) column else row
                                (kps[index * 10 + n] + cell) * stride / scale
                            }
                            best = Face(score, landmarks)
                        }
                    }
                }
            }
        return best
    }

    /** Returns SFace's embedding of [face] in [image]. */
    internal fun embed(image: BufferedImage, face: Face): FloatArray {
        val crop = alignCrop(image, face.landmarks)
        // SFace takes raw 0-255 values in RGB order.
        val tensor = toTensor(crop, ALIGNED_SIZE, bgr = false)
        return OnnxTensor.createTensor(environment, tensor, longArrayOf(1, 3, ALIGNED_SIZE.toLong(), ALIGNED_SIZE.toLong()))
            .use { inputTensor ->
                recognizer.run(mapOf(RECOGNIZER_INPUT to inputTensor)).use { outputs ->
                    outputs.floats(RECOGNIZER_OUTPUT)
                }
            }
    }

    override fun close() {
        detector.close()
        recognizer.close()
    }

    private fun OrtSession.Result.floats(name: String): FloatArray {
        val buffer = (get(name).get() as OnnxTensor).floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        private const val DETECTOR_SIZE = 640
        private const val DETECTOR_INPUT = "input"
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val MIN_FACE_SCORE = 0.7f

        private const val ALIGNED_SIZE = 112
        private const val RECOGNIZER_INPUT = "data"
        private const val RECOGNIZER_OUTPUT = "fc1"

        // Where SFace expects the five landmarks in its 112x112 input (OpenCV's FaceRecognizerSF).
        private val REFERENCE_LANDMARKS = floatArrayOf(
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f,
        )

        // Passport portraits and selfies are at most a few thousand pixels across; refuse
        // anything larger before decoding it, so one request can't exhaust the server's memory.
        private const val MAX_DIMENSION = 6000

        /**
         * Loads the models from [modelsDirectory], checking their hashes.
         *
         * @throws FaceModelException if a model is missing or doesn't match its pinned hash.
         */
        fun load(modelsDirectory: File): OnnxFaceMatcher {
            val detectorModel = FaceModel.DETECTOR.load(modelsDirectory)
            val recognizerModel = FaceModel.RECOGNIZER.load(modelsDirectory)
            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)
            OrtSession.SessionOptions().use { options ->
                // SFace's graph lists its weights as inputs, which ONNX Runtime warns about.
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                val detector = environment.createSession(detectorModel, options)
                val recognizer = try {
                    environment.createSession(recognizerModel, options)
                } catch (e: Exception) {
                    detector.close()
                    throw e
                }
                return OnnxFaceMatcher(environment, detector, recognizer)
            }
        }

        internal fun decode(bytes: ByteArray, unreadableFlag: String): BufferedImage {
            try {
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                        ?: throw FaceMatchException(unreadableFlag, "Unrecognized image format")
                    try {
                        reader.input = input
                        val width = reader.getWidth(0)
                        val height = reader.getHeight(0)
                        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
                            throw FaceMatchException(unreadableFlag, "Image is ${width}x$height, outside 1-$MAX_DIMENSION")
                        }
                        return reader.read(0)
                    } finally {
                        reader.dispose()
                    }
                }
            } catch (e: IOException) {
                throw FaceMatchException(unreadableFlag, "Failed to decode image: ${e.message}")
            }
        }

        /**
         * Returns the similarity transform `[a, -b, tx; b, a, ty]` (as `[a, b, tx, ty]`) that maps
         * [from] onto [to] with the least squared error. Both hold (x, y) pairs.
         *
         * Treating points as complex numbers, the transform is `z -> c*z + t`; the least-squares
         * `c` is `sum(q * conj(p)) / sum(|p|^2)` over the centred points p and q. This is the
         * reflection-free solution OpenCV's Umeyama-based alignment gives.
         */
        internal fun similarityTransform(from: FloatArray, to: FloatArray): DoubleArray {
            val count = from.size / 2
            var fromX = 0.0
            var fromY = 0.0
            var toX = 0.0
            var toY = 0.0
            for (i in 0 until count) {
                fromX += from[2 * i]
                fromY += from[2 * i + 1]
                toX += to[2 * i]
                toY += to[2 * i + 1]
            }
            fromX /= count
            fromY /= count
            toX /= count
            toY /= count
            var real = 0.0
            var imaginary = 0.0
            var norm = 0.0
            for (i in 0 until count) {
                val px = from[2 * i] - fromX
                val py = from[2 * i + 1] - fromY
                val qx = to[2 * i] - toX
                val qy = to[2 * i + 1] - toY
                real += qx * px + qy * py
                imaginary += qy * px - qx * py
                norm += px * px + py * py
            }
            require(norm > 0.0) { "Landmarks are all at one point" }
            val a = real / norm
            val b = imaginary / norm
            return doubleArrayOf(a, b, toX - (a * fromX - b * fromY), toY - (b * fromX + a * fromY))
        }

        /**
         * Resamples [image] so the five [landmarks] land on SFace's reference positions, giving
         * a 112x112 crop as packed RGB pixels. Pixels outside the image are black.
         */
        internal fun alignCrop(image: BufferedImage, landmarks: FloatArray): IntArray {
            val (a, b, tx, ty) = similarityTransform(landmarks, REFERENCE_LANDMARKS).toList()
            // Invert z -> c*z + t, i.e. z = (w - t) / c, to find each output pixel's source.
            val norm = a * a + b * b
            val width = image.width
            val height = image.height
            val source = image.getRGB(0, 0, width, height, null, 0, width)
            val crop = IntArray(ALIGNED_SIZE * ALIGNED_SIZE)
            for (v in 0 until ALIGNED_SIZE) {
                for (u in 0 until ALIGNED_SIZE) {
                    val dx = u - tx
                    val dy = v - ty
                    val x = (a * dx + b * dy) / norm
                    val y = (a * dy - b * dx) / norm
                    crop[v * ALIGNED_SIZE + u] = sampleBilinear(source, width, height, x, y)
                }
            }
            return crop
        }

        private fun sampleBilinear(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            var result = 0
            for (shift in intArrayOf(16, 8, 0)) {
                fun at(px: Int, py: Int): Double =
                    if (px in 0 until width && py in 0 until height) {
                        (pixels[py * width + px] shr shift and 0xFF).toDouble()
                    } else {
                        0.0
                    }
                val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
                val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
                val value = (top * (1 - fy) + bottom * fy + 0.5).toInt().coerceIn(0, 255)
                result = (result shl 8) or value
            }
            return result
        }

        /** Packs RGB pixels into a planar (NCHW) tensor of raw 0-255 values. */
        private fun toTensor(pixels: IntArray, size: Int, bgr: Boolean): FloatBuffer {
            val plane = size * size
            val tensor = FloatBuffer.allocate(3 * plane)
            val shifts = if (bgr) intArrayOf(0, 8, 16) else intArrayOf(16, 8, 0)
            for (channel in 0 until 3) {
                for (i in 0 until plane) {
                    tensor.put(channel * plane + i, (pixels[i] shr shifts[channel] and 0xFF).toFloat())
                }
            }
            return tensor
        }

        internal fun cosineSimilarity(first: FloatArray, second: FloatArray): Double {
            var dot = 0.0
            var firstNorm = 0.0
            var secondNorm = 0.0
            for (i in first.indices) {
                dot += first[i] * second[i]
                firstNorm += first[i] * first[i]
                secondNorm += second[i] * second[i]
            }
            return dot / sqrt(firstNorm * secondNorm)
        }
    }
}
