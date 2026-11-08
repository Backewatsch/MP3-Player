package com.backewatsch.mp3player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.download.DownloadRepository.State

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val items by app.downloads.items.collectAsState()
    val focus = LocalFocusManager.current
    var link by remember { mutableStateOf("") }
    LaunchedEffect(items.size) { app.downloads.markSeen() }
    val valid = link.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    val start = {
        if (valid) {
            app.downloads.download(link.trim()); link = ""; focus.clearFocus()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("closeDownloads")) { Text("Fertig") }
            Spacer(Modifier.weight(1f))
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = app.downloads::clearFinished, enabled = items.isNotEmpty()) { Text("Leeren") }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                link, { link = it }, singleLine = true, placeholder = { Text("Link zu Audio- oder Videodatei") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { start() }),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).testTag("downloadLinkField"),
            )
            TextButton(onClick = { start() }, enabled = valid, modifier = Modifier.testTag("downloadStartButton")) { Text("Laden") }
        }
        Text(
            "Alles wird zu MP3: Videos und andere Audioformate werden automatisch umgewandelt. Fertige Titel landen in der Mediathek, in „Heruntergeladen“ und in der Wiedergabeliste.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text("Noch keine Downloads", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (item.state) {
                        State.Running, State.Converting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Accent)
                        State.Finished -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759))
                        is State.Failed -> Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9500))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, maxLines = 1)
                        when (val s = item.state) {
                            State.Running -> {
                                LinearProgressIndicator(progress = { item.progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
                                Text("${(item.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                            }
                            State.Converting -> Text("Wird in MP3 umgewandelt …", style = MaterialTheme.typography.bodySmall)
                            State.Finished -> Text("In der Playlist „Heruntergeladen“", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("downloadFinished"))
                            is State.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (item.state is State.Failed) IconButton(onClick = { app.downloads.retry(item) }) { Icon(Icons.Default.Refresh, "Erneut versuchen") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
