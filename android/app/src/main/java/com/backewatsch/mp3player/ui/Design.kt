package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.backewatsch.mp3player.app
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover-Bild eines Titels (eigenes/eingebettetes Cover oder App-Logo), im Hintergrund geladen */
object ArtworkLoader {
    private val colors = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var logoColor: Int? = null

    fun bitmap(context: android.content.Context, track: Track?): Bitmap? {
        val app = context.app
        if (track == null) return null
        val show = app.showOwnCovers.value || app.library.hasCustomCover(track)
        if (!show) return null
        return app.library.artworkOf(track)?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    /** Dunkle, kräftige Leitfarbe des Covers (für Hintergründe) */
    fun color(context: android.content.Context, track: Track?, version: Int): Int {
        val key = (track?.id ?: "-") + ":" + version + ":" + context.app.showOwnCovers.value
        colors[key]?.let { return it }
        val bmp = bitmap(context, track)
        val c = if (bmp != null) pick(bmp) else logoColor ?: run {
            val logo = LogoBitmap.png(context)
            pick(BitmapFactory.decodeByteArray(logo, 0, logo.size)).also { logoColor = it }
        }
        colors[key] = c
        return c
    }

    private fun pick(bmp: Bitmap): Int {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val swatch = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
        val rgb = swatch?.rgb ?: 0xFF1F2A3A.toInt()
        // Etwas abdunkeln, damit weiße Schrift gut lesbar bleibt
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        hsv[2] = hsv[2].coerceAtMost(0.55f)
        return android.graphics.Color.HSVToColor(hsv)
    }
}

/** Leitfarbe des Covers als Compose-Farbe (Standard: dunkles Blau, bis sie berechnet ist) */
@Composable
fun rememberArtworkColor(track: Track?): Color {
    val context = LocalContext.current
    val version by context.app.library.coverVersion.collectAsState()
    val own by context.app.showOwnCovers.collectAsState()
    val c by produceState(Color(0xFF1F2A3A), track?.id, version, own) {
        value = Color(withContext(Dispatchers.Default) { ArtworkLoader.color(context, track, version) })
    }
    return c
}

/** Drei hüpfende Balken: „läuft gerade“ */
@Composable
fun PlayingBars(playing: Boolean, color: Color = Accent, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val heights = listOf(0, 180, 360).map { delay ->
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420, delayMillis = delay), RepeatMode.Reverse), label = "bar$delay",
        )
    }
    Row(modifier.size(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(Modifier.width(4.dp).fillMaxHeight(if (playing) h.value else 0.3f).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}

/** Großer runder Abspiel-Knopf wie bei Streaming-Apps */
@Composable
fun RoundPlayButton(playing: Boolean, size: Dp = 56.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.size(size).clip(CircleShape).background(Accent)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Abspielen",
            tint = Color.Black, modifier = Modifier.size(size * 0.55f))
    }
}

/** Kleine Abstände zwischen Abschnitten */
val SectionGap: Dp = 8.dp


/**
 * Kopf einer Sammlung (Playlist, Album, Interpret, Mix) im Stil von Streaming-Apps:
 * großes Bild, Farbverlauf, Titel, runder Abspiel-Knopf und Zufall.
 */
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    colors: List<Color>,
    art: @Composable () -> Unit,
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.first().copy(alpha = 0.55f), Color.Transparent)))
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.shadow(18.dp, RoundedCornerShape(12.dp))) { art() }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                extra()
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShuffle, modifier = Modifier.testTag("shuffleAllButton")) {
                    Icon(Icons.Default.Shuffle, "Zufällig", tint = Accent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                RoundPlayButton(playing = playing, size = 56.dp, modifier = Modifier.testTag("playAllButton"), onClick = onPlay)
            }
        }
    }
}
