package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

// Listen: Alle Titel, Lieblingssongs, Alben/Interpreten/Genres, Playlists

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.model.LibraryState.Companion.DOWNLOADS_PLAYLIST_ID
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.formatTime
import com.backewatsch.mp3player.update.UpdateChecker
import kotlinx.coroutines.launch

@Composable
fun AllTracksScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val sort by app.trackSort.collectAsState()
    val tracks = remember(lib, sort) { app.library.sortedTracks(sort) }
    val selection = rememberSelection()
    var showSort by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, tracks)
        else ScreenTopBar("Titel", onBack = { nav.pop() }) {
            Box {
                IconButton(onClick = { showSort = true }, modifier = Modifier.testTag("sortButton")) {
                    Icon(Icons.AutoMirrored.Filled.Sort, "Sortieren")
                }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    com.backewatsch.mp3player.data.TrackSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = if (option == sort) Accent else MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = { if (option == sort) Icon(Icons.Default.Check, null, tint = Accent) },
                            onClick = { app.setTrackSort(option); showSort = false },
                            modifier = Modifier.testTag("sort-${option.name}"),
                        )
                    }
                }
            }
        }
        LazyColumn {
            if (tracks.isNotEmpty()) item {
                PlayShuffleButtons({ app.player.play(tracks, 0) }, { app.player.play(tracks, tracks.indices.random(), shuffle = true) })
                Text("Sortiert: ${sort.label} · Gedrückt halten zum Auswählen", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, player.currentId == track.id, player.isPlaying,
                    onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track) }, selection = selection)
            }
            item {
                Text("${tracks.size} Titel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp))
            }
        }
    }
}

@Composable
fun FavoritesScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val tracks = remember(lib) { app.library.favoriteTracks() }
    val selection = rememberSelection()
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, tracks) else ScreenTopBar("", onBack = { nav.pop() })
        LazyColumn {
            item {
                CollectionHeader(
                    title = "Lieblingssongs",
                    subtitle = "${tracks.size} Titel · ${formatTime(tracks.sumOf { it.durationMs })}",
                    colors = listOf(Color(0xFF3A2F7A), Color(0xFF89CFF0)),
                    art = {
                        Box(Modifier.size(180.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF3A2F7A), Color(0xFF89CFF0)))),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(72.dp))
                        }
                    },
                    playing = player.isPlaying && tracks.any { it.id == player.currentId },
                    onPlay = { if (tracks.isNotEmpty()) app.player.play(tracks, 0) },
                    onShuffle = { if (tracks.isNotEmpty()) app.player.play(tracks, tracks.indices.random(), shuffle = true) },
                )
            }
            if (tracks.isEmpty()) item {
                EmptyHint("Noch keine Lieblingssongs", "Tippe im Player oder im Menü eines Liedes auf das Herz ♥ – dann erscheint es hier.")
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, player.currentId == track.id, player.isPlaying,
                    onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track) }, selection = selection)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun GroupScreen(albums: Boolean) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val names = remember(lib) { if (albums) app.library.albumNames() else app.library.artistNames() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(if (albums) "Alben" else "Interpreten", onBack = { nav.pop() })
        if (names.isEmpty()) EmptyHint("Nichts gefunden", "In den Musikdateien sind keine Angaben hinterlegt.")
        LazyColumn {
            items(names, key = { it }) { name ->
                val tracks = if (albums) app.library.tracksInAlbum(name) else app.library.tracksByArtist(name)
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(if (albums) LibRoute.Album(name) else LibRoute.Artist(name)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(tracks.firstOrNull(), 52.dp, 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${tracks.size} Titel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun CollectionScreen(name: String, kind: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val tracks = remember(lib, name) {
        when (kind) {
            "Album" -> app.library.tracksInAlbum(name)
            "Genre" -> app.library.tracksInGenre(name)
            else -> app.library.tracksByArtist(name)
        }
    }
    val selection = rememberSelection()
    val color = rememberArtworkColor(tracks.firstOrNull())
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, tracks) else ScreenTopBar("", onBack = { nav.pop() })
        LazyColumn {
            item {
                CollectionHeader(
                    title = name,
                    subtitle = "$kind · ${tracks.size} Titel · ${formatTime(tracks.sumOf { it.durationMs })}",
                    colors = listOf(color, color),
                    art = { Artwork(tracks.firstOrNull(), 180.dp, 12.dp) },
                    playing = player.isPlaying && tracks.any { it.id == player.currentId },
                    onPlay = { if (tracks.isNotEmpty()) app.player.play(tracks, 0) },
                    onShuffle = { if (tracks.isNotEmpty()) app.player.play(tracks, tracks.indices.random(), shuffle = true) },
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, player.currentId == track.id, player.isPlaying,
                    onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track) }, selection = selection)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val playlist = lib.playlists.firstOrNull { it.id == id }
    var editing by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (playlist == null) {
        Column { ScreenTopBar("Playlist", onBack = { nav.pop() }); EmptyHint("Playlist gelöscht", "") }
        return
    }
    val tracks = app.library.tracksIn(playlist)
    val isDownloads = playlist.id == DOWNLOADS_PLAYLIST_ID
    val selection = rememberSelection()
    val context = LocalContext.current
    val color = rememberArtworkColor(tracks.firstOrNull())
    // Eigenes Playlist-Cover aus der Galerie
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) app.scope.launch {
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.let { com.backewatsch.mp3player.data.MetadataReader.shrinkArtwork(it, 800) }
            }
            if (jpeg != null) app.library.setPlaylistCover(playlist, jpeg)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionBar(selection, tracks, playlist)
        else ScreenTopBar("", onBack = { nav.pop() }) {
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Fertig" else "Bearbeiten") }
        }
        LazyColumn {
            item {
                CollectionHeader(
                    title = playlist.name,
                    subtitle = "Playlist · ${tracks.size} Titel · ${formatTime(tracks.sumOf { it.durationMs })}",
                    colors = listOf(color, color),
                    art = {
                        Box(Modifier.clickable {
                            coverPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.testTag("playlistCover")) { PlaylistArtwork(playlist, tracks.firstOrNull(), 180.dp, 12.dp) }
                    },
                    playing = player.isPlaying && tracks.any { it.id == player.currentId },
                    onPlay = { if (tracks.isNotEmpty()) app.player.play(tracks, 0) },
                    onShuffle = { if (tracks.isNotEmpty()) app.player.play(tracks, tracks.indices.random(), shuffle = true) },
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                if (editing) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { app.library.remove(track, playlist) }) { Icon(Icons.Default.Delete, "Entfernen", tint = MaterialTheme.colorScheme.error) }
                        Artwork(track, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(track.title, Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { app.library.move(playlist, index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Nach oben") }
                        IconButton(onClick = { app.library.move(playlist, index, index + 1) }, enabled = index < tracks.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Nach unten") }
                    }
                } else {
                    TrackRow(track, player.currentId == track.id, player.isPlaying,
                        onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track, playlist) }, selection = selection)
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (tracks.isEmpty()) {
                            if (isDownloads) "Lieder, die du herunterlädst (z. B. mit Chrome), erscheinen automatisch hier." else "Noch leer – füge Titel hinzu."
                        } else "${tracks.size} Titel · ${formatTime(tracks.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Titel hinzufügen") }
                        if (!isDownloads) {
                            IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, "Umbenennen") }
                            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Playlist löschen") }
                        }
                    }
                }
            }
        }
    }
    if (showRename) {
        TextInputDialog("Playlist umbenennen", "Name", playlist.name, "Sichern", onDismiss = { showRename = false }) {
            app.library.renamePlaylist(playlist, it)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("„${playlist.name}“ löschen?") },
            text = { Text("Die Titel bleiben in der Mediathek erhalten.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; nav.pop(); app.library.deletePlaylist(playlist) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (showAdd) AddTracksDialog(playlist, onDismiss = { showAdd = false })
}

@Composable
fun GenresScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val genres = remember(lib) { app.library.genreNames() }
    val palette = listOf(Color(0xFF2F6F9F), Color(0xFF7A2F5A), Color(0xFF2F6B4F), Color(0xFF6B5A2F), Color(0xFF4A3A8A), Color(0xFF8A3A3A),
        Color(0xFF2F7A7A), Color(0xFF7A5A2F))
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Genres", onBack = { nav.pop() })
        if (genres.isEmpty()) {
            EmptyHint("Keine Genres", "In deinen Musikdateien ist kein Genre eingetragen. Du kannst es unter „Infos bearbeiten“ selbst setzen.")
            return@Column
        }
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(genres.chunked(2), key = { it.joinToString() }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { g ->
                        val color = palette[(g.lowercase().hashCode() and 0x7fffffff) % palette.size]
                        Box(
                            Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f))))
                                .clickable { nav.push(LibRoute.Genre(g)) }.padding(12.dp).testTag("genre-$g"),
                        ) {
                            Text(g, color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                            Text("${app.library.tracksInGenre(g).size} Titel", color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
