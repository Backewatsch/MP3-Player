package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.data.SmartPlaylist
import com.backewatsch.mp3player.model.formatTime

/** Farben der automatischen Playlists (Kacheln) */
fun SmartPlaylist.colors(): List<Color> = when (this) {
    SmartPlaylist.RECENTLY_ADDED -> listOf(Color(0xFF2F6F9F), Color(0xFF89CFF0))
    SmartPlaylist.ON_REPEAT -> listOf(Color(0xFF7A2F5A), Color(0xFFE07AA0))
    SmartPlaylist.REDISCOVER -> listOf(Color(0xFF2F6B4F), Color(0xFF7FD1A8))
    SmartPlaylist.NEVER_PLAYED -> listOf(Color(0xFF6B5A2F), Color(0xFFE0C27A))
}

/** Quadratische Kachel einer automatischen Playlist */
@Composable
fun SmartTile(kind: SmartPlaylist, size: androidx.compose.ui.unit.Dp, count: Int, onClick: () -> Unit) {
    Column(Modifier.width(size).clickable(onClick = onClick).testTag("mix-${kind.name}")) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(kind.colors())),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(kind.title, color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, softWrap = true, modifier = Modifier.padding(10.dp))
        }
        Text(kind.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("$count Titel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Waagerechte Reihe „Deine Mixe“ */
@Composable
fun SmartRow() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val counts = remember(lib) { SmartPlaylist.entries.associateWith { it.tracks(lib).size } }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(SmartPlaylist.entries.toList(), key = { it.name }) { kind ->
            SmartTile(kind, 140.dp, counts[kind] ?: 0) { nav.push(LibRoute.Smart(kind.name)) }
        }
    }
}

/** Automatische Playlist anzeigen */
@Composable
fun SmartPlaylistScreen(name: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val kind = SmartPlaylist.fromName(name) ?: return
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val tracks = remember(lib) { kind.tracks(lib) }
    val selection = rememberSelection()
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, tracks) else ScreenTopBar("", onBack = { nav.pop() })
        LazyColumn {
            item {
                CollectionHeader(
                    title = kind.title,
                    subtitle = "${kind.subtitle} · ${tracks.size} Titel · ${formatTime(tracks.sumOf { it.durationMs })}",
                    colors = kind.colors(),
                    art = {
                        Box(Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(kind.colors())),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                    },
                    playing = player.isPlaying && tracks.any { it.id == player.currentId },
                    onPlay = { if (tracks.isNotEmpty()) app.player.play(tracks, 0) },
                    onShuffle = { if (tracks.isNotEmpty()) app.player.play(tracks, tracks.indices.random(), shuffle = true) },
                )
            }
            if (tracks.isEmpty()) item { EmptyHint("Noch leer", "Hier erscheinen Lieder, sobald es passende gibt.") }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, player.currentId == track.id, player.isPlaying,
                    onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track) }, selection = selection)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Doppelte Lieder finden und aufräumen */
@Composable
fun DuplicatesScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val lib by app.library.state.collectAsState()
    val groups = remember(lib) { app.library.duplicateGroups() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Doppelte Lieder", onBack = { nav.pop() })
        if (groups.isEmpty()) {
            EmptyHint("Keine Doppelten 🎉", "Jedes Lied ist nur einmal in deiner Mediathek.")
            return@Column
        }
        LazyColumn {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("${groups.size} Lieder sind mehrfach vorhanden (${groups.sumOf { it.size - 1 }} überzählige Dateien).",
                        modifier = Modifier.testTag("duplicatesSummary"))
                    Text("Beim Aufräumen bleibt jeweils ein Lied (MP3, meistgespielt). Playlists, Herzen und Zähler der anderen werden übernommen.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                    FilledTonalButton(onClick = {
                        val n = groups.sumOf { it.size - 1 }
                        groups.forEach { app.library.mergeDuplicates(it) }
                        Toast.makeText(context, "$n doppelte Dateien entfernt", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().testTag("mergeAll")) {
                        Icon(Icons.Default.CleaningServices, null, tint = Accent); Spacer(Modifier.width(8.dp))
                        Text("Alle aufräumen", color = Accent)
                    }
                }
            }
            items(groups, key = { g -> g.joinToString { it.id } }) { group ->
                Card {
                    group.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(t, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.fileName} · ${formatTime(t.durationMs)} · ${app.library.playCount(t)}× gehört",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    FilledTonalButton(onClick = { app.library.mergeDuplicates(group) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text("Aufräumen", color = Accent) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
