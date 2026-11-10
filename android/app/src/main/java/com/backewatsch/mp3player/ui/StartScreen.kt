package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.draw.clip
import com.backewatsch.mp3player.update.UpdateChecker
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** Start-Tab: Weiterhören, Oft gespielt und Verlauf */
@Composable
fun StartScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val update by app.updates.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var showAllHistory by remember { mutableStateOf(false) }

    val most = remember(lib) { app.library.mostPlayed(20) }
    val history = remember(lib) { app.library.history() }
    val recent = remember(lib) { app.library.recentlyPlayed(15) }
    val current = lib.tracks.firstOrNull { it.id == player.currentId }
    val favorites = remember(lib) { app.library.favoriteTracks() }
    val stats = remember(lib) { com.backewatsch.mp3player.data.Stats.summary(lib) }
    val recapYear = remember(lib) { com.backewatsch.mp3player.data.Recap.defaultYear(lib) }

    LazyColumn(Modifier.fillMaxSize().testTag("startScreen")) {
        item {
            Row(Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                    Text("ILLEGAL FREEDOM MUSIC", style = MaterialTheme.typography.labelMedium, color = Accent,
                        fontFamily = CyberMono, letterSpacing = 2.sp)
                }
                Box(Modifier.clickable { nav.showSettings = true }.testTag("startSettings")) { Artwork(null, 40.dp, 20.dp) }
            }
        }
        (update as? UpdateChecker.State.Available)?.let { available ->
            item {
                Row(
                    Modifier.padding(16.dp, 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Accent)
                        .clickable { nav.showSettings = true }.padding(12.dp).testTag("startUpdateBanner"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Update verfügbar", fontWeight = FontWeight.SemiBold)
                        Text("Version ${available.release.version} – tippen zum Installieren", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        recapYear?.let { year ->
            item { RecapBanner(year, Modifier.padding(16.dp, 8.dp)) }
        }

        if (lib.tracks.isEmpty()) {
            item {
                EmptyHint("Willkommen!", "Übertrage deine Musik aus Samsung Music oder lade MP3s z. B. mit Chrome herunter. Hier erscheinen dann deine meistgehörten Lieder und dein Verlauf.")
                FilledTonalButton(onClick = { nav.showTransfer = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("startTransferButton")) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = Accent); Spacer(Modifier.width(8.dp))
                    Text("Von Samsung Music übertragen", color = Accent)
                }
            }
            return@LazyColumn
        }

        // Schnellzugriff: 2 Spalten mit Lieblingssongs, zuletzt gehörten Liedern und Playlists
        item {
            val tiles = buildList<QuickTile> {
                if (favorites.isNotEmpty()) add(QuickTile.Favorites(favorites.size))
                recent.take(3).forEach { add(QuickTile.Song(it)) }
                lib.playlists.filter { it.trackIds.isNotEmpty() }.take(6 - size).forEach { add(QuickTile.PlaylistTile(it)) }
            }.take(6)
            if (tiles.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { tile -> QuickTileView(tile, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Automatische Playlists
        item { SectionHeader("Deine Mixe") }
        item { SmartRow() }

        // Weiterhören
        if (current != null) {
            item { SectionHeader("Weiterhören") }
            item {
                Card {
                    Row(Modifier.fillMaxWidth().clickable { nav.showNowPlaying = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(current, 64.dp, 16.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.PlayArrow, null, tint = Accent)
                    }
                }
            }
        }

        // Lieblingssongs
        if (favorites.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Lieblingssongs")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { app.player.play(favorites, 0) }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) {
                        Text("Alle abspielen", color = Accent)
                    }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(favorites.take(20), key = { _, t -> "f-" + t.id }) { index, track ->
                        Column(Modifier.width(120.dp).clickable { app.player.play(favorites, index) }.testTag("startFav-${track.title}")) {
                            Artwork(track, 120.dp, 20.dp)
                            Text(track.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Statistik in Kürze
        item { SectionHeader("Deine Statistik") }
        item {
            Card {
                Row(Modifier.fillMaxWidth().clickable { nav.push(LibRoute.Stats) }.padding(14.dp).testTag("startStats"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BarChart, null, tint = Accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Diesen Monat: " + com.backewatsch.mp3player.data.Stats.formatListening(stats.listenedThisMonthMs) +
                            " · ${stats.playsThisMonth} Lieder", fontWeight = FontWeight.SemiBold)
                        Text(stats.topThisMonth.firstOrNull()?.let { "Dein Nr.-1-Song: ${it.first.title}" } ?: "Top 10, Hörzeit und Top-Interpreten",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Oft gespielt
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Oft gespielt")
                Spacer(Modifier.weight(1f))
                if (most.isNotEmpty()) TextButton(onClick = { app.player.play(most.map { it.first }, 0) },
                    modifier = Modifier.padding(end = 8.dp, top = 12.dp)) { Text("Alle abspielen", color = Accent) }
            }
        }
        if (most.isEmpty()) {
            item {
                Text("Noch nichts – Lieder, die du öfter hörst (ab 30 Sekunden), erscheinen hier.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp).testTag("mostPlayedEmpty"))
            }
        } else {
            itemsIndexed(most.take(10), key = { _, p -> "m-" + p.first.id }) { index, (track, count) ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.player.play(most.map { it.first }, index) }
                        .padding(horizontal = 16.dp, vertical = 6.dp).testTag("mostPlayed-${track.title}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(index + 1), color = NeonYellow, fontWeight = FontWeight.Bold,
                        fontFamily = CyberMono, modifier = Modifier.width(32.dp))
                    Artwork(track, 44.dp, 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (player.currentId == track.id) Accent else MaterialTheme.colorScheme.onSurface)
                        Text(track.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Default.Whatshot, null, tint = Accent)
                    Text("${count}×", style = MaterialTheme.typography.labelLarge, color = NeonYellow, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        // Zuletzt gehört
        if (recent.isNotEmpty()) {
            item { SectionHeader("Zuletzt gehört") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(recent, key = { _, t -> "r-" + t.id }) { index, track ->
                        Column(Modifier.width(120.dp).clickable { app.player.play(recent, index) }) {
                            Artwork(track, 120.dp, 20.dp)
                            Text(track.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Verlauf
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Verlauf")
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) {
                    Text("Löschen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (history.isEmpty()) {
            item {
                Text("Noch leer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        val shown = if (showAllHistory) history else history.take(15)
        itemsIndexed(shown, key = { i, p -> "h-$i-" + p.first.id }) { _, (track, at) ->
            Row(
                Modifier.fillMaxWidth().clickable { app.player.play(listOf(track), 0) }
                    .padding(horizontal = 16.dp, vertical = 6.dp).testTag("history-${track.title}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Artwork(track, 40.dp, 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(DateUtils.getRelativeTimeSpanString(at).toString(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (history.size > 15 && !showAllHistory) {
            item {
                TextButton(onClick = { showAllHistory = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Ganzen Verlauf anzeigen (${history.size})", color = Accent)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Verlauf löschen?") },
            text = { Text("Verlauf und „Oft gespielt“ werden zurückgesetzt. Deine Lieder bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { app.library.clearHistory(); confirmClear = false }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Hallo"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}


/** Eintrag im Schnellzugriff */
sealed interface QuickTile {
    data class Favorites(val count: Int) : QuickTile
    data class Song(val track: com.backewatsch.mp3player.model.Track) : QuickTile
    data class PlaylistTile(val playlist: com.backewatsch.mp3player.model.Playlist) : QuickTile
}

@Composable
private fun QuickTileView(tile: QuickTile, modifier: Modifier) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val player by app.player.state.collectAsState()
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(CyberPanelHigh)
            .clickable {
                when (tile) {
                    is QuickTile.Favorites -> nav.push(LibRoute.Favorites)
                    is QuickTile.Song -> app.player.play(listOf(tile.track), 0)
                    is QuickTile.PlaylistTile -> nav.push(LibRoute.PlaylistDetail(tile.playlist.id))
                }
            }
            .testTag("quick-" + when (tile) {
                is QuickTile.Favorites -> "favorites"
                is QuickTile.Song -> tile.track.title
                is QuickTile.PlaylistTile -> tile.playlist.name
            }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tile) {
            is QuickTile.Favorites -> Box(
                Modifier.size(56.dp).background(Brush.linearGradient(listOf(Color(0xFF3A2F7A), Accent))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Favorite, null, tint = Color.White) }
            is QuickTile.Song -> Artwork(tile.track, 56.dp, 0.dp)
            is QuickTile.PlaylistTile -> PlaylistArtwork(tile.playlist, app.library.tracksIn(tile.playlist).firstOrNull(), 56.dp, 0.dp)
        }
        Text(
            when (tile) {
                is QuickTile.Favorites -> "Lieblingssongs"
                is QuickTile.Song -> tile.track.title
                is QuickTile.PlaylistTile -> tile.playlist.name
            },
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp).weight(1f),
        )
        if (tile is QuickTile.Song && player.currentId == tile.track.id) PlayingBars(player.isPlaying, Accent, Modifier.padding(end = 10.dp))
    }
}
