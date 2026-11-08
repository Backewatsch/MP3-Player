package com.backewatsch.mp3player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFA2E66)
val AccentPurple = Color(0xFFBE42C0)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentPurple,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF111113),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242427),
    surfaceContainerLow = Color(0xFF111113),
    outlineVariant = Color(0xFF2C2C2E),
)

/** Dunkles Design mit pinker Akzentfarbe */
@Composable
fun MP3Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
