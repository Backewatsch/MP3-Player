package com.backewatsch.mp3player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.backewatsch.mp3player.model.formatTime

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val app = LocalApp.current
    val state by app.player.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val track = lib.tracks.firstOrNull { it.id == state.currentId } ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen)
            .testTag("miniPlayer"),
    ) {
        val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent))
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artist.isNotEmpty()) {
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            IconButton(onClick = app.player::togglePlayPause, modifier = Modifier.testTag("miniPlayPause")) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pause" else "Abspielen",
                )
            }
            IconButton(onClick = app.player::next) { Icon(Icons.Default.SkipNext, "Nächster Titel") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val state by app.player.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val track = lib.tracks.firstOrNull { it.id == state.currentId }
    var showQueue by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black,
        dragHandle = null,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Accent.copy(alpha = 0.45f), AccentPurple.copy(alpha = 0.25f), Color.Black))),
        ) {
            if (track == null) {
                EmptyHint("Nichts wird abgespielt", "Wähle einen Titel in der Mediathek.")
                return@Box
            }
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Schließen") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Wiedergabeliste") }
                    IconButton(onClick = { nav.actions.open(track) }) { Icon(Icons.Default.MoreVert, "Optionen") }
                }
                Spacer(Modifier.weight(1f))
                Artwork(track, 300.dp, 16.dp)
                Spacer(Modifier.height(28.dp))
                Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2)
                Text(track.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                val duration = state.durationMs.coerceAtLeast(1)
                Slider(
                    value = if (scrubbing) scrubValue else state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { scrubbing = true; scrubValue = it },
                    onValueChangeFinished = { app.player.seekTo(scrubValue.toLong()); scrubbing = false },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                )
                Row(Modifier.fillMaxWidth()) {
                    val pos = if (scrubbing) scrubValue.toLong() else state.positionMs
                    Text(formatTime(pos), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text("-" + formatTime((state.durationMs - pos).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = app.player::toggleShuffle) {
                        Icon(Icons.Default.Shuffle, if (state.shuffle) "Zufallswiedergabe an" else "Zufallswiedergabe aus",
                            tint = if (state.shuffle) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = app.player::previous) { Icon(Icons.Default.SkipPrevious, "Vorheriger Titel", Modifier.size(36.dp)) }
                    IconButton(onClick = app.player::togglePlayPause, modifier = Modifier.size(80.dp).testTag("bigPlayPause")) {
                        Icon(
                            if (state.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            if (state.isPlaying) "Pause" else "Abspielen", Modifier.size(76.dp),
                        )
                    }
                    IconButton(onClick = app.player::next) { Icon(Icons.Default.SkipNext, "Nächster Titel", Modifier.size(36.dp)) }
                    IconButton(onClick = app.player::cycleRepeat) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Wiederholen",
                            tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else Accent,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val state by app.player.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val byId = lib.tracks.associateBy { it.id }
    val current = state.currentIndex
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Wiedergabeliste", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        Text(if (state.shuffle) "Zufallswiedergabe an" else "In dieser Reihenfolge", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().testTag("queueList")) {
            itemsIndexed(state.queueIds) { index, id ->
                val track = byId[id] ?: return@itemsIndexed
                when (index) {
                    current -> QueueHeader("Läuft gerade")
                    current + 1 -> QueueHeader("Als Nächstes")
                }
                if (index < current) return@itemsIndexed // bereits gespielt → ausblenden
                Row(
                    Modifier.fillMaxWidth().clickable { app.player.playQueueIndex(index) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        .semantics { contentDescription = "Wiedergabeliste ${index - current}: ${track.title}" }
                        .testTag("queue-${index - current}-${track.title}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = if (index == current) Accent else Color.Unspecified, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (track.artist.isNotEmpty()) Text(track.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (index > current) {
                        IconButton(onClick = { app.player.moveInQueue(index, index - 1) }, enabled = index > current + 1) {
                            Icon(Icons.Default.KeyboardArrowUp, "Nach oben")
                        }
                        IconButton(onClick = { app.player.moveInQueue(index, index + 1) }, enabled = index < state.queueIds.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, "Nach unten")
                        }
                        IconButton(onClick = { app.player.removeFromQueue(index) }) { Icon(Icons.Default.Close, "Entfernen") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}
