package com.backewatsch.mp3player.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
    var confirmClear by remember { mutableStateOf(false) }
    var showAllHistory by remember { mutableStateOf(false) }

    val most = remember(lib) { app.library.mostPlayed(20) }
    val history = remember(lib) { app.library.history() }
    val recent = remember(lib) { app.library.recentlyPlayed(15) }
    val current = lib.tracks.firstOrNull { it.id == player.currentId }

    LazyColumn(Modifier.fillMaxSize().testTag("startScreen")) {
        item {
            Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp))
            Text("ILLEGAL FREEDOM MUSIC", style = MaterialTheme.typography.labelLarge, color = Accent,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        }

        if (lib.tracks.isEmpty()) {
            item {
                EmptyHint("Willkommen!", "Übertrage deine Musik aus Samsung Music oder lade Lieder im Tab „Lieder downloaden“. Hier erscheinen dann deine meistgehörten Lieder und dein Verlauf.")
                FilledTonalButton(onClick = { nav.showTransfer = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("startTransferButton")) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = Accent); Spacer(Modifier.width(8.dp))
                    Text("Von Samsung Music übertragen", color = Accent)
                }
            }
            return@LazyColumn
        }

        // Weiterhören
        if (current != null) {
            item { SectionHeader("Weiterhören") }
            item {
                Card {
                    Row(Modifier.fillMaxWidth().clickable { nav.showNowPlaying = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(current, 64.dp, 10.dp)
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
                    Text("${index + 1}", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                    Artwork(track, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (player.currentId == track.id) Accent else MaterialTheme.colorScheme.onSurface)
                        Text(track.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Default.Whatshot, null, tint = Accent)
                    Text("${count}×", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
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
                            Artwork(track, 120.dp, 12.dp)
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
                Artwork(track, 40.dp)
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
