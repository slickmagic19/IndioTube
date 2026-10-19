package com.indiotube.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// IndioTube design tokens — the YouTube dark palette. Dark theme only, by
// design: the whole app is built for it.
val YtBg = Color(0xFF0F0F0F)
val YtSurface = Color(0xFF212121)
val YtSurfaceHi = Color(0xFF272727)
val YtChip = Color(0xFF272727)
val YtChipSelected = Color(0xFFF1F1F1)
val YtText = Color(0xFFF1F1F1)
val YtMuted = Color(0xFFAAAAAA)
val YtRed = Color(0xFFFF0033)
val YtBlue = Color(0xFF3EA6FF)
val YtDivider = Color(0xFF272727)
val YtBadge = Color(0xCC000000)

private val DarkScheme = darkColorScheme(
    primary = YtRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0A12),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = YtBlue,
    onSecondary = Color(0xFF00243D),
    secondaryContainer = Color(0xFF0E2A40),
    onSecondaryContainer = Color(0xFFD1E8FF),
    tertiary = YtBlue,
    onTertiary = Color(0xFF00243D),
    background = YtBg,
    onBackground = YtText,
    surface = YtBg,
    onSurface = YtText,
    surfaceVariant = YtSurfaceHi,
    onSurfaceVariant = YtMuted,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = YtBg,
    surfaceContainer = YtSurface,
    surfaceContainerHigh = YtSurfaceHi,
    surfaceContainerHighest = Color(0xFF333333),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF4A1620),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF3F3F3F),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color(0xCC000000),
)

private val YtShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val YtTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun IndioTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = YtTypography,
        shapes = YtShapes,
    ) {
        // Full-screen overlays (player, sheets) are drawn outside the Scaffold,
        // so they get no content color from it — without this the ambient
        // default would be black text on the dark theme.
        CompositionLocalProvider(LocalContentColor provides YtText) {
            content()
        }
    }
}

// deterministic avatar color from a name (YouTube-style muted avatars)
private val avatarColors = listOf(
    Color(0xFF8E24AA), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFF4527A0), Color(0xFFAD1457),
)

fun avatarColor(seed: String): Color =
    avatarColors[(seed.hashCode().let { if (it < 0) -it else it }) % avatarColors.size]

val MiniPlayerShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
val Circle = CircleShape
