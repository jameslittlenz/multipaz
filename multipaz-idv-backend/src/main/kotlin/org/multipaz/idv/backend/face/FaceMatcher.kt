package org.multipaz.idv.backend.face

/**
 * Server-side face matching: compares a liveness-checked selfie against a passport's DG2 portrait.
 *
 * The real implementation (YuNet detection/alignment, SFace embedding, cosine similarity) is
 * deferred: it needs a vetted model-download pipeline (`downloadFaceModels`, pinned by SHA-256)
 * that isn't wired up yet (see `docs/validatopia/PLAN.md`'s Component C). [FakeFaceMatcher] is
 * used everywhere until then, including by the Validatopia server profile.
 */
interface FaceMatcher {
    /**
     * Compares two face images and returns a similarity score.
     *
     * @param selfie the liveness-checked selfie captured by the wallet.
     * @param portrait the portrait image decoded from the passport's DG2.
     * @return a similarity score in `[0.0, 1.0]`, higher meaning more similar.
     */
    suspend fun score(selfie: ByteArray, portrait: ByteArray): Double
}

/**
 * A [FaceMatcher] that always returns a fixed score, for tests and for M2's end-to-end
 * demonstration ahead of the real ONNX-based matcher.
 */
class FakeFaceMatcher(private val fixedScore: Double = 1.0) : FaceMatcher {
    override suspend fun score(selfie: ByteArray, portrait: ByteArray): Double = fixedScore
}
