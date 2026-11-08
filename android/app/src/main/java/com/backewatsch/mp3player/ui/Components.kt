package com.backewatsch.mp3player.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val bitmap by produceState<ImageBitmap?>(null, track?.id) {
        value = track?.let { t ->
            withContext(Dispatchers.IO) {
                context.app.library.artworkOf(t)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
            }
        }
    }
    val shape = RoundedCornerShape(corner)
    if (bitmap != null) {
        Image(bitmap!!, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(Accent, AccentPurple))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(size * 0.45f))
        }
    }
}

/** Titelzeile: Tippen spielt, „⋮“ öffnet das Optionsmenü, langes Drücken ebenfalls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(track: Track, isCurrent: Boolean, isPlaying: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onMore)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("track-${track.title}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) Accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artist.ifEmpty { "Unbekannter Interpret" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isCurrent && isPlaying) Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(18.dp))
        Text(
            formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
        )
        IconButton(onClick = onMore, modifier = Modifier.testTag("trackMore")) {
            Icon(Icons.Default.MoreVert, "Optionen für ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Überschrift eines Abschnitts */
@Composable
fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** Abgerundete Gruppe wie bei iOS-Listen */
@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}

@Composable
fun EmptyHint(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
