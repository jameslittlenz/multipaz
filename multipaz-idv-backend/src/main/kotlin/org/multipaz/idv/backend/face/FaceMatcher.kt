package org.multipaz.idv.backend.face

/**
 * Server-side face matching: compares a liveness-checked selfie against a passport's DG2 portrait.
 *
 * [OnnxFaceMatcher] is the real implementation. [FakeFaceMatcher] is for tests.
 */
interface FaceMatcher {
    /**
     * Compares two face images and returns a similarity score.
     *
     * @param selfie the liveness-checked selfie captured by the wallet.
     * @param portrait the portrait image decoded from the passport's DG2.
     * @return a similarity score in `[-1.0, 1.0]`, higher meaning more similar.
     * @throws FaceMatchException if either image can't be read or holds no face.
     */
    suspend fun score(selfie: ByteArray, portrait: ByteArray): Double
}

/**
 * Thrown by [FaceMatcher.score] when it can't compare the images.
 *
 * @property flag the identity-proofing flag reported to the wallet and the audit log.
 */
class FaceMatchException(val flag: String, message: String) : Exception(message) {
    companion object {
        const val SELFIE_UNREADABLE = "SELFIE_UNREADABLE"
        const val PORTRAIT_UNREADABLE = "PORTRAIT_UNREADABLE"
        const val NO_FACE_IN_SELFIE = "NO_FACE_IN_SELFIE"
        const val NO_FACE_IN_PORTRAIT = "NO_FACE_IN_PORTRAIT"
        const val FACE_MATCHER_UNAVAILABLE = "FACE_MATCHER_UNAVAILABLE"
    }
}

/** A [FaceMatcher] that always returns a fixed score, for tests. */
class FakeFaceMatcher(private val fixedScore: Double = 1.0) : FaceMatcher {
    override suspend fun score(selfie: ByteArray, portrait: ByteArray): Double = fixedScore
}

/**
 * A [FaceMatcher] for a server whose face models aren't installed: every comparison fails, so
 * the passport path rejects everyone rather than accepting everyone.
 */
class UnavailableFaceMatcher(private val reason: String) : FaceMatcher {
    override suspend fun score(selfie: ByteArray, portrait: ByteArray): Double =
        throw FaceMatchException(FaceMatchException.FACE_MATCHER_UNAVAILABLE, reason)
}
