package com.backewatsch.mp3player.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.backewatsch.mp3player.data.canTrim
import com.backewatsch.mp3player.data.exactDurationMs
import com.backewatsch.mp3player.data.trim
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.model.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tab „Bearbeiten“: Lied auswählen und zuschneiden */
@Composable
fun EditorScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val track = nav.editTrackId?.let { id -> lib.tracks.firstOrNull { it.id == id } }
    if (track != null) {
        BackHandler { nav.editTrackId = null }
        TrimEditor(track, onClose = { nav.editTrackId = null })
    } else {
        EditorPicker(lib.tracks) { nav.editTrackId = it.id }
    }
}

@Composable
private fun EditorPicker(tracks: List<Track>, onPick: (Track) -> Unit) {
    val app = LocalApp.current
    val player by app.player.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(tracks, query) {
        val q = query.trim()
        tracks.filter { q.isEmpty() || it.title.contains(q, true) || it.artist.contains(q, true) }
            .sortedByDescending { it.addedAt }
    }
    Column(Modifier.fillMaxSize().testTag("editorScreen")) {
        ScreenTopBar("Bearbeiten")
        LazyColumn(Modifier.fillMaxSize().testTag("editorList")) {
            item {
                Text("Wähle ein Lied, um es zuzuschneiden – z. B. die letzten Sekunden mit Gerede oder Werbung wegschneiden.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                SearchPill(query, { query = it }, "Lied suchen",
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp).testTag("editorSearch"), clearTag = "editorSearchClear")
            }
            if (tracks.isEmpty()) {
                item { EmptyHint("Noch keine Musik", "Sobald Lieder in der Mediathek sind, kannst du sie hier bearbeiten.") }
            } else if (shown.isEmpty()) {
                item { EmptyHint("Nichts gefunden", "Zu „${query.trim()}“ gibt es kein Lied.") }
            }
            items(shown, key = { it.id }) { t ->
                TrackRow(t, player.currentId == t.id, player.isPlaying, onPlay = { onPick(t) }, onMore = { onPick(t) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 3:07,4 – Zeit mit Zehntelsekunden */
internal fun formatPrecise(ms: Long): String = formatTime(ms).let { if (ms <= 0) "0:00" else it } + ",${(ms % 1000) / 100}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimEditor(track: Track, onClose: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player by app.player.state.collectAsState()
    val trimmable = remember(track) { app.library.canTrim(track) }
    var duration by remember(track.id) { mutableLongStateOf(0L) }
    var start by remember(track.id) { mutableLongStateOf(0L) }
    var end by remember(track.id) { mutableLongStateOf(-1L) }
    LaunchedEffect(track.id, track.durationMs) {
        duration = if (trimmable) app.library.exactDurationMs(track) else track.durationMs
        if (end < 0 || end > duration) end = duration
        if (start >= end) start = 0
    }
    /** Ein-/Ausblenden in Sekunden (0 = aus) */
    var fadeIn by remember(track.id) { mutableStateOf(0) }
    var fadeOut by remember(track.id) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var needAccess by remember { mutableStateOf(false) }
    /** Vorhören bis zu dieser Stelle, dann Pause */
    var previewUntil by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(previewUntil) {
        val until = previewUntil ?: return@LaunchedEffect
        val began = System.currentTimeMillis()
        while (true) {
            delay(200)
            val s = app.player.state.value
            // Kurz Zeit lassen, bis die Wiedergabe angelaufen ist
            if (System.currentTimeMillis() - began > 2_000 && (s.currentId != track.id || !s.isPlaying)) break
            if (s.positionMs >= until) { app.player.togglePlayPause(); break }
        }
        previewUntil = null
    }

    // Probehören ersetzt kurz die Wiedergabeliste → vorher merken und beim Verlassen wiederherstellen
    val savedQueue = remember(track.id) { mutableStateOf<com.backewatsch.mp3player.player.PlayerConnection.SavedQueue?>(null) }
    androidx.compose.runtime.DisposableEffect(track.id) {
        onDispose { savedQueue.value?.let { app.player.restoreQueue(it) } }
    }

    fun preview(from: Long, until: Long?) {
        val playing = app.player.state.value
        if (playing.currentId != track.id) {
            if (savedQueue.value == null) savedQueue.value = app.player.saveQueue()
            app.player.play(listOf(track), 0)
        }
        app.player.seekTo(from.coerceAtLeast(0))
        if (playing.currentId == track.id && !playing.isPlaying) app.player.resumeOrStart()
        previewUntil = until
    }

    fun save(asCopy: Boolean) {
        busy = true
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                val result = app.library.trim(track, start, end, asCopy, fadeIn * 1000L, fadeOut * 1000L)
                Notice.show(context, if (asCopy) "„${result.title}“ gespeichert (${formatTime(result.durationMs)})"
                    else "„${result.title}“ gekürzt auf ${formatTime(result.durationMs)}")
                onClose()
            } catch (e: Exception) {
                Notice.show(context, "Zuschneiden fehlgeschlagen: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    val ready = trimmable && duration > 0 && end > 0
    val changed = ready && (start > 0 || end < duration || fadeIn > 0 || fadeOut > 0)
    val step = 1_000L
    fun setStart(v: Long) { start = v.coerceIn(0, (end - 1_000).coerceAtLeast(0)) }
    fun setEnd(v: Long) { end = v.coerceIn(start + 1_000, duration) }

    Column(Modifier.fillMaxSize().testTag("trimEditor")) {
        ScreenTopBar("Zuschneiden", onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, 64.dp, 16.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (duration > 0) Text("Länge: ${formatTime(duration)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!trimmable) {
                Spacer(Modifier.height(24.dp))
                Text("Dieses Lied ist keine MP3 und kann nicht zugeschnitten werden. Wandle es zuerst unter Einstellungen → „Alle in MP3 umwandeln“ um.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (!ready) {
                Spacer(Modifier.height(32.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Accent)
                return@Column
            }

            Panel("Ausschnitt") {
                // Übersicht: was bleibt (hell), was wegfällt (dunkel)
                Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                    val cutA = start.toFloat() / duration
                    val keep = (end - start).toFloat() / duration
                    val cutB = (duration - end).toFloat() / duration
                    if (cutA > 0) Spacer(Modifier.weight(cutA).fillMaxSize().background(Color.White.copy(alpha = 0.10f)))
                    Spacer(Modifier.weight(keep.coerceAtLeast(0.001f)).fillMaxSize().background(Accent))
                    if (cutB > 0) Spacer(Modifier.weight(cutB).fillMaxSize().background(Color.White.copy(alpha = 0.10f)))
                }
                RangeSlider(
                    value = start.toFloat()..end.toFloat(),
                    onValueChange = { r -> setStart(r.start.toLong()); setEnd(r.endInclusive.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    modifier = Modifier.fillMaxWidth().testTag("trimSlider"),
                )
                Text(
                    "Bleibt: ${formatPrecise(start)} – ${formatPrecise(end)}  (${formatTime(end - start)})",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("trimSummary"),
                )
                val cutText = buildList {
                    if (start > 0) add("Anfang ${formatPrecise(start)}")
                    if (end < duration) add("Ende ${formatPrecise(duration - end)}")
                }
                Text(if (cutText.isEmpty()) "Noch nichts weggeschnitten" else "Wird weggeschnitten: " + cutText.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Panel("Schnell wegschneiden") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 30).forEach { s ->
                        Pill("Letzte $s s", end == duration - s * 1000L, "trim-last$s") { setEnd(duration - s * 1000L) }
                    }
                }
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10).forEach { s ->
                        Pill("Erste $s s", start == s * 1000L, "trim-first$s") { setStart(s * 1000L) }
                    }
                    Pill("Zurücksetzen", false, "trimReset") { start = 0; end = duration }
                }
            }

            Panel("Genau einstellen") {
                FineTuneRow("Anfang", start, "trimStart", onMinus = { setStart(start - step) }, onPlus = { setStart(start + step) },
                    onListen = { preview(start, start + 5_000) })
                FineTuneRow("Ende", end, "trimEnd", onMinus = { setEnd(end - step) }, onPlus = { setEnd(end + step) },
                    onListen = { preview(end - 5_000, end) })
                val previewing = previewUntil != null && player.isPlaying && player.currentId == track.id
                if (previewing) {
                    TextButton(onClick = { app.player.togglePlayPause(); previewUntil = null }) {
                        Icon(Icons.Default.Pause, null, tint = Accent); Spacer(Modifier.width(6.dp)); Text("Vorhören stoppen", color = Accent)
                    }
                }
            }

            Panel("Sanft ein- und ausblenden") {
                FadeRow("Anfang", fadeIn, "fadeIn") { fadeIn = it }
                Spacer(Modifier.height(8.dp))
                FadeRow("Ende", fadeOut, "fadeOut") { fadeOut = it }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { save(asCopy = true) }, enabled = changed && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("trimSaveCopy"),
            ) {
                Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(8.dp)); Text("Als neues Lied speichern", fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { if (app.library.canModifyFile(track)) confirmReplace = true else needAccess = true },
                enabled = changed && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp).testTag("trimSaveReplace"),
            ) { Text("Original kürzen", fontWeight = FontWeight.Bold) }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp), color = Accent)
            Text(if (fadeIn > 0 || fadeOut > 0) "Mit Ein-/Ausblenden wird der Ton neu umgewandelt (192 kbit/s) – das dauert einen Moment. Titel, Cover und Songtext bleiben erhalten."
                else "Verlustfrei: Die Tonqualität bleibt gleich, Titel, Cover und Songtext bleiben erhalten.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }

    if (needAccess) FileAccessDialog("„${track.title}“", onDismiss = { needAccess = false })
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Original kürzen?") },
            text = {
                Text("„${track.title}“ wird dauerhaft auf ${formatTime(end - start)} gekürzt" +
                    (if (track.isLinked) " – die Originaldatei auf dem Handy, also auch in Samsung Music." else ".") +
                    " Das Weggeschnittene lässt sich nicht wiederherstellen.")
            },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; save(asCopy = false) }, modifier = Modifier.testTag("trimConfirm")) {
                    Text("Kürzen", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Abbrechen") } },
        )
    }
}

/** Auswahl der Blendenlänge: Aus, 1 s, 3 s, 5 s */
@Composable
private fun FadeRow(label: String, value: Int, tag: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(60.dp))
        listOf(0, 1, 3, 5).forEach { s ->
            Pill(if (s == 0) "Aus" else "$s s", value == s, "$tag-$s") { onChange(s) }
        }
    }
}

/** Abschnitt als ruhige, abgerundete Fläche mit Überschrift */
@Composable
private fun Panel(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(24.dp)).background(CyberPanelHigh).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** Runder Auswahl-Knopf ohne Rahmen: gewählt = Babyblau */
@Composable
private fun Pill(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Accent else CyberPanelHighest, label = "pill")
    Text(text, color = if (selected) Color(0xFF04202E) else MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag(tag))
}

@Composable
private fun FineTuneRow(label: String, value: Long, tag: String, onMinus: () -> Unit, onPlus: () -> Unit, onListen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(60.dp))
        androidx.compose.material3.FilledTonalIconButton(onClick = onMinus, modifier = Modifier.testTag("$tag-minus")) {
            Icon(Icons.Default.Remove, "$label 1 Sekunde früher")
        }
        Text(formatPrecise(value), Modifier.weight(1f).testTag("$tag-value"), fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.material3.FilledTonalIconButton(onClick = onPlus, modifier = Modifier.testTag("$tag-plus")) {
            Icon(Icons.Default.Add, "$label 1 Sekunde später")
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.FilledIconButton(onClick = onListen, modifier = Modifier.testTag("$tag-listen")) {
            Icon(Icons.Default.PlayArrow, "$label anhören")
        }
    }
}
