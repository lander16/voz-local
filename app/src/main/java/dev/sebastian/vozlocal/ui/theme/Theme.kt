package dev.sebastian.vozlocal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryColor,
    secondary = SecondaryColor,
    tertiary = TertiaryColor,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color(0xFF020617), // very dark slate
    onSecondary = Color(0xFF0F172A),
    onTertiary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceLightDark,
    onSurfaceVariant = TextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryColor,
    secondary = SecondaryColor,
    tertiary = TertiaryColor,
    background = Color(0xFFF8FAFC), // beautiful slate-light
    surface = Color.White,
    onPrimary = Color(0xFF0F172A), // dark slate contrast over sky blue
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569)
)


@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme by default for the premium "Cosmic" vibe!
    dynamicColor: Boolean = false, // Disable dynamic colors to ensure our custom cosmic branding stays consistent!
    themeMode: String? = null, // "light" / "dark" / "system" — overrides darkTheme when provided
    content: @Composable () -> Unit
) {
    // Resolve the theme mode; "system" defers to the OS setting.
    val effectiveDarkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        "system" -> isSystemInDarkTheme()
        else -> darkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        effectiveDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
