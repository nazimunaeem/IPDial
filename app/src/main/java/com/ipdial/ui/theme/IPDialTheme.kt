package com.ipdial.ui.theme

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.ThemeMode

enum class GlassMode { None, Obsidian, Quartz }
val LocalGlassMode = staticCompositionLocalOf { GlassMode.None }

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

private val DarkColors = darkColorScheme(
    primary            = Color(0xFF8BCF8F),
    onPrimary          = Color(0xFF003912),
    primaryContainer   = Color(0xFF1E5228),
    onPrimaryContainer = Color(0xFFA7F0A8),
    background         = Color(0xFF000000),
    onBackground       = Color(0xFFE0E0E0),
    surface            = Color(0xFF000000),
    onSurface          = Color(0xFFE0E0E0),
    surfaceVariant     = Color(0xFF1E1E1E),
    onSurfaceVariant   = Color(0xFFB0C9B0),
    error              = Color(0xFFCF6679),
    onError            = Color(0xFF680022),
)

// Quartz: Apple-style Light Glass. 
private val QuartzColors = lightColorScheme(
    primary            = Color(0xFF007AFF), // Apple Blue
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
    primary            = Color(0xFF34C759), // Apple Green
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

/**
 * Applies Apple-style Glassmorphism (Translucency + Blur + Border)
 */
@Composable
fun Modifier.glass(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    alpha: Float = 0.65f
): Modifier {
    val mode = LocalGlassMode.current
    if (mode == GlassMode.None) return this
    
    val borderColor = if (mode == GlassMode.Quartz) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.3f)
    val bgColor = if (mode == GlassMode.Quartz) Color.White.copy(alpha = alpha) else Color(0xFF1C1C1E).copy(alpha = alpha)

    // We remove the blur modifier from the element itself because it blurs children (text/icons).
    // The glass effect is maintained via translucency, border, and the screen-wide blurred background.
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
    
    val colors = when (themeMode) {
        ThemeMode.Light -> LightColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Quartz -> QuartzColors
        ThemeMode.Obsidian -> ObsidianColors
        ThemeMode.System -> if (systemDark) DarkColors else LightColors
    }
    
    val glassMode = when (themeMode) {
        ThemeMode.Quartz -> GlassMode.Quartz
        ThemeMode.Obsidian -> GlassMode.Obsidian
        ThemeMode.System, ThemeMode.Light, ThemeMode.Dark -> GlassMode.None
    }
    
    val scaledTypography = if (fontMultiplier != 1.0f) {
        Typography(
            displayLarge = IPDialTypography.displayLarge.copy(fontSize = IPDialTypography.displayLarge.fontSize * fontMultiplier),
            displayMedium = IPDialTypography.displayMedium.copy(fontSize = IPDialTypography.displayMedium.fontSize * fontMultiplier),
            headlineLarge = IPDialTypography.headlineLarge.copy(fontSize = IPDialTypography.headlineLarge.fontSize * fontMultiplier),
            headlineMedium = IPDialTypography.headlineMedium.copy(fontSize = IPDialTypography.headlineMedium.fontSize * fontMultiplier),
            titleLarge = IPDialTypography.titleLarge.copy(fontSize = IPDialTypography.titleLarge.fontSize * fontMultiplier),
            titleMedium = IPDialTypography.titleMedium.copy(fontSize = IPDialTypography.titleMedium.fontSize * fontMultiplier),
            bodyLarge = IPDialTypography.bodyLarge.copy(fontSize = IPDialTypography.bodyLarge.fontSize * fontMultiplier),
            bodyMedium = IPDialTypography.bodyMedium.copy(fontSize = IPDialTypography.bodyMedium.fontSize * fontMultiplier),
            labelLarge = IPDialTypography.labelLarge.copy(fontSize = IPDialTypography.labelLarge.fontSize * fontMultiplier),
            labelMedium = IPDialTypography.labelMedium.copy(fontSize = IPDialTypography.labelMedium.fontSize * fontMultiplier),
        )
    } else IPDialTypography

    CompositionLocalProvider(LocalGlassMode provides glassMode) {
        MaterialTheme(
            colorScheme = colors,
            typography  = scaledTypography,
            shapes      = IPDialShapes
        ) {
            if (glassMode != GlassMode.None) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Redesigned vibrant whole-screen gradient background
                    val vibrancyBrush = if (glassMode == GlassMode.Obsidian) {
                        Brush.linearGradient(
                            0f to Color(0xFF1A0808),
                            0.5f to Color(0xFF0D0404),
                            1f to Color(0xFF1A0808)
                        )
                    } else {
                        Brush.linearGradient(
                            0f to Color(0xFFF2F2F7),
                            0.5f to Color(0xFFE5E5EA),
                            1f to Color(0xFFF2F2F7)
                        )
                    }
                    Box(modifier = Modifier.fillMaxSize().background(vibrancyBrush))
                    
                    // Soft colorful "Vibrancy" overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = if (glassMode == GlassMode.Obsidian) {
                                        listOf(Color(0xFFD32F2F).copy(alpha = 0.12f), Color.Transparent)
                                    } else {
                                        listOf(Color(0xFF007AFF).copy(alpha = 0.1f), Color.Transparent)
                                    },
                                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    radius = 1000f
                                )
                            )
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = if (glassMode == GlassMode.Obsidian) {
                                        listOf(Color(0xFF8B0000).copy(alpha = 0.12f), Color.Transparent)
                                    } else {
                                        listOf(Color(0xFFFF2D55).copy(alpha = 0.1f), Color.Transparent)
                                    },
                                    center = androidx.compose.ui.geometry.Offset(1000f, 2000f),
                                    radius = 1200f
                                )
                            )
                    )
                    
                    // Grain/Texture overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = if (glassMode == GlassMode.Obsidian) {
                                        listOf(Color(0x33FF6B6B), Color.Transparent, Color(0x334A0000))
                                    } else {
                                        listOf(Color(0x1A000000), Color.Transparent, Color(0x1A000000))
                                    }
                                )
                            )
                    )

                    content()
                }
            } else {
                content()
            }
        }
    }
}
