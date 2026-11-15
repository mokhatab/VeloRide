package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val VeloDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF022C22),
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldBright,
    secondary = AmberAccent,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = AmberLight,
    tertiary = InfoCyan,
    background = ObsidianBg,
    onBackground = TextOnDark,
    surface = ObsidianSurface,
    onSurface = TextOnDark,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = TextMutedOnDark,
    outline = ObsidianOutline,
    error = ErrorCoral,
    errorContainer = ErrorContainerDark
)

private val VeloLightColorScheme = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = Color(0xFF022C22),
    secondary = AmberAccent,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = AmberLight,
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = InfoCyan,
    background = LightBg,
    onBackground = TextOnLight,
    surface = LightSurface,
    onSurface = TextOnLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextMutedOnLight,
    outline = LightOutline,
    error = ErrorCoral
)

val VeloShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VeloRideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> VeloDarkColorScheme
        else -> VeloLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VeloTypography,
        shapes = VeloShapes,
        content = content
    )
}
