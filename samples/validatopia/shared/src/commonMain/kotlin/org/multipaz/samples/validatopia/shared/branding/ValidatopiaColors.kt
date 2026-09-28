package org.multipaz.samples.validatopia.shared.branding

/**
 * A Material-3-shaped color scheme, expressed as `"#RRGGBB"` strings so it can be consumed
 * from both Compose (Android) and SwiftUI (iOS, from milestone M5) without a Compose dependency
 * in commonMain.
 */
data class ValidatopiaColorScheme(
    val primary: String,
    val onPrimary: String,
    val primaryContainer: String,
    val onPrimaryContainer: String,
    val secondary: String,
    val onSecondary: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val error: String,
    val onError: String,
)

/**
 * Validatopia brand colors. Every text-on-fill pair here is covered by
 * [ValidatopiaColorsTest], which checks the WCAG 2.2 AA contrast ratio (4.5:1).
 */
object ValidatopiaColors {
    val light = ValidatopiaColorScheme(
        primary = "#0B5566",
        onPrimary = "#FFFFFF",
        primaryContainer = "#B7E4EC",
        onPrimaryContainer = "#00363D",
        secondary = "#7A5900",
        onSecondary = "#FFFFFF",
        background = "#FFFBFF",
        onBackground = "#1A1C1E",
        surface = "#FFFBFF",
        onSurface = "#1A1C1E",
        error = "#BA1A1A",
        onError = "#FFFFFF",
    )

    val dark = ValidatopiaColorScheme(
        primary = "#7FD4E8",
        onPrimary = "#00363D",
        primaryContainer = "#274753",
        onPrimaryContainer = "#B7E4EC",
        secondary = "#FFDEA3",
        onSecondary = "#402D00",
        background = "#1A1C1E",
        onBackground = "#E2E2E6",
        surface = "#1A1C1E",
        onSurface = "#E2E2E6",
        error = "#FFB4AB",
        onError = "#690005",
    )
}
