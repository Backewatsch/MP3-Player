package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

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

/** Cover eines Titels: eigenes Cover (falls gewählt oder eingeschaltet), sonst das App-Logo */
@Composable
fun Artwork(track: Track?, size: Dp, corner: Dp = 6.dp) {
    val context = LocalContext.current
    val ownCovers by context.app.showOwnCovers.collectAsState()
    val version by context.app.library.coverVersion.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, track?.id, ownCovers, version) {
        value = withContext(Dispatchers.IO) { ArtworkLoader.bitmap(context, track)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(corner)
    if (bitmap != null) {
        Image(bitmap!!, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        LogoArt(size, shape)
    }
}

/** Cover einer Playlist: eigenes Bild, sonst das Cover des ersten Titels */
@Composable
fun PlaylistArtwork(playlist: com.backewatsch.mp3player.model.Playlist, firstTrack: Track?, size: Dp, corner: Dp = 8.dp) {
    val context = LocalContext.current
    val version by context.app.library.coverVersion.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, playlist.id, version) {
        value = withContext(Dispatchers.IO) {
            context.app.library.playlistCoverFile(playlist).takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
    }
    if (bitmap != null) Image(bitmap!!, null, Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    else Artwork(firstTrack, size, corner)
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
    val favorite = track.id in LocalFavorites.current
    val selecting = selection?.active == true
    val selected = selection != null && track.id in selection.ids
    // Das laufende und ausgewählte Lieder liegen auf einer weich abgerundeten Fläche
    val highlight by androidx.compose.animation.animateColorAsState(
        when {
            selected -> Accent.copy(alpha = 0.16f)
            isCurrent -> Accent.copy(alpha = 0.09f)
            else -> Color.Transparent
        }, label = "rowHighlight")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(highlight)
            .combinedClickable(
                onClick = { if (selecting) selection!!.toggle(track.id) else onPlay() },
                onLongClick = { if (selection != null) selection.toggle(track.id) else onMore() },
            )
            .padding(start = 8.dp, top = 7.dp, bottom = 7.dp)
            .testTag("track-${track.title}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, 52.dp, 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                track.artist.ifEmpty { "Unbekannter Interpret" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (favorite) Icon(Icons.Default.Favorite, "Lieblingssong", tint = Accent, modifier = Modifier.size(14.dp).testTag("fav-${track.title}"))
        if (isCurrent) PlayingBars(isPlaying, Accent, Modifier.padding(start = 8.dp))
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

/** Überschrift eines Abschnitts: groß und fett („Zuletzt gehört“) */
@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 28.dp, bottom = 12.dp))
}

/** Gruppe als ruhige, abgerundete Fläche */
@Composable
fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(CyberPanelHigh),
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

/** Ruhiger dunkler Hintergrund mit leichtem Babyblau-Schimmer oben */
fun Modifier.cyberBackground(): Modifier = this.drawBehind {
    drawRect(CyberBlack)
    drawRect(Brush.verticalGradient(listOf(Accent.copy(alpha = 0.07f), Color.Transparent), endY = size.height * 0.35f))
}

@Composable
fun EmptyHint(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(34.dp), tint = Accent) }
        Spacer(Modifier.size(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
