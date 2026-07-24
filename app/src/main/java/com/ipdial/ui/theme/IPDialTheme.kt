package com.ipdial.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.ThemeMode

enum class GlassMode { None, Obsidian, Quartz }
val LocalGlassMode = staticCompositionLocalOf { GlassMode.None }
val LocalGlassAlpha = staticCompositionLocalOf { 0.65f }

// ── Palette ─────────────────────────────────────────────────────────────────
val SageBackground   = Color(0xFFEAEFE9)
val ForestGreen      = Color(0xFF1E6B3C)
val MintSurface      = Color(0xFFF2F7F1)
val DarkForest       = Color(0xFF0D3D20)
val EndRed           = Color(0xFFD32F2F)
val GrayText         = Color(0xFF5A6B5A)
val OutlineGreen     = Color(0xFFB0C9B0)
val OnSageText       = Color(0xFF1A2E1A)

private val LightColors = lightColorScheme(
    primary            = ForestGreen,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFB7DFB9),
    onPrimaryContainer = DarkForest,
    background         = SageBackground,
    onBackground       = OnSageText,
    surface            = MintSurface,
    onSurface          = OnSageText,
    surfaceVariant     = Color(0xFFDCE8DC),
    onSurfaceVariant   = GrayText,
    outline            = OutlineGreen,
    error              = EndRed,
    onError            = Color.White,
)

val DarkColors = darkColorScheme(
    primary            = Color(0xFF8BCF8F),
    onPrimary          = Color(0xFF003912),
    primaryContainer   = Color(0xFF1E5228),
    onPrimaryContainer = Color(0xFFA7F0A8),
    background         = Color(0xFF121212),
    onBackground       = Color(0xFFE0E0E0),
    surface            = Color(0xFF1A1A1A),
    onSurface          = Color(0xFFE0E0E0),
    surfaceVariant     = Color(0xFF10241A),
    onSurfaceVariant   = Color(0xFFB0C9B0),
    error              = Color(0xFFCF6679),
    onError            = Color(0xFF680022),
)

// Quartz: Apple-style Light Glass.
private val QuartzColors = lightColorScheme(
    primary            = Color(0xFF007AFF),
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFF2F2F7),
    onPrimaryContainer = Color.Black,
    background         = Color.Transparent,
    onBackground       = Color.Black,
    surface            = Color(0xCCFFFFFF),
    onSurface          = Color.Black,
    surfaceVariant     = Color(0xFFE5E5EA),
    onSurfaceVariant   = Color(0xFF3C3C43),
    outline            = Color(0x33000000),
)

// Obsidian: Apple-style Dark Glass.
private val ObsidianColors = darkColorScheme(
    primary            = Color(0xFF34C759),
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFF1C1C1E),
    onPrimaryContainer = Color.White,
    background         = Color.Transparent,
    onBackground       = Color.White,
    surface            = Color(0xCC1C1C1E),
    onSurface          = Color.White,
    surfaceVariant     = Color(0xFF2C2C2E),
    onSurfaceVariant   = Color(0xFFEBEBF5),
    outline            = Color(0x33FFFFFF),
)

data class ThemeSchemePreview(
    val label: String,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val onSurface: Color,
    val onPrimary: Color
)

fun themePreviewForMode(mode: ThemeMode): ThemeSchemePreview {
    val scheme = when (mode) {
        ThemeMode.System -> LightColors
        ThemeMode.Light -> LightColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Dynamic -> LightColors
        ThemeMode.Obsidian -> ObsidianColors
        ThemeMode.Quartz -> QuartzColors
    }
    return ThemeSchemePreview(
        label = mode.name,
        background = scheme.background,
        surface = scheme.surface,
        primary = scheme.primary,
        onSurface = scheme.onSurface,
        onPrimary = scheme.onPrimary
    )
}

@Composable
fun GlassBackground(mode: GlassMode) {
    if (mode == GlassMode.None) return

    val obsidian = mode == GlassMode.Obsidian

    // Base gradient
    val baseBrush = if (obsidian) {
        Brush.linearGradient(
            0f to Color(0xFF1A0808), 0.5f to Color(0xFF0D0404), 1f to Color(0xFF1A0808)
        )
    } else {
        Brush.linearGradient(
            0f to Color(0xFFF2F2F7), 0.5f to Color(0xFFE5E5EA), 1f to Color(0xFFF2F2F7)
        )
    }
    Box(modifier = Modifier.fillMaxSize().background(baseBrush))

    // Color overlay 1
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.radialGradient(
            colors = if (obsidian) listOf(Color(0xFFD32F2F).copy(alpha = 0.12f), Color.Transparent)
            else listOf(Color(0xFF007AFF).copy(alpha = 0.1f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 1000f
        )
    ))

    // Color overlay 2
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.radialGradient(
            colors = if (obsidian) listOf(Color(0xFF8B0000).copy(alpha = 0.12f), Color.Transparent)
            else listOf(Color(0xFFFF2D55).copy(alpha = 0.1f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(1000f, 2000f), radius = 1200f
        )
    ))

    // Grain/texture
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(
            colors = if (obsidian) listOf(Color(0x33FF6B6B), Color.Transparent, Color(0x334A0000))
            else listOf(Color(0x1A000000), Color.Transparent, Color(0x1A000000))
        )
    ))
}

/**
 * Applies Apple-style Glassmorphism (Translucency + Border)
 */
@Composable
fun Modifier.glass(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    alpha: Float = LocalGlassAlpha.current
): Modifier {
    val mode = LocalGlassMode.current
    if (mode == GlassMode.None) return this

    val borderColor = if (mode == GlassMode.Quartz) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.3f)
    val bgColor = if (mode == GlassMode.Quartz) Color.White.copy(alpha = alpha) else Color(0xFF1C1C1E).copy(alpha = alpha)

    return this
        .clip(shape)
        .background(bgColor)
        .border(borderWidth, borderColor, shape)
}

@Composable
fun IPDialTheme(
    themeMode: ThemeMode = ThemeMode.System,
    fontMultiplier: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clampedFont = fontMultiplier.coerceAtMost(1.25f)

    val colors = when (themeMode) {
        ThemeMode.Light -> LightColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Quartz -> QuartzColors
        ThemeMode.Obsidian -> ObsidianColors
        ThemeMode.Dynamic -> if (Build.VERSION.SDK_INT >= 31)
            (if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
        else LightColors
        ThemeMode.System -> if (Build.VERSION.SDK_INT >= 31)
            (if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
        else if (systemDark) DarkColors else LightColors
    }

    val isLight = themeMode != ThemeMode.Dark && themeMode != ThemeMode.Obsidian &&
        (themeMode != ThemeMode.System || !systemDark) && (themeMode != ThemeMode.Dynamic || !systemDark)

    val glassMode = when (themeMode) {
        ThemeMode.Quartz -> GlassMode.Quartz
        ThemeMode.Obsidian -> GlassMode.Obsidian
        else -> GlassMode.None
    }

    val scaledTypography = if (clampedFont != 1.0f) {
        Typography(
            displayLarge = IPDialTypography.displayLarge.copy(
                fontSize = IPDialTypography.displayLarge.fontSize * clampedFont,
                lineHeight = IPDialTypography.displayLarge.lineHeight * clampedFont
            ),
            displayMedium = IPDialTypography.displayMedium.copy(
                fontSize = IPDialTypography.displayMedium.fontSize * clampedFont,
                lineHeight = IPDialTypography.displayMedium.lineHeight * clampedFont
            ),
            headlineLarge = IPDialTypography.headlineLarge.copy(
                fontSize = IPDialTypography.headlineLarge.fontSize * clampedFont,
                lineHeight = IPDialTypography.headlineLarge.lineHeight * clampedFont
            ),
            headlineMedium = IPDialTypography.headlineMedium.copy(
                fontSize = IPDialTypography.headlineMedium.fontSize * clampedFont,
                lineHeight = IPDialTypography.headlineMedium.lineHeight * clampedFont
            ),
            titleLarge = IPDialTypography.titleLarge.copy(
                fontSize = IPDialTypography.titleLarge.fontSize * clampedFont,
                lineHeight = IPDialTypography.titleLarge.lineHeight * clampedFont
            ),
            titleMedium = IPDialTypography.titleMedium.copy(
                fontSize = IPDialTypography.titleMedium.fontSize * clampedFont,
                lineHeight = IPDialTypography.titleMedium.lineHeight * clampedFont
            ),
            bodyLarge = IPDialTypography.bodyLarge.copy(
                fontSize = IPDialTypography.bodyLarge.fontSize * clampedFont,
                lineHeight = IPDialTypography.bodyLarge.lineHeight * clampedFont
            ),
            bodyMedium = IPDialTypography.bodyMedium.copy(
                fontSize = IPDialTypography.bodyMedium.fontSize * clampedFont,
                lineHeight = IPDialTypography.bodyMedium.lineHeight * clampedFont
            ),
            labelLarge = IPDialTypography.labelLarge.copy(
                fontSize = IPDialTypography.labelLarge.fontSize * clampedFont,
                lineHeight = IPDialTypography.labelLarge.lineHeight * clampedFont
            ),
            labelMedium = IPDialTypography.labelMedium.copy(
                fontSize = IPDialTypography.labelMedium.fontSize * clampedFont,
                lineHeight = IPDialTypography.labelMedium.lineHeight * clampedFont
            ),
        )
    } else IPDialTypography

    // Status/nav bar icon tint
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = isLight
            insetsController.isAppearanceLightNavigationBars = true
        }
    }

    CompositionLocalProvider(
        LocalGlassMode provides glassMode,
        LocalGlassAlpha provides (if (glassMode != GlassMode.None) 0.65f else 0f)
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography  = scaledTypography,
            shapes      = IPDialShapes
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                GlassBackground(mode = glassMode)
                content()
            }
        }
    }
}
