package com.backewatsch.mp3player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Songtext groß zum Mitlesen – aus der MP3 oder selbst eingetragen */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(track: Track, onDismiss: () -> Unit) {
    val app = LocalApp.current
    var lyrics by remember(track.id) { mutableStateOf<String?>(null) }
    var loaded by remember(track.id) { mutableStateOf(false) }
    var editing by remember(track.id) { mutableStateOf(false) }
    var draft by remember(track.id) { mutableStateOf("") }
    LaunchedEffect(track.id) {
        lyrics = withContext(Dispatchers.IO) { app.library.lyricsOf(track) }
        loaded = true
    }
    val color = rememberArtworkColor(track)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.75f), CyberBlack)))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Songtext", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                    Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
                if (!editing) {
                    IconButton(onClick = { draft = lyrics.orEmpty(); editing = true }, modifier = Modifier.testTag("lyricsEdit")) {
                        Icon(Icons.Default.Edit, "Songtext bearbeiten", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (editing) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("Songtext hier einfügen …") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).weight(1f).testTag("lyricsField"),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = false }) { Text("Abbrechen", color = Color.White) }
                    TextButton(onClick = {
                        app.library.setLyrics(track, draft)
                        lyrics = draft.trim().ifEmpty { null }
                        editing = false
                    }, modifier = Modifier.testTag("lyricsSave")) { Text("Sichern", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when {
                        !loaded -> Unit
                        lyrics == null -> Text(
                            "Für dieses Lied ist kein Songtext gespeichert.\n\nTippe oben auf den Stift und füge ihn ein – er bleibt dann gespeichert.",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp,
                            modifier = Modifier.testTag("lyricsEmpty"),
                        )
                        else -> Text(lyrics!!, color = Color.White, fontSize = 24.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("lyricsText"))
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}
