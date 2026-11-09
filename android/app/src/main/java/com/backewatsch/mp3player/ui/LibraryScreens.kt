package com.backewatsch.mp3player.ui

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

sealed interface LibRoute {
    data object Root : LibRoute
    data object AllTracks : LibRoute
    data object Albums : LibRoute
    data object Artists : LibRoute
    data class Album(val name: String) : LibRoute
    data class Artist(val name: String) : LibRoute
    data class PlaylistDetail(val id: String) : LibRoute
    data object Favorites : LibRoute
    data object Stats : LibRoute
    data class Smart(val name: String) : LibRoute
    data object Duplicates : LibRoute
    data object Genres : LibRoute
    data class Genre(val name: String) : LibRoute
}

@Composable
fun LibraryHost() {
    val nav = LocalNav.current
    when (val route = nav.libraryStack.last()) {
        LibRoute.Root -> MediathekRoot()
        LibRoute.AllTracks -> AllTracksScreen()
        LibRoute.Albums -> GroupScreen(albums = true)
        LibRoute.Artists -> GroupScreen(albums = false)
        is LibRoute.Album -> CollectionScreen(route.name, "Album")
        is LibRoute.Artist -> CollectionScreen(route.name, "Interpret")
        is LibRoute.PlaylistDetail -> PlaylistScreen(route.id)
        LibRoute.Favorites -> FavoritesScreen()
        LibRoute.Stats -> StatsScreen()
        is LibRoute.Smart -> SmartPlaylistScreen(route.name)
        LibRoute.Duplicates -> DuplicatesScreen()
        LibRoute.Genres -> GenresScreen()
        is LibRoute.Genre -> CollectionScreen(route.name, "Genre")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            navigationIconContentColor = NeonCyan, actionIconContentColor = NeonCyan,
        ),
    )
}

@Composable
private fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onPlay, modifier = Modifier.weight(1f).testTag("playAllButton")) {
            Icon(Icons.Default.PlayArrow, null, tint = Accent); Spacer(Modifier.width(6.dp)); Text("Abspielen", color = Accent)
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Shuffle, null, tint = Accent); Spacer(Modifier.width(6.dp)); Text("Zufällig", color = Accent)
        }
    }
}

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
private fun CategoryRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int?, tag: String, onClick: () -> Unit) {
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
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
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
                            if (isDownloads) "Titel, die du im Tab „Lieder downloaden“ lädst, erscheinen automatisch hier." else "Noch leer – füge Titel hinzu."
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
private fun AddTracksDialog(playlist: Playlist, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val selected = remember { mutableStateOf(setOf<String>()) }
    val all = app.library.sortedTracks()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Titel hinzufügen") },
        text = {
            LazyColumn(Modifier.height(380.dp)) {
                items(all, key = { it.id }) { track ->
                    val already = track.id in playlist.trackIds
                    val checked = already || track.id in selected.value
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !already) {
                            selected.value = if (track.id in selected.value) selected.value - track.id else selected.value + track.id
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null, enabled = !already)
                        Artwork(track, 36.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                all.filter { it.id in selected.value }.forEach { app.library.add(it, playlist) }
                onDismiss()
            }, enabled = selected.value.isNotEmpty()) { Text("Hinzufügen (${selected.value.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Einfacher Eingabedialog */
@Composable
fun TextInputDialog(title: String, placeholder: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(placeholder) },
                modifier = Modifier.testTag("dialogInput"))
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(text) }, modifier = Modifier.testTag("dialogConfirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}


@Composable
private fun ResultRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
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
