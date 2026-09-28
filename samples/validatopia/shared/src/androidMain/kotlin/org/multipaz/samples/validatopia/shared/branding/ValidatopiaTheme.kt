package org.multipaz.samples.validatopia.shared.branding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Text and icon colors for status badges, from [ValidatopiaColorScheme]. */
@Immutable
data class ValidatopiaStatusColors(
    val success: Color,
    val warning: Color,
    val neutral: Color,
    val error: Color,
)

/** The current status colors; set by [ValidatopiaTheme]. */
val LocalValidatopiaStatusColors = staticCompositionLocalOf {
    ValidatopiaColors.light.toStatusColors()
}

/**
 * Validatopia's Material 3 theme: navy and green on white, dynamic color off so the brand holds.
 * Every Material role is set explicitly, so none falls back to Material's default purple tints.
 */
@Composable
fun ValidatopiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val tokens = if (darkTheme) ValidatopiaColors.dark else ValidatopiaColors.light
    val colorScheme = if (darkTheme) darkColorScheme().withTokens(tokens) else lightColorScheme().withTokens(tokens)
    CompositionLocalProvider(LocalValidatopiaStatusColors provides tokens.toStatusColors()) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

private fun ColorScheme.withTokens(tokens: ValidatopiaColorScheme): ColorScheme {
    val surfaceContainer = tokens.surfaceContainer.toComposeColor()
    return copy(
        primary = tokens.primary.toComposeColor(),
        onPrimary = tokens.onPrimary.toComposeColor(),
        primaryContainer = tokens.primaryContainer.toComposeColor(),
        onPrimaryContainer = tokens.onPrimaryContainer.toComposeColor(),
        inversePrimary = tokens.secondary.toComposeColor(),
        secondary = tokens.secondary.toComposeColor(),
        onSecondary = tokens.onSecondary.toComposeColor(),
        secondaryContainer = tokens.secondaryContainer.toComposeColor(),
        onSecondaryContainer = tokens.onSecondaryContainer.toComposeColor(),
        tertiary = tokens.secondary.toComposeColor(),
        onTertiary = tokens.onSecondary.toComposeColor(),
        tertiaryContainer = tokens.secondaryContainer.toComposeColor(),
        onTertiaryContainer = tokens.onSecondaryContainer.toComposeColor(),
        background = tokens.background.toComposeColor(),
        onBackground = tokens.onBackground.toComposeColor(),
        surface = tokens.surface.toComposeColor(),
        onSurface = tokens.onSurface.toComposeColor(),
        surfaceVariant = tokens.surfaceVariant.toComposeColor(),
        onSurfaceVariant = tokens.onSurfaceVariant.toComposeColor(),
        surfaceTint = tokens.primary.toComposeColor(),
        surfaceBright = surfaceContainer,
        surfaceDim = tokens.surfaceVariant.toComposeColor(),
        surfaceContainerLowest = tokens.surface.toComposeColor(),
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainer,
        surfaceContainerHighest = tokens.surfaceVariant.toComposeColor(),
        outline = tokens.outline.toComposeColor(),
        outlineVariant = tokens.surfaceVariant.toComposeColor(),
        error = tokens.error.toComposeColor(),
        onError = tokens.onError.toComposeColor(),
    )
}

private fun ValidatopiaColorScheme.toStatusColors() = ValidatopiaStatusColors(
    success = success.toComposeColor(),
    warning = warning.toComposeColor(),
    neutral = neutral.toComposeColor(),
    error = error.toComposeColor(),
)

private fun String.toComposeColor(): Color {
    val rgb = removePrefix("#").toInt(16)
    return Color(0xFF000000.toInt() or rgb)
}
