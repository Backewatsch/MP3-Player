package com.backewatsch.mp3player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.update.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val update by app.updates.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val conversion by app.library.conversionStatus.collectAsState()
    val player by app.player.state.collectAsState()
    var confirmConvert by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (update == UpdateChecker.State.Idle) app.updates.check() }
    val convertible = lib.tracks.count { !com.backewatsch.mp3player.data.FileNames.isMp3(it.fileName) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(null, 56.dp, 13.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("ILLEGAL FREEDOM MUSIC", fontWeight = FontWeight.SemiBold)
                    Text("Version ${app.updates.currentVersion} (Build ${app.updates.currentBuild})",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("currentVersion"))
                    Text("${lib.tracks.size} Titel · ${lib.playlists.size} Playlists", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionHeader("Updates")
            Card {
                when (val s = update) {
                    UpdateChecker.State.Idle, UpdateChecker.State.Checking -> InfoRow("Suche nach Updates …", null)
                    UpdateChecker.State.UpToDate -> InfoRow("Du hast die neueste Version.", Icons.Default.CheckCircle, Color(0xFF34C759))
                    UpdateChecker.State.NoReleases -> InfoRow("Noch keine Version veröffentlicht.", null)
                    is UpdateChecker.State.Failed -> InfoRow(s.message, Icons.Default.Warning, Color(0xFFFF9500))
                    is UpdateChecker.State.Downloading -> Column(Modifier.padding(16.dp)) {
                        Text("Update wird geladen … ${(s.progress * 100).toInt()} %")
                        LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                    }
                    is UpdateChecker.State.Available -> {
                        InfoRow("Update verfügbar: Version ${s.release.version}", Icons.Default.AutoAwesome, Accent)
                        if (s.release.notes.isNotBlank()) {
                            Text(s.release.notes.take(500), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (s.release.apkUrl != null) {
                            ActionLine("Jetzt installieren", Icons.Default.Download, "installUpdate") { app.scope.launch { app.updates.install(s.release) } }
                        }
                        ActionLine("Release auf GitHub ansehen", Icons.Default.OpenInBrowser) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.release.pageUrl)))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionLine("Jetzt nach Updates suchen", Icons.Default.Refresh, "checkUpdatesButton") { app.scope.launch { app.updates.check() } }
            }
            Text("Beim ersten Update fragt Android, ob diese App Apps installieren darf – einmal erlauben. Deine Musik bleibt erhalten.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            SectionHeader("Konverter")
            Card {
                if (conversion != null) {
                    Column(Modifier.padding(16.dp)) {
                        Text(conversion!!, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                    }
                } else {
                    ActionLine("Alle in MP3 umwandeln ($convertible)", Icons.Default.Autorenew, "convertAllButton",
                        enabled = convertible > 0) { confirmConvert = true }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            }
            Text("Neue Titel werden beim Laden automatisch zu MP3 (192 kbit/s). Hier wandelst du vorhandene Dateien in anderen Formaten um. Playlists, Kurzwahl und Cover bleiben erhalten.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Spacer(Modifier.height(12.dp))
            Text("MP3-Kodierung: LAME 3.100 (LGPL)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
    if (confirmConvert) {
        AlertDialog(
            onDismissRequest = { confirmConvert = false },
            title = { Text("Alle $convertible Titel in MP3 umwandeln?") },
            text = { Text("Die Originaldateien werden ersetzt. Der gerade laufende Titel wird übersprungen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmConvert = false
                    app.scope.launch {
                        val (ok, failed) = app.library.convertAllToMp3(player.currentId)
                        result = "Fertig: $ok umgewandelt" + if (failed > 0) ", $failed fehlgeschlagen." else "."
                    }
                }) { Text("Umwandeln") }
            },
            dismissButton = { TextButton(onClick = { confirmConvert = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun InfoRow(text: String, icon: ImageVector?, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = tint); Spacer(Modifier.width(10.dp)) }
        Text(text, color = tint)
    }
}

@Composable
private fun ActionLine(text: String, icon: ImageVector, tag: String = text, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, color = if (enabled) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
