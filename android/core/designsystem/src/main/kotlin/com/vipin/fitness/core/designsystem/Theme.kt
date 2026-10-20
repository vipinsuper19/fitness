package com.vipin.fitness.core.designsystem

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

private val Green = Color(0xFF2E7D32)
private val GreenLight = Color(0xFF81C784)
private val Orange = Color(0xFFEF6C00)
private val OrangeLight = Color(0xFFFFB74D)

internal val LightColors = lightColorScheme(
    primary = Green,
    secondary = Orange,
)

internal val DarkColors = darkColorScheme(
    primary = GreenLight,
    secondary = OrangeLight,
)

/**
 * App-wide Material 3 theme. Uses dynamic color on Android 12+ when [dynamicColor] is true,
 * otherwise the brand palette.
 */
@Composable
fun FitnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = FitnessTypography, content = content)
}
