package com.backewatsch.mp3player.ui

import androidx.compose.foundation.shape.RoundedCornerShape
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

// ---------- Cyberpunk-Farben (gedämpft, augenschonend) ----------
/** Haupt-Akzent: Babyblau */
val Accent = Color(0xFF89CFF0)
/** Zweiter Akzent: gedämpftes Cyan */
val NeonCyan = Color(0xFF4FAFBB)
/** Gedämpftes Signal-Gelb für Hervorhebungen */
val NeonYellow = Color(0xFFC8B85A)
val AccentPurple = Color(0xFF3E6E96)
val CyberBlack = Color(0xFF07090D)
val CyberPanel = Color(0xFF0D1118)
val CyberPanelHigh = Color(0xFF141A24)
/** Hellste Fläche: Suchfelder, gedrückte und hervorgehobene Elemente */
val CyberPanelHighest = Color(0xFF1C2431)
val CyberLine = Color(0xFF232E40)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04202E),
    primaryContainer = Color(0xFF12324A),
    onPrimaryContainer = Color(0xFFCDEBFA),
    secondary = NeonCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1A2A3B),
    onSecondaryContainer = Color(0xFFCDEBFA),
    tertiary = NeonYellow,
    background = CyberBlack,
    onBackground = Color(0xFFE2E9F0),
    surface = CyberPanel,
    onSurface = Color(0xFFE2E9F0),
    surfaceVariant = CyberPanelHigh,
    onSurfaceVariant = Color(0xFF8C9BB0),
    surfaceDim = CyberBlack,
    surfaceBright = CyberPanelHighest,
    surfaceContainerLowest = Color(0xFF05070A),
    surfaceContainerLow = CyberPanel,
    surfaceContainer = Color(0xFF10151D),
    surfaceContainerHigh = CyberPanelHigh,
    surfaceContainerHighest = CyberPanelHighest,
    outline = Color(0xFF3A4A60),
    outlineVariant = CyberLine,
    error = Color(0xFFFF7A8A),
    scrim = Color.Black,
)

/** Terminal-Schrift für Überschriften und Beschriftungen */
val CyberMono = FontFamily.Monospace

private val base = Typography()
/** Klare, kräftige Schrift wie bei Streaming-Apps (Terminal-Schrift nur noch für kleine Akzente) */
private val typography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    bodyLarge = base.bodyLarge.copy(letterSpacing = 0.1.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Große, weiche Rundungen (Material 3 Expressive) */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Dunkles Design mit Babyblau – klar und ruhig wie bei Streaming-Apps */
@Composable
fun MP3Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

@Suppress("unused")
val CyberCaption = TextStyle(fontFamily = CyberMono, fontSize = 11.sp, letterSpacing = 1.sp)
