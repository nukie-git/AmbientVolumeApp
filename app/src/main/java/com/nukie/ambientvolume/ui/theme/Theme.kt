package com.nukie.ambientvolume.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BlueIndigoDarkColorScheme = darkColorScheme(
    primary = Blue80,
    secondary = Indigo80,
    tertiary = LightBlue80,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Blue40,
    onSecondary = Indigo40,
    onTertiary = LightBlue40,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

private val BlueIndigoLightColorScheme = lightColorScheme(
    primary = Blue40,
    secondary = Indigo40,
    tertiary = LightBlue40,
    background = LightBackground,
    surface = LightSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1A1C1C),
    onSurface = Color(0xFF1A1C1C)
)

@Composable
fun AmbientVolumeTheme(
    followSystemDark: Boolean = true,
    manualDarkMode: Boolean = false,
    useSystemTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = if (followSystemDark) isSystemInDarkTheme() else manualDarkMode
    val colorScheme = when {
        useSystemTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BlueIndigoDarkColorScheme
        else -> BlueIndigoLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

