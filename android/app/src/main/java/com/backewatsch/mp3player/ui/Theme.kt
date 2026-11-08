package com.backewatsch.mp3player.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Cyberpunk-Farben ----------
/** Haupt-Akzent: Neon-Magenta */
val Accent = Color(0xFFFF2BD6)
/** Zweiter Akzent: Neon-Cyan */
val NeonCyan = Color(0xFF00F0FF)
/** Signal-Gelb für Hervorhebungen */
val NeonYellow = Color(0xFFF7F000)
val AccentPurple = Color(0xFF7B2CFF)
val CyberBlack = Color(0xFF05030A)
val CyberPanel = Color(0xFF0D0A1C)
val CyberPanelHigh = Color(0xFF16112E)
val CyberLine = Color(0xFF2A2350)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = NeonCyan,
    onSecondary = Color.Black,
    secondaryContainer = CyberPanelHigh,
    onSecondaryContainer = NeonCyan,
    primaryContainer = Color(0xFF3A0A33),
    onPrimaryContainer = Accent,
    tertiary = NeonYellow,
    background = CyberBlack,
    onBackground = Color(0xFFEDE7FF),
    surface = CyberPanel,
    onSurface = Color(0xFFEDE7FF),
    surfaceVariant = CyberPanel,
    onSurfaceVariant = Color(0xFF9C94C8),
    surfaceContainer = CyberPanel,
    surfaceContainerHigh = CyberPanelHigh,
    surfaceContainerHighest = CyberPanelHigh,
    surfaceContainerLow = CyberBlack,
    outline = NeonCyan.copy(alpha = 0.5f),
    outlineVariant = CyberLine,
    error = Color(0xFFFF3B5C),
)

/** Terminal-Schrift für Überschriften und Beschriftungen */
val CyberMono = FontFamily.Monospace

private val base = Typography()
private val typography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontFamily = CyberMono, fontWeight = FontWeight.Black, letterSpacing = 2.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = CyberMono, fontWeight = FontWeight.Black, letterSpacing = 2.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = CyberMono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    titleLarge = base.titleLarge.copy(fontFamily = CyberMono, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
    titleMedium = base.titleMedium.copy(fontFamily = CyberMono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    titleSmall = base.titleSmall.copy(fontFamily = CyberMono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
    labelLarge = base.labelLarge.copy(fontFamily = CyberMono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    labelMedium = base.labelMedium.copy(fontFamily = CyberMono, letterSpacing = 0.5.sp),
    labelSmall = base.labelSmall.copy(fontFamily = CyberMono, letterSpacing = 0.5.sp),
)

/** Abgeschrägte Ecken statt runder */
private val shapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
    extraLarge = CutCornerShape(topStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = 0.dp),
)

/** Cyberpunk-Design: Neon auf Schwarz, Terminal-Schrift, abgeschrägte Ecken */
@Composable
fun MP3Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

@Suppress("unused")
val CyberCaption = TextStyle(fontFamily = CyberMono, fontSize = 11.sp, letterSpacing = 1.sp)
