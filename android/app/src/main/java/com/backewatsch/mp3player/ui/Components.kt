package com.backewatsch.mp3player.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.app
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.model.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover eines Titels oder ein Verlauf mit Notensymbol */
@Composable
fun Artwork(track: Track?, size: Dp, corner: Dp = 6.dp) {
    val context = LocalContext.current
    val ownCovers by context.app.showOwnCovers.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, track?.id, ownCovers) {
        value = if (!ownCovers) null else track?.let { t ->
            withContext(Dispatchers.IO) {
                context.app.library.artworkOf(t)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
            }
        }
    }
    val shape = CutCornerShape(topStart = corner, bottomEnd = corner)
    if (bitmap != null) {
        Image(bitmap!!, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        // Kein eigenes Cover (oder ausgeschaltet) → App-Logo
        LogoArt(size, shape)
    }
}

/**
 * Titelzeile: Tippen spielt, „⋮“ öffnet das Optionsmenü. Mit [selection] startet langes Drücken die
 * Mehrfachauswahl (sonst öffnet es das Menü); während der Auswahl schaltet Tippen das Häkchen um.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    selection: SelectionState? = null,
) {
    val app = LocalContext.current.app
    val lib by app.library.state.collectAsState()
    val favorite = track.id in lib.favorites
    val selecting = selection?.active == true
    val selected = selection != null && track.id in selection.ids
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) selection!!.toggle(track.id) else onPlay() },
                onLongClick = { if (selection != null) selection.toggle(track.id) else onMore() },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("track-${track.title}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) NeonCyan else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                track.artist.ifEmpty { "Unbekannter Interpret" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (favorite) Icon(Icons.Default.Favorite, "Lieblingssong", tint = Accent, modifier = Modifier.size(14.dp).testTag("fav-${track.title}"))
        if (isCurrent && isPlaying) Icon(Icons.Default.GraphicEq, null, tint = NeonCyan, modifier = Modifier.padding(start = 6.dp).size(18.dp))
        Text(
            formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
        )
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { selection!!.toggle(track.id) },
                colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Color.Black),
                modifier = Modifier.testTag("check-${track.title}"))
        } else {
            IconButton(onClick = onMore, modifier = Modifier.testTag("trackMore")) {
                Icon(Icons.Default.MoreVert, "Optionen für ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Überschrift eines Abschnitts im Terminal-Stil: „▮ // OFT GESPIELT“ */
@Composable
fun SectionHeader(text: String) {
    Row(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(Accent))
        Spacer(Modifier.width(8.dp))
        Text("// ", style = MaterialTheme.typography.titleSmall, color = Accent)
        Text(text.uppercase(), style = MaterialTheme.typography.titleSmall, color = NeonCyan)
    }
}

/** Gruppe im Cyberpunk-Stil: abgeschrägte Ecken, Neon-Rand */
@Composable
fun Card(content: @Composable () -> Unit) {
    val shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CyberPanelHigh, CyberPanel)))
            .border(1.dp, Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.30f), Accent.copy(alpha = 0.22f))), shape),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}

/** Text mit Glitch-Effekt (versetzte Cyan- und Babyblau-Kopie) */
@Composable
fun GlitchText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFDCE6EE),
    maxLines: Int = 1,
) {
    Box(modifier) {
        Text(text, style = style, color = NeonCyan.copy(alpha = 0.45f), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = (-1).dp, y = 0.5.dp))
        Text(text, style = style, color = Accent.copy(alpha = 0.45f), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = 1.dp, y = (-0.5).dp))
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Cyberpunk-Hintergrund: Neon-Raster, Leuchten und Scanlines */
fun Modifier.cyberBackground(): Modifier = this.drawBehind {
    drawRect(CyberBlack)
    drawRect(Brush.radialGradient(listOf(Accent.copy(alpha = 0.07f), Color.Transparent),
        center = Offset(size.width * 0.85f, 0f), radius = size.width * 0.9f))
    drawRect(Brush.radialGradient(listOf(NeonCyan.copy(alpha = 0.05f), Color.Transparent),
        center = Offset(0f, size.height), radius = size.width * 1.1f))
    val step = 48.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(NeonCyan.copy(alpha = 0.022f), Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(NeonCyan.copy(alpha = 0.022f), Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
    // (keine Scanlines – schont die Augen)
}

@Composable
fun EmptyHint(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(40.dp), tint = NeonCyan)
        Spacer(Modifier.size(8.dp))
        GlitchText(title.uppercase(), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
