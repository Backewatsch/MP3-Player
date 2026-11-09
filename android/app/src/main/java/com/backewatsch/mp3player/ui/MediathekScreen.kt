package com.backewatsch.mp3player.ui

// Mediathek-Startseite: Suche, Kategorien, Mixe, Playlists

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

// region Mediathek-Übersicht

@Composable
fun MediathekRoot() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val conversion by app.library.conversionStatus.collectAsState()
    val update by app.updates.state.collectAsState()
    var search by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val recentSearches by app.recentSearches.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            Toast.makeText(context, "Wird übernommen und in MP3 umgewandelt …", Toast.LENGTH_SHORT).show()
            val errors = app.importUris(uris)
            if (errors.isNotEmpty()) Toast.makeText(context, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Mediathek", actions = {
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.testTag("addMenu")) { Icon(Icons.Default.Add, "Hinzufügen") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Neue Playlist") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { showMenu = false; showNewPlaylist = true })
                    DropdownMenuItem(text = { Text("Aus Dateien importieren / Video umwandeln") }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                        onClick = { showMenu = false; importer.launch(arrayOf("audio/*", "video/*")) })
                    DropdownMenuItem(text = { Text("Von Link herunterladen") }, leadingIcon = { Icon(Icons.Default.Link, null) },
                        onClick = { showMenu = false; showLink = true })
                    DropdownMenuItem(text = { Text("Von Samsung Music übertragen") }, leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                        onClick = { showMenu = false; nav.showTransfer = true }, modifier = Modifier.testTag("menuTransfer"))
                }
            }
        })
        LazyColumn(Modifier.fillMaxSize().testTag("libraryList")) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, singleLine = true,
                        placeholder = { Text("Was willst du hören?", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (search.isNotEmpty()) IconButton(onClick = { search = "" }, modifier = Modifier.testTag("searchClear")) {
                                Icon(Icons.Default.Close, "Suche löschen")
                            }
                        },
                        modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused }.testTag("searchField"),
                        shape = RoundedCornerShape(24.dp),
                    )
                    IconButton(onClick = { nav.showSettings = true }, modifier = Modifier.testTag("settingsButton")) {
                        Icon(Icons.Default.Settings, "Info & Updates")
                    }
                }
            }
            (update as? UpdateChecker.State.Available)?.let { available ->
                item {
                    Row(
                        Modifier.padding(16.dp, 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Accent)
                            .clickable { nav.showSettings = true }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Update verfügbar", fontWeight = FontWeight.SemiBold)
                            Text("Version ${available.release.version} – tippen zum Installieren", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            conversion?.let { status ->
                item {
                    Column(Modifier.padding(16.dp, 6.dp)) {
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Accent)
                    }
                }
            }

            if (search.isNotBlank()) {
                val results = com.backewatsch.mp3player.data.Search.run(lib, search)
                val saveQuery = { app.rememberSearch(search) }
                if (results.isEmpty) {
                    item { EmptyHint("Nichts gefunden", "Zu „${search.trim()}“ gibt es keine Treffer.") }
                    return@LazyColumn
                }
                if (results.artists.isNotEmpty()) {
                    item { SectionHeader("Interpreten") }
                    item {
                        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(results.artists, key = { "a-$it" }) { artist ->
                                Column(Modifier.width(96.dp).clickable { saveQuery(); nav.push(LibRoute.Artist(artist)) }.testTag("result-artist-$artist"),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape)) {
                                        Artwork(app.library.tracksByArtist(artist).firstOrNull(), 88.dp, 44.dp)
                                    }
                                    Text(artist, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
                if (results.albums.isNotEmpty() || results.genres.isNotEmpty() || results.playlists.isNotEmpty()) {
                    item { SectionHeader("Alben, Genres & Playlists") }
                    items(results.albums, key = { "al-$it" }) { album ->
                        ResultRow(album, "Album", Icons.Default.Album, "result-album-$album") { saveQuery(); nav.push(LibRoute.Album(album)) }
                    }
                    items(results.genres, key = { "g-$it" }) { genre ->
                        ResultRow(genre, "Genre", Icons.Default.Category, "result-genre-$genre") { saveQuery(); nav.push(LibRoute.Genre(genre)) }
                    }
                    items(results.playlists, key = { "pl-" + it.id }) { pl ->
                        ResultRow(pl.name, "Playlist · ${pl.trackIds.size} Titel", Icons.AutoMirrored.Filled.QueueMusic, "result-playlist-${pl.name}") {
                            saveQuery(); nav.push(LibRoute.PlaylistDetail(pl.id))
                        }
                    }
                }
                if (results.tracks.isNotEmpty()) {
                    item { SectionHeader("Lieder (${results.tracks.size})") }
                    itemsIndexed(results.tracks, key = { _, t -> "s-" + t.id }) { index, track ->
                        TrackRow(track, player.currentId == track.id, player.isPlaying,
                            onPlay = { saveQuery(); app.player.play(results.tracks, index) }, onMore = { nav.actions.open(track) })
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
                return@LazyColumn
            }

            // Fokus im leeren Suchfeld: letzte Suchen
            if (searchFocused && recentSearches.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Letzte Suchen")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { app.clearSearches() }, modifier = Modifier.padding(end = 8.dp, top = 16.dp)) {
                            Text("Löschen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(recentSearches, key = { "r-$it" }) { q ->
                    ResultRow(q, "Letzte Suche", Icons.Default.History, "recent-$q") { search = q }
                }
            }

            if (lib.tracks.isEmpty()) {
                item {
                    EmptyHint("Noch keine Musik",
                        "Lade im Tab „Lieder downloaden“ Musik aus dem Internet, importiere Dateien über ＋ oder übertrage deine Musik aus Samsung Music.")
                }
                item {
                    FilledTonalButton(onClick = { nav.showTransfer = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("transferButton")) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = Accent); Spacer(Modifier.width(8.dp))
                        Text("Von Samsung Music übertragen", color = Accent)
                    }
                }
            } else {
                item {
                    PlayShuffleButtons(
                        onPlay = { app.player.play(app.library.sortedTracks(), 0) },
                        onShuffle = { val all = app.library.sortedTracks(); app.player.play(all, all.indices.random(), shuffle = true) },
                    )
                }
            }

            val pinned = lib.pinned.mapNotNull { id -> lib.tracks.firstOrNull { it.id == id } }
            if (pinned.isNotEmpty()) {
                item { SectionHeader("Kurzwahl") }
                item {
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(pinned, key = { _, t -> "p-" + t.id }) { index, track ->
                            Column(Modifier.width(110.dp).clickable { app.player.play(pinned, index) }) {
                                Artwork(track, 110.dp, 12.dp)
                                Text(track.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp))
                                Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (lib.tracks.isNotEmpty()) {
                item { SectionHeader("Deine Mixe") }
                item { SmartRow() }
            }

            item { SectionHeader("Bibliothek") }
            item {
                Card {
                    CategoryRow("Lieblingssongs", Icons.Default.Favorite, lib.favorites.count { id -> lib.tracks.any { it.id == id } },
                        "category-Lieblingssongs") { nav.push(LibRoute.Favorites) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Titel", Icons.Default.MusicNote, lib.tracks.size, "category-Titel") { nav.push(LibRoute.AllTracks) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Alben", Icons.Default.Album, app.library.albumNames().size, "category-Alben") { nav.push(LibRoute.Albums) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Interpreten", Icons.Default.Mic, app.library.artistNames().size, "category-Interpreten") { nav.push(LibRoute.Artists) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Genres", Icons.Default.Category, app.library.genreNames().size, "category-Genres") { nav.push(LibRoute.Genres) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Statistik", Icons.Default.BarChart, null, "category-Statistik") { nav.push(LibRoute.Stats) }
                }
            }

            item { SectionHeader("Playlists") }
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().clickable { showNewPlaylist = true }.padding(12.dp).testTag("newPlaylistButton"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = Accent) }
                        Spacer(Modifier.width(12.dp))
                        Text("Neue Playlist", color = Accent)
                    }
                    lib.playlists.forEach { playlist ->
                        HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        PlaylistRow(playlist) { nav.push(LibRoute.PlaylistDetail(playlist.id)) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showNewPlaylist) {
        TextInputDialog("Neue Playlist", "Name", "", "Erstellen", onDismiss = { showNewPlaylist = false }) { name ->
            val playlist = app.library.createPlaylist(name)
            nav.push(LibRoute.PlaylistDetail(playlist.id))
        }
    }
    if (showLink) {
        TextInputDialog("Von Link herunterladen", "https://…/lied.mp3", "", "Laden", onDismiss = { showLink = false }) { url ->
            if (url.startsWith("http")) {
                app.downloads.download(url.trim())
                Toast.makeText(context, "Download gestartet", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "Ungültiger Link.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun CategoryRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int?, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f))
        if (count != null) Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    val app = LocalApp.current
    val tracks = app.library.tracksIn(playlist)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PlaylistArtwork(playlist, tracks.firstOrNull(), 52.dp, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playlist.id == DOWNLOADS_PLAYLIST_ID) {
                    Icon(Icons.Default.Download, null, tint = Accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                }
                Text(playlist.name)
            }
            Text("${tracks.size} Titel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// endregion

@Composable
internal fun ResultRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(CyberPanelHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Alle Genres als farbige Kacheln */
