package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.backewatsch.mp3player.data.FileNames
import com.backewatsch.mp3player.data.MetadataReader
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.model.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Welcher Dialog rund um einen Titel gerade offen ist */
class TrackActionsState {
    var menu by mutableStateOf<Pair<Track, Playlist?>?>(null)
    var addToPlaylist by mutableStateOf<Track?>(null)
    var info by mutableStateOf<Track?>(null)
    var edit by mutableStateOf<Track?>(null)
    var confirmDelete by mutableStateOf<Track?>(null)
    /** Für diesen Titel ein eigenes Cover aus der Galerie wählen */
    var pickCoverFor by mutableStateOf<Track?>(null)

    fun open(track: Track, playlist: Playlist? = null) { menu = track to playlist }
}

@Composable
fun TrackActionsHost(state: TrackActionsState) {
    val hostApp = LocalApp.current
    val hostContext = LocalContext.current
    // Galerie-Auswahl lebt hier (das Menü ist beim Ergebnis schon geschlossen)
    val coverPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val track = state.pickCoverFor
        state.pickCoverFor = null
        if (uri != null && track != null) hostApp.scope.launch {
            val jpeg = withContext(Dispatchers.IO) {
                hostContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { MetadataReader.shrinkArtwork(it, 800) }
            }
            if (jpeg != null) {
                withContext(Dispatchers.IO) { hostApp.library.setCustomCover(track, jpeg) }
                hostApp.player.refreshArtwork()
            } else Toast.makeText(hostContext, "Bild konnte nicht gelesen werden.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(state.pickCoverFor) {
        if (state.pickCoverFor != null) coverPicker.launch(
            androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    state.menu?.let { (track, playlist) -> TrackMenuSheet(track, playlist, state) }
    state.addToPlaylist?.let { AddToPlaylistSheet(it) { state.addToPlaylist = null } }
    state.info?.let { SongInfoSheet(it) { state.info = null } }
    state.edit?.let { EditTrackDialog(it) { state.edit = null } }
    state.confirmDelete?.let { track ->
        val app = LocalApp.current
        if (!app.library.canModifyFile(track)) {
            // Verknüpftes Original, App darf es (noch) nicht löschen
            FileAccessDialog("„${track.title}“", onDismiss = { state.confirmDelete = null }) { app.library.delete(track) }
            return@let
        }
        AlertDialog(
            onDismissRequest = { state.confirmDelete = null },
            title = { Text("Lied löschen?") },
            text = {
                Text(if (track.isLinked) "„${track.title}“ wird vom Handy gelöscht – auch aus Samsung Music bzw. dem Download-Ordner – und aus allen Playlists entfernt."
                    else "„${track.title}“ wird vom Gerät gelöscht und aus allen Playlists entfernt.")
            },
            confirmButton = {
                TextButton(onClick = { app.library.delete(track); state.confirmDelete = null }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { state.confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackMenuSheet(track: Track, playlist: Playlist?, state: TrackActionsState) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val current = lib.tracks.firstOrNull { it.id == track.id } ?: track
    val close = { state.menu = null }

    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(current, 54.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(current.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(listOf(current.artist, current.album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Als Nächstes abspielen", Icons.Default.SkipNext, Modifier.weight(1f)) {
                    app.player.playNext(current)
                    Toast.makeText(context, "„${current.title}“ kommt als Nächstes", Toast.LENGTH_SHORT).show()
                    close()
                }
                Tile("Zu Playlist hinzufügen", Icons.AutoMirrored.Filled.PlaylistAdd, Modifier.weight(1f)) {
                    close(); state.addToPlaylist = current
                }
                Tile("Teilen", Icons.Default.Share, Modifier.weight(1f)) {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", app.library.fileOf(current))
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/mpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, current.title))
                    }.onFailure { Toast.makeText(context, "Teilen nicht möglich: ${it.message}", Toast.LENGTH_LONG).show() }
                    close()
                }
            }
            Spacer(Modifier.height(10.dp))
            val favorite = current.id in lib.favorites
            ActionRow(if (favorite) "Aus Lieblingssongs entfernen" else "Zu Lieblingssongs hinzufügen",
                if (favorite) Icons.Default.HeartBroken else Icons.Default.Favorite) {
                app.library.toggleFavorite(current)
                Toast.makeText(context, if (favorite) "Aus Lieblingssongs entfernt" else "♥ Zu Lieblingssongs hinzugefügt", Toast.LENGTH_SHORT).show()
                close()
            }
            ActionRow("Mix starten", Icons.Default.Radio) { app.player.startMix(current, app.library.tracks); close() }
            ActionRow("In die Wiedergabeliste", Icons.AutoMirrored.Filled.QueueMusic) {
                app.player.addToQueue(current)
                Toast.makeText(context, "„${current.title}“ ans Ende der Wiedergabeliste", Toast.LENGTH_SHORT).show()
                close()
            }
            if (current.album.isNotBlank()) ActionRow("Album anzeigen", Icons.Default.Album) { close(); nav.push(LibRoute.Album(current.album)) }
            if (current.artist.isNotBlank()) ActionRow("Künstlerseite anzeigen", Icons.Default.Mic) { close(); nav.push(LibRoute.Artist(current.artist)) }
            ActionRow("Mitwirkende an Song anzeigen", Icons.Default.People) { close(); state.info = current }
            ActionRow(if (app.library.isPinned(current)) "Von Kurzwahl lösen" else "An Kurzwahl anpinnen", Icons.Default.PushPin) {
                app.library.togglePin(current); close()
            }
            ActionRow("Infos bearbeiten", Icons.Default.Edit) { close(); state.edit = current }
            ActionRow("Zuschneiden", Icons.Default.ContentCut) { close(); nav.openEditor(current) }
            ActionRow("Cover ändern", Icons.Default.Image) { close(); state.pickCoverFor = current }
            if (app.library.hasCustomCover(current)) {
                ActionRow("Eigenes Cover entfernen", Icons.Default.HideImage) {
                    close()
                    app.scope.launch {
                        withContext(Dispatchers.IO) { app.library.removeCustomCover(current); app.library.awaitFileWrites() }
                        app.player.refreshArtwork()
                    }
                }
            }
            if (!FileNames.isMp3(current.fileName)) {
                ActionRow("In MP3 umwandeln", Icons.Default.Autorenew) {
                    close()
                    app.scope.launch {
                        app.library.setConversionStatus("Wandle „${current.title}“ in MP3 um …")
                        try {
                            app.library.convertToMp3(current)
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                        } finally {
                            app.library.setConversionStatus(null)
                        }
                    }
                }
            }
            if (playlist != null) ActionRow("Aus Playlist entfernen", Icons.Default.PlaylistRemove) { app.library.remove(current, playlist); close() }
            if (player.queueIds.isNotEmpty()) ActionRow("Wiedergabeliste schließen", Icons.Default.Delete) { app.player.stop(); close() }
            ActionRow("Aus Mediathek entfernen", Icons.Default.BookmarkRemove, destructive = true) { close(); state.confirmDelete = current }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Tile(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).testTag("action-$title"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null) }
        Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ActionRow(title: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp).testTag("action-$title"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(18.dp))
        Text(title, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(track: Track, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val lib by app.library.state.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Zu Playlist hinzufügen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clickable { showNew = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = Accent); Spacer(Modifier.width(12.dp)); Text("Neue Playlist …", color = Accent)
            }
            lib.playlists.forEach { playlist ->
                val contains = track.id in playlist.trackIds
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (contains) app.library.remove(track, playlist) else app.library.add(track, playlist)
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(app.library.tracksIn(playlist).firstOrNull(), 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name)
                        Text("${playlist.trackIds.size} Titel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (contains) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                        tint = if (contains) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showNew) {
        TextInputDialog("Neue Playlist", "Name", "", "Erstellen", onDismiss = { showNew = false }) { name ->
            app.library.add(track, app.library.createPlaylist(name))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongInfoSheet(track: Track, onDismiss: () -> Unit) {
    val app = LocalApp.current
    var credits by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(track.id) {
        credits = withContext(Dispatchers.IO) { MetadataReader.credits(app.library.fileOf(track)) }
    }
    val file = app.library.fileOf(track)
    val size = file.length()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Mitwirkende", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, 60.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.title, fontWeight = FontWeight.SemiBold)
                    Text(track.artist.ifEmpty { "Unbekannter Interpret" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionLabel("Mitwirkende")
            when {
                credits == null -> Text("Lädt …", color = MaterialTheme.colorScheme.onSurfaceVariant)
                credits!!.isEmpty() -> Text("In der Datei sind keine Mitwirkenden hinterlegt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> credits!!.forEach { (label, value) -> InfoLine(label, value) }
            }
            SectionLabel("Datei")
            InfoLine("Format", FileNames.extension(track.fileName).uppercase())
            InfoLine("Dauer", formatTime(track.durationMs))
            InfoLine("Größe", android.text.format.Formatter.formatShortFileSize(LocalContext.current, size))
            if (track.durationMs > 0 && size > 0) InfoLine("Bitrate (ca.)", "${(size * 8 / track.durationMs)} kbit/s")
            InfoLine("Datei", track.fileName)
            InfoLine("Hinzugefügt", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(track.addedAt)))
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("info-$label")) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun EditTrackDialog(track: Track, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var genre by remember { mutableStateOf(track.genre) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Infos bearbeiten") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true,
                    modifier = Modifier.testTag("editTitle"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(artist, { artist = it }, label = { Text("Interpret") }, singleLine = true,
                    modifier = Modifier.testTag("editArtist"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true,
                    modifier = Modifier.testTag("editAlbum"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true,
                    modifier = Modifier.testTag("editGenre"))
                if (FileNames.isMp3(track.fileName)) {
                    Text("Wird auch in der MP3-Datei gespeichert – andere Apps zeigen die Infos dann ebenfalls an.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                app.scope.launch {
                    withContext(Dispatchers.IO) { app.library.editInfo(track, title, artist, album, genre) }
                    app.player.refreshArtwork()
                    Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.testTag("editSave")) { Text("Sichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

