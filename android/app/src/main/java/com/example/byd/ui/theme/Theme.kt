package com.example.byd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Same values as res/values/colors.xml, so app and widgets match exactly. */
object Palette {
    // Deep navy graphite instead of pure black: reads as a lit cabin, not an empty screen.
    val BackdropTop = Color(0xFF151D29)
    val Bg = Color(0xFF0E131B)
    val Backdrop = Brush.verticalGradient(0f to BackdropTop, 0.42f to Bg, 1f to Color(0xFF0B0F15))
    val Surface = Color(0xFF172029)
    val SurfaceHigh = Color(0xFF1D2632)
    val SurfaceTop = Color(0xFF253041)
    val Outline = Color(0xFF2D3846)
    val TextPrimary = Color(0xFFF2F5F9)
    val TextSecondary = Color(0xFFAAB4C3)
    val TextMuted = Color(0xFF8994A5)
    val Accent = Color(0xFF5CC8FF)
    val OnAccent = Color(0xFF03141E)
    val Ok = Color(0xFF2FD39A)
    val Warn = Color(0xFFFFB648)
    val Err = Color(0xFFFF5F6D)
}

/** Tabular figures: numbers that change (%, km, timers) don't make the text jump. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().run {
    copy(
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** Always dark: an automotive cockpit look that also saves power on OLED screens. */
@Composable
fun BydTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            secondary = Palette.Ok,
            background = Palette.Bg,
            onBackground = Palette.TextPrimary,
            surface = Palette.Surface,
            onSurface = Palette.TextPrimary,
            surfaceVariant = Palette.SurfaceHigh,
            onSurfaceVariant = Palette.TextSecondary,
            surfaceContainer = Palette.SurfaceHigh,
            surfaceContainerHigh = Palette.SurfaceTop,
            surfaceContainerLow = Palette.Surface,
            outline = Palette.Outline,
            outlineVariant = Palette.Outline,
            error = Palette.Err,
        ),
        typography = AppTypography,
        content = content,
    )
}
