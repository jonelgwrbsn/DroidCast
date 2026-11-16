package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DroidCastDarkColorScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = ObsidianBg,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealPrimaryBright,
    secondary = SlateTextSecondary,
    onSecondary = ObsidianBg,
    background = ObsidianBg,
    onBackground = SlateTextPrimary,
    surface = ObsidianSurface,
    onSurface = SlateTextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = SlateTextSecondary,
    outline = ObsidianBorder,
    error = StatusError,
    onError = Color.White,
)

private val DroidCastLightColorScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF115E59),
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFCBD5E1),
    error = StatusError,
    onError = Color.White,
)

@Composable
fun DroidCastTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DroidCastDarkColorScheme else DroidCastLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DroidCastTypography,
        content = content,
    )
}
