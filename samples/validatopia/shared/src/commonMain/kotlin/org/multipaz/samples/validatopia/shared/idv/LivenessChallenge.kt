package org.multipaz.samples.validatopia.shared.idv

import kotlin.math.abs

/**
 * One face found in a camera frame, as a face detector reports it.
 *
 * @property trackingId identifies the same face across frames, or `null` if the detector lost it.
 * @property leftEyeOpen probability the left eye is open, 0 to 1, or `null` if unknown.
 * @property rightEyeOpen probability the right eye is open, 0 to 1, or `null` if unknown.
 * @property yawDegrees how far the head is turned left or right; 0 faces the camera.
 * @property rollDegrees how far the head is tilted sideways; 0 is upright.
 * @property widthFraction the face's width as a fraction of the frame's width.
 */
data class DetectedFace(
    val trackingId: Int?,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val yawDegrees: Float,
    val rollDegrees: Float,
    val widthFraction: Float,
)

/** The action the holder chooses to prove they're present: there's always a choice (WCAG 2.2). */
enum class LivenessAction(val instruction: String) {
    BLINK("Blink slowly"),
    TURN_HEAD("Turn your head to one side, then back to face the camera"),
}

/**
 * On-device liveness check: the holder performs [action] in front of the camera, then holds still
 * facing it while a selfie is taken. Feed it every frame's faces with [onFrame].
 *
 * The same face must stay in view throughout; if it's lost, or another face appears, the check
 * starts again. This raises the bar against a held-up photo, but doesn't stop a determined
 * attacker: the server's face match against the passport chip's photo is the real check.
 */
class LivenessChallenge(val action: LivenessAction) {
    /** Where the check is, with what to tell the holder. */
    enum class Stage(val instruction: String) {
        FIND_FACE("Fit your face inside the frame"),
        MOVE_CLOSER("Move a little closer"),
        ONE_FACE_ONLY("Make sure only your face is in view"),
        PERFORM_ACTION(""),
        HOLD_STILL("Now look straight at the camera and hold still"),
        DONE("Done"),
    }

    var stage: Stage = Stage.FIND_FACE
        private set

    /** What to tell the holder now. */
    val instruction: String
        get() = if (stage == Stage.PERFORM_ACTION) action.instruction else stage.instruction

    private var trackingId: Int? = null
    private var eyesClosedSeen = false
    private var turnedSeen = false
    private var steadyFrames = 0

    /**
     * Updates the check with one frame's [faces].
     *
     * @return `true` if this frame should be kept as the selfie; the stage is then [Stage.DONE].
     */
    fun onFrame(faces: List<DetectedFace>): Boolean {
        if (stage == Stage.DONE) return false
        val face = when (faces.size) {
            0 -> return restart(Stage.FIND_FACE)
            1 -> faces[0]
            else -> return restart(Stage.ONE_FACE_ONLY)
        }
        if (face.widthFraction < MIN_FACE_WIDTH) {
            return restart(Stage.MOVE_CLOSER)
        }
        if (trackingId != null && face.trackingId != trackingId) {
            // A different face, or the detector lost track: start over with this one.
            restart(Stage.FIND_FACE)
        }
        trackingId = face.trackingId

        when (stage) {
            Stage.FIND_FACE, Stage.MOVE_CLOSER, Stage.ONE_FACE_ONLY -> {
                if (isFrontal(face)) stage = Stage.PERFORM_ACTION
            }
            Stage.PERFORM_ACTION -> if (actionPerformed(face)) stage = Stage.HOLD_STILL
            Stage.HOLD_STILL -> {
                steadyFrames = if (isFrontal(face) && eyesOpen(face)) steadyFrames + 1 else 0
                if (steadyFrames >= STEADY_FRAMES) {
                    stage = Stage.DONE
                    return true
                }
            }
            Stage.DONE -> Unit
        }
        return false
    }

    private fun actionPerformed(face: DetectedFace): Boolean = when (action) {
        LivenessAction.BLINK -> {
            val left = face.leftEyeOpen
            val right = face.rightEyeOpen
            if (left != null && right != null) {
                if (left < EYE_CLOSED && right < EYE_CLOSED) eyesClosedSeen = true
                eyesClosedSeen && left > EYE_OPEN && right > EYE_OPEN
            } else {
                false
            }
        }
        LivenessAction.TURN_HEAD -> {
            if (abs(face.yawDegrees) > TURNED_YAW) turnedSeen = true
            turnedSeen && abs(face.yawDegrees) < FRONTAL_YAW
        }
    }

    private fun restart(next: Stage): Boolean {
        stage = next
        trackingId = null
        eyesClosedSeen = false
        turnedSeen = false
        steadyFrames = 0
        return false
    }

    private fun isFrontal(face: DetectedFace) = abs(face.yawDegrees) < FRONTAL_YAW && abs(face.rollDegrees) < FRONTAL_ROLL

    private fun eyesOpen(face: DetectedFace) =
        (face.leftEyeOpen ?: 1f) > EYE_OPEN && (face.rightEyeOpen ?: 1f) > EYE_OPEN

    companion object {
        private const val MIN_FACE_WIDTH = 0.3f
        private const val FRONTAL_YAW = 10f
        private const val FRONTAL_ROLL = 12f
        private const val TURNED_YAW = 25f
        private const val EYE_CLOSED = 0.2f
        private const val EYE_OPEN = 0.7f
        private const val STEADY_FRAMES = 3
    }
}
