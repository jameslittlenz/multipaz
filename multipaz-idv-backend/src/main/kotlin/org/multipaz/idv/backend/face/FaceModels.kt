package org.multipaz.idv.backend.face

import java.io.File
import java.security.MessageDigest

/**
 * The ONNX models [OnnxFaceMatcher] uses, from the OpenCV model zoo, pinned by SHA-256.
 *
 * `downloadFaceModels` in `multipaz-idv-backend/build.gradle.kts` fetches the same files with the
 * same hashes; keep the two in step. The models are never committed.
 */
enum class FaceModel(val fileName: String, val sha256Hex: String) {
    /** YuNet face detector (MIT), with five landmarks per face. */
    DETECTOR(
        fileName = "face_detection_yunet_2023mar.onnx",
        sha256Hex = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    ),

    /** SFace face recognizer (Apache-2.0), giving a 128-value embedding of an aligned face. */
    RECOGNIZER(
        fileName = "face_recognition_sface_2021dec.onnx",
        sha256Hex = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
    );

    /**
     * Reads this model from [directory], checking its hash.
     *
     * @throws FaceModelException if the file is missing or doesn't match [sha256Hex].
     */
    fun load(directory: File): ByteArray {
        val file = File(directory, fileName)
        if (!file.isFile) {
            throw FaceModelException("Face model '${file.absolutePath}' is missing")
        }
        val bytes = file.readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != sha256Hex) {
            throw FaceModelException("Face model '${file.absolutePath}' has SHA-256 $actual, expected $sha256Hex")
        }
        return bytes
    }
}

/** Thrown when a face model can't be loaded. */
class FaceModelException(message: String) : Exception(message)
