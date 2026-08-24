package com.snapclear.app.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * SnapClear 主题
 *
 * 关闭 Material You 动态取色，使用统一的品牌青绿配色，
 * 保证全应用视觉风格一致、克制、高级。
 */
private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = OnSurfaceLight,
    secondary = BrandPrimary,
    onSecondary = Color.White,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = OnSurfaceLight,
    tertiary = BrandPrimary,
    onTertiary = Color.White,
    tertiaryContainer = SurfaceVariantLight,
    onTertiaryContainer = OnSurfaceLight,
    error = BrandPrimary,
    onError = Color.White,
    errorContainer = SurfaceVariantLight,
    onErrorContainer = OnSurfaceLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = Color(0xFFD2D2D2)
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = OnSurfaceDark,
    secondary = BrandPrimary,
    onSecondary = Color.White,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = OnSurfaceDark,
    tertiary = BrandPrimary,
    onTertiary = Color.White,
    tertiaryContainer = SurfaceVariantDark,
    onTertiaryContainer = OnSurfaceDark,
    error = BrandPrimary,
    onError = Color.White,
    errorContainer = SurfaceVariantDark,
    onErrorContainer = OnSurfaceDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF3D3D3D)
)

@Composable
fun SnapClearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            window.decorView.setBackgroundColor(
                if (darkTheme) AndroidColor.rgb(17, 17, 17) else AndroidColor.rgb(246, 246, 246)
            )
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
