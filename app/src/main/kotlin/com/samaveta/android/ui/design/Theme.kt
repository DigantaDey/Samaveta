package com.samaveta.android.ui.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * SAMAVETA design system.
 *
 * Visual direction (see docs/DESIGN_SYSTEM.md): restrained, warm, tri-chromatic, typography-led.
 * No purple gradients, no glowing orbs, no decorative animation. Colour is used to convey meaning,
 * not to decorate.
 *
 * Palette rationale
 * -----------------
 * Background is warm off-white / near-black charcoal rather than pure white / pure black: on OLED
 * panels a true black adjacent to warm text produces halo artifacts, and pure white is harsh for a
 * product meant to be read for long stretches.
 * The accent is a single muted copper. It appears only on: active navigation, primary actions,
 * focus rings and the "live" indicator. Everything else is neutral, which is what makes the accent
 * read as intentional.
 */

// ---- Raw palette (never used directly by screens) ----------------------------------------------

internal object Palette {
    // Warm neutrals
    val WarmWhite = Color(0xFFFAF8F5)
    val WarmWhiteElevated = Color(0xFFFFFDFA)
    val CharcoalDeep = Color(0xFF12100E)
    val CharcoalElevated = Color(0xFF1C1917)
    val CharcoalBorder = Color(0xFF2E2A26)

    val Ink = Color(0xFF1A1815)
    val InkMuted = Color(0xFF5C564E)
    val InkFaint = Color(0xFF8C857B)
    val BorderLight = Color(0xFFE4DED5)

    val WarmWhiteText = Color(0xFFF3EFE9)
    val WarmWhiteMuted = Color(0xFFB3ACA2)
    val WarmWhiteFaint = Color(0xFF7C766D)

    // Single accent: muted copper
    val Copper = Color(0xFFB06A3B)
    val CopperBright = Color(0xFFD0824F)
    val CopperDim = Color(0xFF8A5330)
    val CopperContainerLight = Color(0xFFF3E3D6)
    val CopperContainerDark = Color(0xFF3A2417)

    // Status colours, used sparingly and never as decoration
    val SuccessLight = Color(0xFF3F6B4A)
    val SuccessDark = Color(0xFF8FBF9B)
    val CautionLight = Color(0xFF8A6A1F)
    val CautionDark = Color(0xFFD9BC72)
    val DangerLight = Color(0xFF8E3B33)
    val DangerDark = Color(0xFFDD9A92)
}

/**
 * Semantic colours beyond Material's scheme. Screens must use these — never [Palette] directly —
 * so a future theme change is a single-file edit.
 */
@Immutable
data class SamavetaColors(
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentMuted: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val success: Color,
    val caution: Color,
    val danger: Color,
    val liveIndicator: Color,
    val skeleton: Color,
    val isDark: Boolean,
)

private val LightColors = SamavetaColors(
    canvas = Palette.WarmWhite,
    surface = Palette.WarmWhiteElevated,
    surfaceElevated = Color(0xFFF2EDE6),
    border = Palette.BorderLight,
    borderStrong = Color(0xFFCFC7BB),
    textPrimary = Palette.Ink,
    textSecondary = Palette.InkMuted,
    textTertiary = Palette.InkFaint,
    accent = Palette.Copper,
    accentMuted = Palette.CopperDim,
    accentContainer = Palette.CopperContainerLight,
    onAccent = Palette.WarmWhite,
    success = Palette.SuccessLight,
    caution = Palette.CautionLight,
    danger = Palette.DangerLight,
    liveIndicator = Palette.Copper,
    skeleton = Color(0xFFEDE7DE),
    isDark = false,
)

private val DarkColors = SamavetaColors(
    canvas = Palette.CharcoalDeep,
    surface = Palette.CharcoalElevated,
    surfaceElevated = Color(0xFF262220),
    border = Palette.CharcoalBorder,
    borderStrong = Color(0xFF443E38),
    textPrimary = Palette.WarmWhiteText,
    textSecondary = Palette.WarmWhiteMuted,
    textTertiary = Palette.WarmWhiteFaint,
    accent = Palette.CopperBright,
    accentMuted = Palette.CopperDim,
    accentContainer = Palette.CopperContainerDark,
    onAccent = Palette.CharcoalDeep,
    success = Palette.SuccessDark,
    caution = Palette.CautionDark,
    danger = Palette.DangerDark,
    liveIndicator = Palette.CopperBright,
    skeleton = Color(0xFF262220),
    isDark = true,
)

val LocalSamavetaColors = staticCompositionLocalOf { LightColors }

/** Convenience accessor used throughout the UI. */
val MaterialTheme.samaveta: SamavetaColors
    @Composable get() = LocalSamavetaColors.current

private fun SamavetaColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = textPrimary,
        secondary = textSecondary,
        onSecondary = canvas,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = canvas,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = textPrimary,
        secondary = textSecondary,
        onSecondary = canvas,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        outline = borderStrong,
        outlineVariant = border,
        error = danger,
        onError = onAccent,
    )
}

/**
 * Typography. A single family with weight/size hierarchy rather than multiple families; bundling
 * fonts increases APK size and CJK/Devanagari fallbacks are handled by the system.
 *
 * Devanagari note: समवेत is rendered with the system Devanagari font. Where the full brand lockup is
 * shown, [TypographyTokens.brandDevanagari] is used so line height does not clip matras.
 */
object TypographyTokens {
    val display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    )
    val title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    )
    val heading = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )
    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    )
    val bodyEmphasis = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    )
    val caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    )
    val label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp,
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    )
    val brandDevanagari = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        // Generous line height: Devanagari matras and the ि vowel sign sit above the baseline and
        // get clipped at tight leading.
        lineHeight = 28.sp,
    )
}

private val SamavetaTypography = Typography(
    displaySmall = TypographyTokens.display,
    headlineMedium = TypographyTokens.title,
    headlineSmall = TypographyTokens.heading,
    titleMedium = TypographyTokens.heading,
    bodyLarge = TypographyTokens.body,
    bodyMedium = TypographyTokens.body,
    bodySmall = TypographyTokens.caption,
    labelLarge = TypographyTokens.bodyEmphasis,
    labelSmall = TypographyTokens.label,
)

/** Spacing scale. Every gap in the product comes from here. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    /** Horizontal page gutter. */
    val gutter = 20.dp
}

/** Corner radii. Deliberately small: large radii read as "generic AI app". */
object Radius {
    val sm = 6.dp
    val md = 10.dp
    val lg = 14.dp
    val pill = 999.dp
}

/**
 * Theme modes. Default is SYSTEM, per the product directive, and system mode must follow Android's
 * live configuration changes (including battery saver's dark theme).
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun SamavetaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge with *transparent* system bars; icon contrast is derived from the theme
            // so bars never end up invisible in the wrong mode.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalSamavetaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = SamavetaTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(Radius.sm),
                small = RoundedCornerShape(Radius.sm),
                medium = RoundedCornerShape(Radius.md),
                large = RoundedCornerShape(Radius.lg),
                extraLarge = RoundedCornerShape(Radius.lg),
            ),
            content = content,
        )
    }
}
