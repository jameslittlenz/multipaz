package org.multipaz.samples.validatopia.shared.branding

import kotlin.test.Test
import kotlin.test.assertTrue

class ValidatopiaColorsTest {
    // WCAG 2.2 AA requires 4.5:1 for normal text.
    private val minTextContrast = 4.5

    private fun textPairs(scheme: ValidatopiaColorScheme) = listOf(
        "primary/onPrimary" to (scheme.primary to scheme.onPrimary),
        "primaryContainer/onPrimaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
        "secondary/onSecondary" to (scheme.secondary to scheme.onSecondary),
        "background/onBackground" to (scheme.background to scheme.onBackground),
        "surface/onSurface" to (scheme.surface to scheme.onSurface),
        "error/onError" to (scheme.error to scheme.onError),
    )

    @Test
    fun lightSchemeTextPairsMeetWcagAaContrast() = assertTextPairsMeetWcagAaContrast(ValidatopiaColors.light)

    @Test
    fun darkSchemeTextPairsMeetWcagAaContrast() = assertTextPairsMeetWcagAaContrast(ValidatopiaColors.dark)

    private fun assertTextPairsMeetWcagAaContrast(scheme: ValidatopiaColorScheme) {
        for ((name, pair) in textPairs(scheme)) {
            val (fill, onFill) = pair
            val ratio = ContrastRatio.of(fill, onFill)
            assertTrue(
                ratio >= minTextContrast,
                "$name ($fill on $onFill) has contrast $ratio, needs at least $minTextContrast"
            )
        }
    }
}
