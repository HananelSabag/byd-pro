package com.example.byd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Same values as res/values/colors.xml, so app and widgets match exactly. */
object Palette {
    // Light and calm: soft sky at the top fading to white, like a showroom. Text and state colours
    // are darkened so they keep at least 4.5:1 contrast on white.
    val BackdropTop = Color(0xFFC9E2F8)
    val Bg = Color(0xFFFFFFFF)
    val Backdrop = Brush.verticalGradient(0f to BackdropTop, 0.45f to Color(0xFFE9F3FC), 0.72f to Bg, 1f to Bg)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF1F5FA)
    val SurfaceTop = Color(0xFFE4EBF3)
    val Outline = Color(0xFFD9E2EC)
    val TextPrimary = Color(0xFF0F1B2B)
    val TextSecondary = Color(0xFF44546A)
    val TextMuted = Color(0xFF66758A)
    val Accent = Color(0xFF1E78D8)
    val OnAccent = Color(0xFFFFFFFF)
    val Ok = Color(0xFF1E78D8)
    val Warn = Color(0xFFD63C3C)
    val Err = Color(0xFFD63C3C)
    val Busy = Color(0xFF8C99AA) // a command on its way: neutral, never a state colour
    val Soft = Color(0xFFB86E00) // sent but not confirmed yet / still checking
    val Heat = Color(0xFFD9601A) // heating: readable orange on white
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

/** Light: sky-to-white backdrop, white cards, dark text. */
@Composable
fun BydTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
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
