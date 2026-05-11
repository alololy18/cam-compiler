package com.camcompiler.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Color.Black,
    primaryContainer = Teal20,
    onPrimaryContainer = Teal80,

    secondary = Amber60,
    onSecondary = Color.Black,
    secondaryContainer = Amber20,
    onSecondaryContainer = Amber80,

    tertiary = Amber80,
    onTertiary = Color.Black,

    background = Neutral10,
    onBackground = Neutral95,
    surface = Neutral20,
    onSurface = Neutral95,
    surfaceVariant = Neutral40,
    onSurfaceVariant = Neutral80,

    error = DangerRed,
    onError = Color.White,
    errorContainer = Color(0xFF4A1010),
    onErrorContainer = DangerRedDim,

    outline = Neutral80,
    outlineVariant = Neutral40
)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal80,
    onPrimaryContainer = Teal20,

    secondary = Amber40,
    onSecondary = Color.Black,
    secondaryContainer = Amber80,
    onSecondaryContainer = Amber20,

    tertiary = Amber40,
    onTertiary = Color.Black,

    background = Neutral95,
    onBackground = Neutral20,
    surface = Color.White,
    onSurface = Neutral20,
    surfaceVariant = Neutral90,
    onSurfaceVariant = Neutral40,

    error = DangerRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E5),
    onErrorContainer = Color(0xFF7F1D1D),

    outline = Neutral40,
    outlineVariant = Neutral80
)

@Composable
fun CamCompilerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
