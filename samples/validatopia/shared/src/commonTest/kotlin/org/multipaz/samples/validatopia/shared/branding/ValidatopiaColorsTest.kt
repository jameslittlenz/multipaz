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
        "secondaryContainer/onSecondaryContainer" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
        "surfaceVariant/onSurfaceVariant" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
        "surfaceContainer/onSurface" to (scheme.surfaceContainer to scheme.onSurface),
        "error/onError" to (scheme.error to scheme.onError),
    ) + listOf(scheme.background, scheme.surfaceContainer).flatMap { fill ->
        listOf(
            "success" to scheme.success,
            "warning" to scheme.warning,
            "neutral" to scheme.neutral,
            "error" to scheme.error,
        ).map { (name, color) -> "$fill/$name" to (fill to color) }
    }

    // WCAG 2.2 AA requires 3:1 for UI component boundaries such as text field outlines.
    @Test
    fun outlinesMeetWcagAaNonTextContrast() {
        for (scheme in listOf(ValidatopiaColors.light, ValidatopiaColors.dark)) {
            val ratio = ContrastRatio.of(scheme.outline, scheme.background)
            assertTrue(ratio >= 3.0, "outline ${scheme.outline} on ${scheme.background} has contrast $ratio")
        }
    }

    // Brand green is too light to be text on white; it must only appear as a fill there.
    @Test
    fun brandGreenIsNeverLightSchemeText() {
        val light = ValidatopiaColors.light
        assertTrue(ContrastRatio.of(ValidatopiaColors.GREEN, "#FFFFFF") < minTextContrast)
        for (textColor in listOf(light.onBackground, light.onSurface, light.success, light.onPrimary)) {
            assertTrue(textColor != ValidatopiaColors.GREEN)
        }
    }

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
