package com.zhibai.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Custom bubble color override (Color.Unspecified = use default)
val LocalCustomBubbleColor = staticCompositionLocalOf { Color.Unspecified }

// Custom font Typeface override (null = use system default)
val LocalCustomTypeface = staticCompositionLocalOf<android.graphics.Typeface?> { null }

// MiMo brand colors - Black & White
// Micro-contrast: each surface layer differs by 1-3% for subtle depth
object MiMoColors {
    val Primary = Color(0xFF000000)
    val PrimaryDark = Color(0xFFFFFFFF)
    val OnPrimary = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFAFAFA)          // base surface
    val SurfaceElevated = Color(0xFFF7F7F7)  // ~2% darker than Surface
    val SurfaceDark = Color(0xFF141414)      // base dark surface
    val SurfaceDarkElevated = Color(0xFF181818) // ~2% lighter than SurfaceDark
    val Background = Color(0xFFF5F5F5)
    val BackgroundDark = Color(0xFF0C0C0C)   // ~1.5% lighter than 0A0A0A
    val UserBubble = Color(0xFF000000)
    val AssistantBubble = Color(0xFFFFFFFF)
    val AssistantBubbleDark = Color(0xFF1E1E1E)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextPrimaryDark = Color(0xFFD8D8D8)  // ~2% dimmer than E0E0E0
    val TextSecondary = Color(0xFF666666)
    val TextSecondaryDark = Color(0xFF949494) // ~2% dimmer than 999999
    val ThinkingBg = Color(0xFFF0F0F0)        // slightly darker than Background for contrast
    val ThinkingBgDark = Color(0xFF222222)    // clearly distinct from BackgroundDark (#0C0C0C)
    val ThinkingText = Color(0xFF333333)
    val ThinkingTextDark = Color(0xFFB0B0B0)  // dimmer but still readable
    val Divider = Color(0xFFE0E0E0)
    val DividerDark = Color(0xFF2A2A2A)
    val Error = Color(0xFFD32F2F)
    val Success = Color(0xFF388E3C)
    val Warning = Color(0xFFFF9800)
}

private val LightColorScheme = lightColorScheme(
    primary = MiMoColors.Primary,
    onPrimary = MiMoColors.OnPrimary,
    primaryContainer = MiMoColors.Primary.copy(alpha = 0.12f),
    onPrimaryContainer = MiMoColors.Primary,
    // Secondary/tertiary MUST be set — M3 defaults are purple and leak into chips/selection
    secondary = MiMoColors.Primary,
    onSecondary = MiMoColors.OnPrimary,
    secondaryContainer = MiMoColors.Primary.copy(alpha = 0.12f),
    onSecondaryContainer = MiMoColors.Primary,
    tertiary = MiMoColors.TextSecondary,
    onTertiary = MiMoColors.OnPrimary,
    tertiaryContainer = MiMoColors.Divider,
    onTertiaryContainer = MiMoColors.TextPrimary,
    surface = MiMoColors.Surface,
    surfaceVariant = MiMoColors.ThinkingBg,
    // M3 menus/sheets read surfaceContainer* — baseline defaults are purple-tinted.
    surfaceContainerLowest = MiMoColors.Surface,
    surfaceContainerLow = MiMoColors.Surface,
    surfaceContainer = MiMoColors.Surface,
    surfaceContainerHigh = MiMoColors.SurfaceElevated,
    surfaceContainerHighest = MiMoColors.SurfaceElevated,
    onSurfaceVariant = MiMoColors.TextSecondary,
    background = MiMoColors.Background,
    onBackground = MiMoColors.TextPrimary,
    onSurface = MiMoColors.TextPrimary,
    outline = MiMoColors.Divider,
    outlineVariant = MiMoColors.Divider,
    error = MiMoColors.Error
)

private val DarkColorScheme = darkColorScheme(
    primary = MiMoColors.PrimaryDark,
    onPrimary = MiMoColors.Primary,
    primaryContainer = MiMoColors.PrimaryDark.copy(alpha = 0.12f),
    onPrimaryContainer = MiMoColors.PrimaryDark,
    secondary = MiMoColors.PrimaryDark,
    onSecondary = MiMoColors.Primary,
    secondaryContainer = MiMoColors.PrimaryDark.copy(alpha = 0.18f),
    onSecondaryContainer = MiMoColors.Primary,
    tertiary = MiMoColors.TextSecondaryDark,
    onTertiary = MiMoColors.BackgroundDark,
    tertiaryContainer = MiMoColors.DividerDark,
    onTertiaryContainer = MiMoColors.TextPrimaryDark,
    surface = MiMoColors.SurfaceDark,
    surfaceVariant = MiMoColors.ThinkingBgDark,
    surfaceContainerLowest = MiMoColors.SurfaceDark,
    surfaceContainerLow = MiMoColors.SurfaceDark,
    surfaceContainer = MiMoColors.SurfaceDark,
    surfaceContainerHigh = MiMoColors.SurfaceDarkElevated,
    surfaceContainerHighest = MiMoColors.SurfaceDarkElevated,
    onSurfaceVariant = MiMoColors.TextSecondaryDark,
    background = MiMoColors.BackgroundDark,
    onBackground = MiMoColors.TextPrimaryDark,
    onSurface = MiMoColors.TextPrimaryDark,
    outline = MiMoColors.DividerDark,
    outlineVariant = MiMoColors.DividerDark,
    error = MiMoColors.Error
)

@Composable
fun MiMoAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    customFontColor: String = "",
    customBgColor: String = "",
    customBubbleColor: String = "",
    customTextColor: String = "",
    customFontPath: String = "",
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Parse custom colors ("" = keep default)
    fun parseColor(hex: String): Color? {
        if (hex.isBlank()) return null
        return try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { null }
    }
    val fontColor = parseColor(customFontColor)
    val bgColor = parseColor(customBgColor)
    val bubbleColor = parseColor(customBubbleColor)
    val textColor = parseColor(customTextColor)

    val colorScheme = baseScheme.copy(
        primary = fontColor ?: baseScheme.primary,
        background = bgColor ?: baseScheme.background,
        surface = bgColor ?: baseScheme.surface,
        onBackground = textColor ?: baseScheme.onBackground,
        onSurface = textColor ?: baseScheme.onSurface
    )

    // Dark mode: slightly thinner font weight for softer reading
    val base = Typography()
    val typography = if (darkTheme) {
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Default),
            headlineLarge = base.headlineLarge.copy(
                fontWeight = FontWeight.Medium,  // thinner than Bold in dark
                letterSpacing = (-0.5).sp
            ),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Normal), // thinner than SemiBold
            bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
            labelSmall = base.labelSmall.copy(
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.Normal  // thinner than Medium
            )
        )
    } else {
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Default),
            headlineLarge = base.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
            labelSmall = base.labelSmall.copy(
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }

    // Load custom font typeface
    val customTypeface = remember(customFontPath) {
        if (customFontPath.isNotBlank()) SystemFonts.loadTypeface(customFontPath) else null
    }

    // Provide custom overrides via CompositionLocal
    CompositionLocalProvider(
        LocalCustomBubbleColor provides (bubbleColor ?: Color.Unspecified),
        LocalCustomTypeface provides customTypeface
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
