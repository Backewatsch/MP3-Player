package com.backewatsch.mp3player.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.Track

/** Mehrfachauswahl in Titellisten (langes Drücken startet sie) */
class SelectionState {
    var ids by mutableStateOf(setOf<String>())
    val active get() = ids.isNotEmpty()
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun clear() { ids = emptySet() }
    fun selectAll(tracks: List<Track>) { ids = tracks.map { it.id }.toSet() }
}

@Composable
fun rememberSelection() = remember { SelectionState() }

/**
 * Leiste oben, solange etwas ausgewählt ist: Anzahl, Alle auswählen und die Aktionen
 * Als Nächstes · Zu Playlist · Herz · (Aus Playlist entfernen) · Löschen.
 */
@Composable
fun SelectionBar(selection: SelectionState, all: List<Track>, playlist: Playlist? = null) {
    val app = LocalApp.current
    val context = LocalContext.current
    var addTo by remember { mutableStateOf<List<Track>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Track>?>(null) }
    BackHandler(enabled = selection.active) { selection.clear() }
    // Reihenfolge wie in der Liste
    val chosen = all.filter { it.id in selection.ids }

    Column(Modifier.fillMaxWidth().background(CyberPanelHigh)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = selection::clear, modifier = Modifier.testTag("sel-close")) { Icon(Icons.Default.Close, "Auswahl beenden") }
            Text("${chosen.size} ausgewählt", style = MaterialTheme.typography.titleMedium, color = NeonCyan,
                modifier = Modifier.weight(1f).testTag("selectionCount"))
            TextButton(onClick = { if (chosen.size == all.size) selection.clear() else selection.selectAll(all) },
                modifier = Modifier.testTag("sel-all")) {
                Icon(Icons.Default.SelectAll, null, tint = Accent); Spacer(Modifier.width(4.dp))
                Text(if (chosen.size == all.size) "Keine" else "Alle", color = Accent)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SelAction("Als Nächstes", Icons.Default.SkipNext, "sel-next") {
                chosen.forEach { app.player.playNext(it) }
                Toast.makeText(context, "${chosen.size} Titel kommen als Nächstes", Toast.LENGTH_SHORT).show()
                selection.clear()
            }
            SelAction("Zu Playlist", Icons.AutoMirrored.Filled.PlaylistAdd, "sel-playlist") { addTo = chosen }
            SelAction("Herz", Icons.Default.Favorite, "sel-fav") {
                app.library.addFavorites(chosen)
                Toast.makeText(context, "${chosen.size} Titel zu Lieblingssongs", Toast.LENGTH_SHORT).show()
                selection.clear()
            }
            if (playlist != null) {
                SelAction("Entfernen", Icons.Default.PlaylistRemove, "sel-remove") {
                    chosen.forEach { app.library.remove(it, playlist) }
                    selection.clear()
                }
            }
            SelAction("Löschen", Icons.Default.Delete, "sel-delete", destructive = true) { confirmDelete = chosen }
        }
    }

    addTo?.let { list ->
        AddManyToPlaylistSheet(list, onDismiss = { addTo = null }) { selection.clear() }
    }
    confirmDelete?.let { list ->
        if (list.any { !app.library.canModifyFile(it) }) {
            FileAccessDialog("Mindestens ein Lied", onDismiss = { confirmDelete = null }) {
                app.library.deleteAll(list); selection.clear()
            }
            return@let
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${list.size} Titel löschen?") },
            text = {
                Text(if (list.any { it.isLinked }) "Die Titel werden vom Handy gelöscht – auch aus Samsung Music bzw. dem Download-Ordner – und aus allen Playlists entfernt."
                    else "Die Titel werden vom Gerät gelöscht und aus allen Playlists entfernt.")
            },
            confirmButton = {
                TextButton(onClick = { app.library.deleteAll(list); confirmDelete = null; selection.clear() },
                    modifier = Modifier.testTag("sel-delete-confirm")) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SelAction(label: String, icon: ImageVector, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else Accent
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, textAlign = TextAlign.Center)
    }
}

/** Mehrere Titel zu einer Playlist hinzufügen */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManyToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit, onDone: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    val lib by app.library.state.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    fun done(playlist: Playlist) {
        app.library.addAll(tracks, playlist)
        Toast.makeText(context, "${tracks.size} Titel zu „${playlist.name}“ hinzugefügt", Toast.LENGTH_SHORT).show()
        onDismiss(); onDone()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("${tracks.size} Titel zu Playlist hinzufügen", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clickable { showNew = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = Accent); Spacer(Modifier.width(12.dp)); Text("Neue Playlist …", color = Accent)
            }
            lib.playlists.forEach { playlist ->
                Row(
                    Modifier.fillMaxWidth().clickable { done(playlist) }.padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("addTo-${playlist.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(app.library.tracksIn(playlist).firstOrNull(), 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name)
                        Text("${playlist.trackIds.size} Titel", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showNew) {
        TextInputDialog("Neue Playlist", "Name", "", "Erstellen", onDismiss = { showNew = false }) { name ->
            done(app.library.createPlaylist(name))
        }
    }
}
