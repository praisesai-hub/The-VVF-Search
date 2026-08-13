package com.example.ui.theme
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val DarkColorScheme = darkColorScheme(
    primary = BhagwaOrange,
    onPrimary = Color.White,
    primaryContainer = CosmicBlue,
    onPrimaryContainer = SoftGold,
    secondary = SkyCyan,
    onSecondary = Color.Black,
    tertiary = EmeraldGreen,
    onTertiary = Color.White,
    background = DarkCanvas,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = SoftGold
)
private val LightColorScheme = lightColorScheme(
    primary = BhagwaOrange,
    onPrimary = Color.White,
    primaryContainer = CosmicBlue,
    onPrimaryContainer = SoftGold,
    secondary = SkyCyan,
    onSecondary = Color.Black,
    tertiary = EmeraldGreen,
    onTertiary = Color.White,
    background = LightCanvas,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = BhagwaOrange
)
@Composable
fun VVFSmartManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set false to prioritize VVF brand palette
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
// Alias for compatibility with existing tests
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    VVFSmartManagerTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
