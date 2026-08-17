package com.snapclear.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * SnapClear 主题
 *
 * 关闭 Material You 动态取色，使用统一的品牌青绿配色，
 * 保证全应用视觉风格一致、克制、高级。
 */
private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = Color(0xFF0F2D2A),
    secondary = BrandSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF0B3B33),
    tertiary = BrandTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0C2A3F),
    error = StatusDenied,
    onError = Color.White,
    errorContainer = StatusDeniedContainer,
    onErrorContainer = Color(0xFF5C1010),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = Color(0xFFC9D8D4)
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = Color(0xFF003733),
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = BrandSecondaryDark,
    onSecondary = Color(0xFF003831),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFF99F6E4),
    tertiary = BrandTertiaryDark,
    onTertiary = Color(0xFF003248),
    tertiaryContainer = Color(0xFF074A6B),
    onTertiaryContainer = Color(0xFFCCE6FF),
    error = Color(0xFFF87171),
    onError = Color(0xFF5C1010),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF445954)
)

@Composable
fun SnapClearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
