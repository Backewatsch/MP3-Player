package com.backewatsch.mp3player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.data.Search
import com.backewatsch.mp3player.data.adoptImported
import com.backewatsch.mp3player.data.deleteImported
import com.backewatsch.mp3player.data.importedFile
import com.backewatsch.mp3player.data.syncImportedFolder
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.model.formatTime
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reinhören in ein importiertes Lied – bewusst getrennt vom normalen Player: Importierte Lieder sollen nicht in
 * Wiedergabeliste, Verlauf oder Statistik geraten, solange sie nicht übernommen sind.
 */
class SongPreview {
    /** Kennung des Liedes, das gerade zum Reinhören läuft */
    var playingId by mutableStateOf<String?>(null)
        private set
    private var player: android.media.MediaPlayer? = null

    /** Startet das Lied; läuft es schon, hält es an. */
    fun toggle(id: String, file: File) {
        if (playingId == id) {
            stop()
            return
        }
        stop()
        try {
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            player = mp
            playingId = id
            mp.prepareAsync()
        } catch (e: Exception) {
            // Datei nicht lesbar: einfach nichts abspielen
            stop()
        }
    }

    fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
        playingId = null
    }
}

/** „1 Lied“ / „5 Lieder“ */
private fun songs(count: Int) = if (count == 1) "1 Lied" else "$count Lieder"

/**
 * „Importierte Lieder“: Lieder von einem anderen Handy, die noch nicht in der Titel-Liste sind.
 * Antippen = reinhören, ＋ = in die Titel-Liste übernehmen, gedrückt halten = mehrere auswählen.
 */
@Composable
fun ImportedScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val lib by app.library.state.collectAsState()
    val player by app.player.state.collectAsState()
    val tracks = lib.imported
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(tracks, query) { Search.filter(tracks, query) }
    val selection = rememberSelection()
    val preview = remember { SongPreview() }
    var confirmDelete by remember { mutableStateOf<List<Track>?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Beim Öffnen mit dem Ordner abgleichen (z. B. per USB hineinkopierte Lieder)
    LaunchedEffect(Unit) { app.library.syncImportedFolder() }
    // Reinhören endet beim Verlassen der Seite, wenn die App in den Hintergrund geht und sobald der normale Player spielt
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) preview.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            preview.stop()
        }
    }
    LaunchedEffect(player.isPlaying) { if (player.isPlaying) preview.stop() }

    fun adopt(list: List<Track>) {
        if (list.isEmpty() || busy) return
        if (list.any { it.id == preview.playingId }) preview.stop()
        busy = true
        app.scope.launch {
            val count = try { app.library.adoptImported(list.map { it.id }) } finally { busy = false }
            selection.clear()
            Notice.show(context, if (count == 0) "Die Lieder ließen sich nicht übernehmen" else "${songs(count)} in die Titel-Liste übernommen")
        }
    }

    fun delete(list: List<Track>) {
        if (list.isEmpty() || busy) return
        if (list.any { it.id == preview.playingId }) preview.stop()
        busy = true
        app.scope.launch {
            val count = try { app.library.deleteImported(list.map { it.id }) } finally { busy = false }
            selection.clear()
            Notice.show(context, "${songs(count)} gelöscht")
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            ImportedSelectionBar(selection, shown, onAdopt = { adopt(it) }, onDelete = { confirmDelete = it })
        } else {
            ScreenTopBar("Importierte Lieder", onBack = { nav.pop() })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        LazyColumn(Modifier.fillMaxSize().testTag("importedList")) {
            if (tracks.isEmpty()) {
                item(key = "importedEmpty") {
                    EmptyHint("Keine importierten Lieder mehr.",
                        "Neue Lieder holst du über das Zahnrad › „Neues Handy“ › „Lieder importieren“ hierher.")
                }
            } else {
                item(key = "importedHint") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("Diese Lieder sind noch nicht in deiner Titel-Liste. Tippe auf ＋, um ein Lied zu übernehmen.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Antippen = reinhören · Gedrückt halten = mehrere auswählen",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { adopt(tracks) }, enabled = !busy,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("importedAdoptAll")) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp))
                                Text("Alle übernehmen", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { confirmDelete = tracks }, enabled = !busy,
                                modifier = Modifier.testTag("importedDeleteAll")) {
                                Text("Alle löschen", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                listSearch(query, { query = it }, shown.size)
            }
            items(shown, key = { it.id }) { track ->
                ImportedRow(
                    track = track,
                    previewing = preview.playingId == track.id,
                    selection = selection,
                    enabled = !busy,
                    onPreview = {
                        // Der normale Player macht Pause, solange reingehört wird
                        if (preview.playingId != track.id && player.isPlaying) app.player.togglePlayPause()
                        preview.toggle(track.id, app.library.importedFile(track))
                    },
                    onAdopt = { adopt(listOf(track)) },
                    onDelete = { confirmDelete = listOf(track) },
                )
            }
            item(key = "importedEnd") { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${songs(list.size)} löschen?") },
            text = { Text("Die Dateien werden aus dem Ordner „Importierte Lieder“ gelöscht. Deine Titel-Liste bleibt unverändert.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; delete(list) }, modifier = Modifier.testTag("importedDeleteConfirm")) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

/** Zeile eines importierten Liedes */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportedRow(
    track: Track,
    previewing: Boolean,
    selection: SelectionState,
    enabled: Boolean,
    onPreview: () -> Unit,
    onAdopt: () -> Unit,
    onDelete: () -> Unit,
) {
    val selecting = selection.active
    val selected = track.id in selection.ids
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.16f)
                    previewing -> Accent.copy(alpha = 0.09f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                onClick = { if (selecting) selection.toggle(track.id) else onPreview() },
                onLongClick = { selection.toggle(track.id) },
            )
            .padding(start = 8.dp, top = 7.dp, bottom = 7.dp)
            .testTag("imported-${track.title}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, 52.dp, 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (previewing) Accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (previewing) FontWeight.Bold else FontWeight.Medium)
            Text(track.artist.ifEmpty { "Unbekannter Interpret" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (previewing) PlayingBars(true, Accent, Modifier.padding(start = 8.dp))
        Text(formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { selection.toggle(track.id) },
                colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Color.Black),
                modifier = Modifier.testTag("importedCheck-${track.title}"))
        } else {
            IconButton(onClick = onAdopt, enabled = enabled, modifier = Modifier.testTag("importedAdd")) {
                Icon(Icons.Default.Add, "„${track.title}“ in die Titel-Liste übernehmen", tint = Accent)
            }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("importedDelete")) {
                Icon(Icons.Default.Delete, "„${track.title}“ löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Leiste oben, solange etwas ausgewählt ist: Anzahl, Alle auswählen, Übernehmen und Löschen */
@Composable
private fun ImportedSelectionBar(
    selection: SelectionState,
    all: List<Track>,
    onAdopt: (List<Track>) -> Unit,
    onDelete: (List<Track>) -> Unit,
) {
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
            TextButton(onClick = { onAdopt(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("importedAdoptSelected")) {
                Icon(Icons.Default.Add, null, tint = Accent); Spacer(Modifier.width(6.dp))
                Text("Übernehmen", color = Accent)
            }
            TextButton(onClick = { onDelete(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("importedDeleteSelected")) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                Text("Löschen", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
