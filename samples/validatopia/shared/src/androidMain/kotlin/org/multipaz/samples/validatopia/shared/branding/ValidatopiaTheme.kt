package org.multipaz.samples.validatopia.shared.branding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun ValidatopiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        val tokens = ValidatopiaColors.dark
        darkColorScheme(
            primary = tokens.primary.toComposeColor(),
            onPrimary = tokens.onPrimary.toComposeColor(),
            primaryContainer = tokens.primaryContainer.toComposeColor(),
            onPrimaryContainer = tokens.onPrimaryContainer.toComposeColor(),
            secondary = tokens.secondary.toComposeColor(),
            onSecondary = tokens.onSecondary.toComposeColor(),
            background = tokens.background.toComposeColor(),
            onBackground = tokens.onBackground.toComposeColor(),
            surface = tokens.surface.toComposeColor(),
            onSurface = tokens.onSurface.toComposeColor(),
            error = tokens.error.toComposeColor(),
            onError = tokens.onError.toComposeColor(),
        )
    } else {
        val tokens = ValidatopiaColors.light
        lightColorScheme(
            primary = tokens.primary.toComposeColor(),
            onPrimary = tokens.onPrimary.toComposeColor(),
            primaryContainer = tokens.primaryContainer.toComposeColor(),
            onPrimaryContainer = tokens.onPrimaryContainer.toComposeColor(),
            secondary = tokens.secondary.toComposeColor(),
            onSecondary = tokens.onSecondary.toComposeColor(),
            background = tokens.background.toComposeColor(),
            onBackground = tokens.onBackground.toComposeColor(),
            surface = tokens.surface.toComposeColor(),
            onSurface = tokens.onSurface.toComposeColor(),
            error = tokens.error.toComposeColor(),
            onError = tokens.onError.toComposeColor(),
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun String.toComposeColor(): Color {
    val rgb = removePrefix("#").toInt(16)
    return Color(0xFF000000.toInt() or rgb)
}
