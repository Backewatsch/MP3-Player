package com.backewatsch.mp3player.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.backewatsch.mp3player.browser.BrowserController
import com.backewatsch.mp3player.data.FileNames
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val browser = app.browser
    val state by browser.state.collectAsState()
    val downloads by app.downloads.items.collectAsState()
    val unseen by app.downloads.unseen.collectAsState()
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showFound by remember { mutableStateOf(false) }
    LaunchedEffect(state.url, editing) { if (!editing) address = state.url }
    val active = downloads.count { it.state is com.backewatsch.mp3player.download.DownloadRepository.State.Running ||
        it.state is com.backewatsch.mp3player.download.DownloadRepository.State.Converting }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address, onValueChange = { address = it }, singleLine = true,
                placeholder = { Text("Suchen oder Adresse eingeben") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { browser.submit(address); focus.clearFocus() }),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused }.testTag("addressField"),
            )
        }
        if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, Modifier.fillMaxWidth(), color = Accent)
        if (FileNames.isYouTube(state.host)) {
            Row(Modifier.fillMaxWidth().background(Color(0x33FF9500)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null)
                Spacer(Modifier.width(8.dp))
                Text("Downloads von YouTube werden nicht unterstützt – das verbieten die YouTube-Nutzungsbedingungen. Nutze freie Quellen von der Startseite.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("youtubeHint"))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { (browser.webView.parent as? ViewGroup)?.removeView(browser.webView); browser.webView },
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.align(Alignment.BottomCenter)) {
                MediaBanner(state.detected, browser)
            }
            state.notice?.let { notice ->
                LaunchedEffect(notice) { delay(3500); browser.notify(null) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                ) { Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = browser::back, enabled = state.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            IconButton(onClick = browser::forward, enabled = state.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Vor") }
            IconButton(onClick = { browser.scan { if (it.isEmpty()) browser.notify("Auf dieser Seite wurden keine Audio- oder Videodateien gefunden.") else showFound = true } },
                modifier = Modifier.testTag("scanButton")) {
                BadgedBox(badge = { if (state.found.isNotEmpty()) Badge(containerColor = Accent) { Text("${state.found.size}") } }) {
                    Icon(Icons.Default.GraphicEq, "Audio- und Videodateien auf Seite suchen", tint = Accent)
                }
            }
            IconButton(onClick = { nav.showDownloads = true }, modifier = Modifier.testTag("downloadsButton")) {
                BadgedBox(badge = { if (active + unseen > 0) Badge(containerColor = Accent) { Text("${active + unseen}") } }) {
                    Icon(Icons.Default.Download, "Downloads")
                }
            }
            IconButton(onClick = browser::loadStartPage) { Icon(Icons.Default.Home, "Startseite") }
            IconButton(onClick = browser::reload) { Icon(if (state.loading) Icons.Default.Close else Icons.Default.Refresh, "Neu laden") }
        }
    }
    if (showFound) FoundMediaSheet(state.found, browser, onDismiss = { showFound = false })
}

@Composable
private fun MediaBanner(detected: BrowserController.Detected?, browser: BrowserController) {
    when (detected) {
        is BrowserController.Detected.Downloadable -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp,
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Medium erkannt", fontWeight = FontWeight.SemiBold)
                    Text(detected.url.substringAfterLast('/').substringBefore('?'), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = browser::downloadDetected, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.testTag("downloadDetectedMedia")) { Text("Als MP3 laden") }
                IconButton(onClick = browser::dismissDetected) { Icon(Icons.Default.Close, "Schließen") }
            }
        }
        BrowserController.Detected.Stream -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null)
                Spacer(Modifier.width(10.dp))
                Text("Dieses Medium wird als Stream abgespielt und kann nicht als Datei geladen werden.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = browser::dismissDetected) { Icon(Icons.Default.Close, "Schließen") }
            }
        }
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoundMediaSheet(found: List<String>, browser: BrowserController, onDismiss: () -> Unit) {
    val app = LocalApp.current
    var started by remember { mutableStateOf(setOf<String>()) }
    var askName by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("${found.size} Dateien gefunden", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp))
        Button(
            onClick = { askName = true }, enabled = started.size < found.size,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("downloadAllAsPlaylist"),
        ) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null); Spacer(Modifier.width(8.dp)); Text("Alle als neue Playlist laden") }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
            items(found, key = { it }) { url ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(java.net.URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "UTF-8"), maxLines = 2)
                        Text(android.net.Uri.parse(url).host.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (url in started) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759))
                    else IconButton(onClick = { browser.startDownload(url); started = started + url }) { Icon(Icons.Default.Download, "Laden") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (askName) {
        TextInputDialog("Neue Playlist", "Name", browser.state.value.title.take(60).ifBlank { "Neue Playlist" }, "Laden",
            onDismiss = { askName = false }) { name ->
            val playlist = app.library.createPlaylist(name)
            found.filter { it !in started }.forEach { browser.startDownload(it, playlistId = playlist.id) }
            started = found.toSet()
            browser.notify("Downloads gestartet – die Titel landen in der Playlist „${playlist.name}“.")
            onDismiss()
        }
    }
}
