package com.vfit.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand: the logo's navy and orange.
private val Navy = Color(0xFF1F4E79)
private val Orange = Color(0xFFC55A11)

private val Light = lightColorScheme(
    primary = Navy, onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3F5), onPrimaryContainer = Color(0xFF0A2540),
    secondary = Orange, onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC7), onSecondaryContainer = Color(0xFF3B1A04),
    tertiary = Color(0xFF3E7A2A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EBC7), onTertiaryContainer = Color(0xFF0E2A04),
    background = Color(0xFFF7F8FB), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F8FB), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E5EC), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F7),
    surfaceContainer = Color(0xFFEBEEF3), surfaceContainerHigh = Color(0xFFE5E8EE), surfaceContainerHighest = Color(0xFFDFE3E9),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6CF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA3C9F0), onPrimary = Color(0xFF0A2540),
    primaryContainer = Navy, onPrimaryContainer = Color(0xFFD3E3F5),
    secondary = Color(0xFFFFB68C), onSecondary = Color(0xFF4A1F04),
    secondaryContainer = Color(0xFF7A3307), onSecondaryContainer = Color(0xFFFFDCC7),
    tertiary = Color(0xFFB4D79A), onTertiary = Color(0xFF1B3A0D),
    tertiaryContainer = Color(0xFF2C5419), onTertiaryContainer = Color(0xFFD7EBC7),
    background = Color(0xFF111418), onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418), onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceContainerLowest = Color(0xFF0C0F13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF272A2F), surfaceContainerHighest = Color(0xFF32353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF43474E),
)

/** One accent per kind of timeline item; used for card tint, timeline dot and chart series. */
@Immutable
data class Accents(
    val body: Color, val glucose: Color, val meal: Color, val snack: Color, val exercise: Color, val day: Color,
)

private val LightAccents = Accents(
    body = Navy, glucose = Color(0xFFC2185B), meal = Orange, snack = Color(0xFFB7791F),
    exercise = Color(0xFF3E7A2A), day = Color(0xFF6A4C93),
)
private val DarkAccents = Accents(
    body = Color(0xFF8FB8E6), glucose = Color(0xFFF48FB1), meal = Color(0xFFFFA36B), snack = Color(0xFFF2C46D),
    exercise = Color(0xFF9ACD7E), day = Color(0xFFC3A6EA),
)

val LocalAccents = staticCompositionLocalOf { LightAccents }

private val VFitType = Typography().let {
    it.copy(
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    )
}

@Composable
fun VFitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalAccents provides if (dark) DarkAccents else LightAccents) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = VFitType, content = content)
    }
}
