package com.backewatsch.mp3player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.backewatsch.mp3player.data.DeviceMusicImporter
import kotlinx.coroutines.launch

/** „Von Samsung Music übertragen“: Lieder und Playlists vom Handy übernehmen */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val importer = app.deviceImporter
    val progress by importer.progress.collectAsState()
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    var songs by remember { mutableStateOf<List<DeviceMusicImporter.DeviceSong>?>(null) }
    var songResult by remember { mutableStateOf<String?>(null) }
    var playlistResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pickPlaylists = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) app.scope.launch {
            busy = true
            val r = importer.importPlaylistFiles(uris)
            playlistResult = "${r.playlists} Playlists mit ${r.tracks} Titeln übertragen" +
                if (r.missing > 0) " · ${r.missing} Titel nicht gefunden (Datei nicht mehr auf dem Handy)." else "."
            busy = false
        }
    }

    LaunchedEffect(granted) {
        if (granted) songs = importer.scanSongs()
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text("Von Samsung Music übertragen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Deine Lieder bleiben in Samsung Music erhalten – die App legt eigene Kopien an (als MP3).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Schritt 1: Lieder
            StepTitle("1", "Alle Lieder übertragen", Icons.Default.MusicNote)
            when {
                !granted -> {
                    Text("Die App braucht Zugriff auf die Musik auf deinem Handy.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { askPermission.launch(permission) }, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.testTag("grantMusicAccess")) { Text("Zugriff erlauben") }
                }
                songs == null -> Text("Suche Musik auf dem Handy …", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val list = songs!!
                    val already = list.count { app.library.trackFromSource(it.sourceKey) != null }
                    val size = Formatter.formatShortFileSize(context, list.sumOf { it.size })
                    Text("${list.size} Lieder gefunden ($size)" + if (already > 0) ", davon $already schon übertragen." else ".",
                        modifier = Modifier.testTag("songsFound"))
                    Spacer(Modifier.height(8.dp))
                    progress?.let { p ->
                        Text("Übertrage ${p.done + 1} von ${p.total}: ${p.current}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        LinearProgressIndicator(progress = { (p.done + 1f) / p.total }, Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Accent)
                    }
                    Button(
                        onClick = {
                            app.scope.launch {
                                busy = true
                                val r = importer.importSongs(list)
                                val pl = importer.importMediaStorePlaylists()
                                songResult = "${r.imported} Lieder übertragen" +
                                    (if (r.skipped > 0) ", ${r.skipped} waren schon da" else "") +
                                    (if (r.failed > 0) ", ${r.failed} fehlgeschlagen" else "") + "." +
                                    (if (pl.playlists > 0) " Zusätzlich ${pl.playlists} Playlists vom Handy erkannt." else "")
                                busy = false
                            }
                        },
                        enabled = !busy && list.size > already,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth().testTag("transferSongs"),
                    ) { Text(if (list.size > already) "${list.size - already} Lieder übertragen" else "Alles übertragen") }
                }
            }
            songResult?.let { ResultLine(it, "songResult") }

            // Schritt 2: Playlists
            StepTitle("2", "Playlists übertragen", Icons.AutoMirrored.Filled.QueueMusic)
            Text("So exportierst du deine Playlists in Samsung Music:", style = MaterialTheme.typography.bodyMedium)
            listOf(
                "Samsung Music öffnen → Reiter „Playlists“",
                "Oben auf ⋮ (Mehr) tippen → „Playlists exportieren“ bzw. „Exportieren“",
                "Alle Playlists auswählen → „Exportieren“ (sie landen als .m3u-Dateien im Ordner „Music“)",
                "Hier auf „Playlist-Dateien wählen“ tippen, zum Ordner „Music“ gehen und alle .m3u-Dateien markieren",
            ).forEachIndexed { i, text ->
                Text("${i + 1}. $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = { pickPlaylists.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "*/*")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("pickPlaylists"),
            ) { Text("Playlist-Dateien wählen", color = Accent) }
            Text("Lieder aus einer Playlist, die noch nicht übertragen sind, werden automatisch mitgenommen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            playlistResult?.let { ResultLine(it, "playlistResult") }
        }
    }
}

@Composable
private fun StepTitle(number: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResultLine(text: String, tag: String) {
    Row(Modifier.padding(top = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
