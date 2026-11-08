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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
                }
            }
        })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, singleLine = true,
                        placeholder = { Text("Titel, Interpret oder Album") },
                        modifier = Modifier.weight(1f).testTag("searchField"),
                        shape = RoundedCornerShape(12.dp),
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
                val q = search.trim()
                val results = app.library.sortedTracks().filter {
                    it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
                }
                item { SectionHeader("${results.size} Treffer") }
                itemsIndexed(results, key = { _, t -> "s-" + t.id }) { index, track ->
                    TrackRow(track, player.currentId == track.id, player.isPlaying,
                        onPlay = { app.player.play(results, index) }, onMore = { nav.actions.open(track) })
                }
                return@LazyColumn
            }

            if (lib.tracks.isEmpty()) {
                item {
                    EmptyHint("Noch keine Musik",
                        "Lade im Tab „Lieder downloaden“ Musik aus dem Internet oder importiere Dateien über ＋.")
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

            item { SectionHeader("Bibliothek") }
            item {
                Card {
                    CategoryRow("Titel", Icons.Default.MusicNote, lib.tracks.size, "category-Titel") { nav.push(LibRoute.AllTracks) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Alben", Icons.Default.Album, app.library.albumNames().size, "category-Alben") { nav.push(LibRoute.Albums) }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryRow("Interpreten", Icons.Default.Mic, app.library.artistNames().size, "category-Interpreten") { nav.push(LibRoute.Artists) }
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
private fun CategoryRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    val app = LocalApp.current
    val tracks = app.library.tracksIn(playlist)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(tracks.firstOrNull(), 52.dp, 8.dp)
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
    val tracks = remember(lib) { app.library.sortedTracks() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Titel", onBack = { nav.pop() })
        LazyColumn {
            if (tracks.isNotEmpty()) item {
                PlayShuffleButtons({ app.player.play(tracks, 0) }, { app.player.play(tracks, tracks.indices.random(), shuffle = true) })
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, player.currentId == track.id, player.isPlaying,
                    onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track) })
            }
            item {
                Text("${tracks.size} Titel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp))
            }
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
    val tracks = remember(lib, name) { if (kind == "Album") app.library.tracksInAlbum(name) else app.library.tracksByArtist(name) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(name, onBack = { nav.pop() })
        LazyColumn {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(tracks.firstOrNull(), 160.dp, 14.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("$kind · ${tracks.size} Titel · ${formatTime(tracks.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tracks.isNotEmpty()) item {
                PlayShuffleButtons({ app.player.play(tracks, 0) }, { app.player.play(tracks, tracks.indices.random(), shuffle = true) })
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(track, player.currentId == track.id, player.isPlaying,
                    onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track) })
            }
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
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(playlist.name, onBack = { nav.pop() }) {
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Fertig" else "Bearbeiten") }
        }
        LazyColumn {
            if (tracks.isNotEmpty()) item {
                PlayShuffleButtons(
                    { app.player.play(tracks, 0) },
                    { app.player.play(tracks, tracks.indices.random(), shuffle = true) },
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
                        onPlay = { app.player.play(tracks, index) }, onMore = { nav.actions.open(track, playlist) })
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
