package org.multipaz.samples.validatopia.shared.branding

import kotlin.test.Test
import kotlin.test.assertEquals

class ContrastRatioTest {
    @Test
    fun blackOnWhiteIsMaximumContrast() {
        assertEquals(21.0, ContrastRatio.of("#000000", "#FFFFFF"), absoluteTolerance = 0.01)
    }

    @Test
    fun sameColorIsMinimumContrast() {
        assertEquals(1.0, ContrastRatio.of("#7FD4E8", "#7FD4E8"), absoluteTolerance = 0.01)
    }

    @Test
    fun isSymmetric() {
        val forward = ContrastRatio.of("#0B5566", "#FFFFFF")
        val backward = ContrastRatio.of("#FFFFFF", "#0B5566")
        assertEquals(forward, backward, absoluteTolerance = 0.0001)
    }
}
