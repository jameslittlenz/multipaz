package org.multipaz.samples.validatopia.shared.idv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivenessChallengeTest {
    private fun face(eyes: Float = 0.95f, yaw: Float = 0f, id: Int? = 1, width: Float = 0.5f) =
        DetectedFace(trackingId = id, leftEyeOpen = eyes, rightEyeOpen = eyes, yawDegrees = yaw, rollDegrees = 0f, widthFraction = width)

    private fun LivenessChallenge.feed(vararg faces: DetectedFace): Boolean =
        faces.map { onFrame(listOf(it)) }.last()

    @Test
    fun blinkThenHoldStillTakesTheSelfie() {
        val challenge = LivenessChallenge(LivenessAction.BLINK)
        challenge.feed(face())
        assertEquals(LivenessChallenge.Stage.PERFORM_ACTION, challenge.stage)
        challenge.feed(face(eyes = 0.05f), face())
        assertEquals(LivenessChallenge.Stage.HOLD_STILL, challenge.stage)
        assertFalse(challenge.feed(face(), face()))
        assertTrue(challenge.feed(face()))
        assertEquals(LivenessChallenge.Stage.DONE, challenge.stage)
    }

    @Test
    fun openEyesAloneAreNotABlink() {
        val challenge = LivenessChallenge(LivenessAction.BLINK)
        challenge.feed(face(), face(), face(), face(), face())
        assertEquals(LivenessChallenge.Stage.PERFORM_ACTION, challenge.stage)
    }

    @Test
    fun headTurnThenBackToCentre() {
        val challenge = LivenessChallenge(LivenessAction.TURN_HEAD)
        challenge.feed(face(), face(yaw = 15f))
        assertEquals(LivenessChallenge.Stage.PERFORM_ACTION, challenge.stage)
        challenge.feed(face(yaw = -32f), face(yaw = 3f))
        assertEquals(LivenessChallenge.Stage.HOLD_STILL, challenge.stage)
    }

    @Test
    fun aDifferentFaceStartsOver() {
        val challenge = LivenessChallenge(LivenessAction.BLINK)
        challenge.feed(face(), face(eyes = 0.05f))
        challenge.feed(face(id = 2))
        // The new face is frontal, so it's asked to blink, but the first face's closed eyes don't count.
        assertEquals(LivenessChallenge.Stage.PERFORM_ACTION, challenge.stage)
        challenge.feed(face(id = 2))
        assertEquals(LivenessChallenge.Stage.PERFORM_ACTION, challenge.stage)
    }

    @Test
    fun twoFacesOrASmallFaceAreRefused() {
        val challenge = LivenessChallenge(LivenessAction.BLINK)
        challenge.onFrame(listOf(face(), face(id = 2)))
        assertEquals(LivenessChallenge.Stage.ONE_FACE_ONLY, challenge.stage)
        challenge.feed(face(width = 0.1f))
        assertEquals(LivenessChallenge.Stage.MOVE_CLOSER, challenge.stage)
        assertEquals("Move a little closer", challenge.instruction)
    }
}
