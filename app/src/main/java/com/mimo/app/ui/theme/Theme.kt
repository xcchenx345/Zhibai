package com.mimo.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// MiMo brand colors - Black & White
object MiMoColors {
    val Primary = Color(0xFF000000)
    val PrimaryDark = Color(0xFFFFFFFF)
    val OnPrimary = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFAFAFA)
    val SurfaceDark = Color(0xFF121212)
    val Background = Color(0xFFF5F5F5)
    val BackgroundDark = Color(0xFF0A0A0A)
    val UserBubble = Color(0xFF000000)
    val AssistantBubble = Color(0xFFFFFFFF)
    val AssistantBubbleDark = Color(0xFF1E1E1E)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextPrimaryDark = Color(0xFFE0E0E0)
    val TextSecondary = Color(0xFF666666)
    val TextSecondaryDark = Color(0xFF999999)
    val ThinkingBg = Color(0xFFF5F5F5)
    val ThinkingBgDark = Color(0xFF1A1A1A)
    val ThinkingText = Color(0xFF333333)
    val ThinkingTextDark = Color(0xFFCCCCCC)
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
    onTertiary = MiMoColors.Primary,
    tertiaryContainer = MiMoColors.DividerDark,
    onTertiaryContainer = MiMoColors.TextPrimaryDark,
    surface = MiMoColors.SurfaceDark,
    surfaceVariant = MiMoColors.ThinkingBgDark,
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
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            displayLarge = Typography().displayLarge.copy(fontFamily = FontFamily.Default),
            headlineLarge = Typography().headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = Typography().bodyLarge.copy(lineHeight = 24.sp),
            bodyMedium = Typography().bodyMedium.copy(lineHeight = 20.sp),
            labelSmall = Typography().labelSmall.copy(
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.Medium
            )
        ),
        content = content
    )
}
