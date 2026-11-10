package com.backewatsch.mp3player.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Suche über die ganze Mediathek – erreichbar über die Lupe auf „Start“ und „Mediathek“ */
@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val recentSearches by app.recentSearches.collectAsState()
    val search = nav.searchQuery
    val focus = remember { FocusRequester() }
    // Beim Öffnen gleich lostippen können (nicht, wenn man aus einem Treffer zurückkommt)
    LaunchedEffect(Unit) { if (nav.searchQuery.isEmpty()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { nav.closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Spacer(Modifier.width(8.dp))
            SearchPill(search, { nav.searchQuery = it }, "Was willst du hören?",
                Modifier.weight(1f).focusRequester(focus).testTag("searchField"))
        }
        LazyColumn(Modifier.fillMaxSize().testTag("searchList")) {
            if (search.isBlank()) {
                if (recentSearches.isEmpty()) {
                    item { EmptyHint("Suchen", "Finde Lieder, Interpreten, Alben, Genres und Playlists.") }
                } else {
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
                        ResultRow(q, "Letzte Suche", Icons.Default.History, "recent-$q") { nav.searchQuery = q }
                    }
                }
                return@LazyColumn
            }
            val results = com.backewatsch.mp3player.data.Search.run(lib, search)
            val saveQuery = { app.rememberSearch(search) }
            if (results.isEmpty) {
                item { EmptyHint("Nichts gefunden", "Zu „${search.trim()}“ gibt es keine Treffer.") }
                return@LazyColumn
            }
            if (results.artists.isNotEmpty()) {
                item { SectionHeader("Interpreten") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(results.artists, key = { "a-$it" }) { artist ->
                            Column(Modifier.width(96.dp).clickable { saveQuery(); nav.push(LibRoute.Artist(artist)) }.testTag("result-artist-$artist"),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.clip(CircleShape)) {
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
        }
    }
}
