package org.multipaz.samples.validatopia.shared.branding

/**
 * A Material-3-shaped color scheme, expressed as `"#RRGGBB"` strings so it can be consumed
 * from both Compose (Android) and SwiftUI (iOS, from milestone M5) without a Compose dependency
 * in commonMain.
 *
 * Beyond the Material roles it carries [success], [warning] and [neutral]: text and icon colors
 * for trust badges, each readable on [background], [surface] and [surfaceContainer]. Badges pair
 * them with an icon and a label, so status is never conveyed by color alone.
 */
data class ValidatopiaColorScheme(
    val primary: String,
    val onPrimary: String,
    val primaryContainer: String,
    val onPrimaryContainer: String,
    val secondary: String,
    val onSecondary: String,
    val secondaryContainer: String,
    val onSecondaryContainer: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val surfaceVariant: String,
    val onSurfaceVariant: String,
    val surfaceContainer: String,
    val outline: String,
    val error: String,
    val onError: String,
    val success: String,
    val warning: String,
    val neutral: String,
)

/**
 * Validatopia brand colors: navy `#0A1B37` and green `#4EBC7D`, on white.
 *
 * Green on white is only 2.4:1, below WCAG 2.2 AA for text (4.5:1) and for UI components (3:1),
 * so in the light scheme green is only ever a fill carrying navy content ([secondary]), never
 * text or an icon on white. Where green has to read as text on a light background ([success]),
 * a darker shade is used. In the dark scheme, on navy, the brand green carries text directly.
 *
 * Every pair is covered by [ValidatopiaColorsTest].
 */
object ValidatopiaColors {
    /** The brand navy. */
    const val NAVY = "#0A1B37"

    /** The brand green. Fill only on light backgrounds; see the class documentation. */
    const val GREEN = "#4EBC7D"

    /**
     * The card-art teal, paired with [NAVY] and white in the document card art (see
     * [ValidatopiaCardArt]). White on teal, and teal on white, are both 5:1.
     */
    const val TEAL = "#0E7C7B"

    val light = ValidatopiaColorScheme(
        primary = NAVY,
        onPrimary = "#FFFFFF",
        primaryContainer = "#E8EDF5",
        onPrimaryContainer = NAVY,
        secondary = GREEN,
        onSecondary = NAVY,
        secondaryContainer = "#DDF3E6",
        onSecondaryContainer = NAVY,
        background = "#FFFFFF",
        onBackground = NAVY,
        surface = "#FFFFFF",
        onSurface = NAVY,
        surfaceVariant = "#E8EDF5",
        onSurfaceVariant = "#3A4659",
        surfaceContainer = "#F3F5F9",
        outline = "#5A6272",
        error = "#BA1A1A",
        onError = "#FFFFFF",
        success = "#1B7A45",
        warning = "#8A5A00",
        neutral = "#5A6272",
    )

    val dark = ValidatopiaColorScheme(
        primary = GREEN,
        onPrimary = NAVY,
        primaryContainer = "#1D355C",
        onPrimaryContainer = "#FFFFFF",
        secondary = GREEN,
        onSecondary = NAVY,
        secondaryContainer = "#1D355C",
        onSecondaryContainer = "#FFFFFF",
        background = NAVY,
        onBackground = "#FFFFFF",
        surface = NAVY,
        onSurface = "#FFFFFF",
        surfaceVariant = "#1D355C",
        onSurfaceVariant = "#C9D2DF",
        surfaceContainer = "#13264A",
        outline = "#8D99AB",
        error = "#FFB4AB",
        onError = "#690005",
        success = "#A8E6C1",
        warning = "#FFC857",
        neutral = "#AAB4C3",
    )
}
