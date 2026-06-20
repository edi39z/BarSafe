package com.example.barsafe.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BlueNeon,
    secondary = GreenSecure,
    tertiary = YellowWarning,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = TextPrimary,
    onSecondary = DarkBackground,
    onTertiary = DarkBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun BarSafeTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme // Force dark mode for premium look
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
