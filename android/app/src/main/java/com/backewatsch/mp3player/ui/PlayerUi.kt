package com.backewatsch.mp3player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.backewatsch.mp3player.model.formatTime

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val app = LocalApp.current
    val state by app.player.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val track = lib.tracks.firstOrNull { it.id == state.currentId } ?: return
    val color = rememberArtworkColor(track)
    val haptic = LocalHapticFeedback.current
    var dragX by remember { mutableFloatStateOf(0f) }
    val shown by animateFloatAsState(dragX, label = "miniDrag")
    val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.85f))))
            // Wischen: links = nächster Titel, rechts = vorheriger
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -140f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.player.next() }
                        else if (dragX > 140f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.player.previous() }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { _, delta -> dragX = (dragX + delta).coerceIn(-300f, 300f) }
            }
            .clickable(onClick = onOpen)
            .testTag("miniPlayer"),
    ) {
        Row(
            Modifier.fillMaxWidth().offset { IntOffset((shown * 0.35f).toInt(), 0) }.padding(start = 10.dp, end = 6.dp, top = 9.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, 44.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White,
                    style = MaterialTheme.typography.bodyMedium)
                Text(track.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Läuft der Schlafmodus, zeigt der Mini-Player die Restzeit
            rememberSleepRemaining()?.let { remaining ->
                Icon(Icons.Default.Bedtime, "Schlafmodus läuft", tint = Color.White, modifier = Modifier.size(16.dp))
                Text(" " + com.backewatsch.mp3player.player.SleepTimer.formatRemaining(remaining),
                    style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.testTag("miniSleep"))
            }
            val favorite = track.id in lib.favorites
            IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.library.toggleFavorite(track) }) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (favorite) "Aus Lieblingssongs entfernen" else "Zu Lieblingssongs", tint = if (favorite) Accent else Color.White)
            }
            IconButton(onClick = app.player::togglePlayPause, modifier = Modifier.testTag("miniPlayPause")) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pause" else "Abspielen",
                    tint = Color.White, modifier = Modifier.size(30.dp),
                )
            }
        }
        // Fortschritt als feine Linie unten
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp).fillMaxWidth().height(3.dp)
            .clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NowPlayingSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val state by app.player.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val track = lib.tracks.firstOrNull { it.id == state.currentId }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val color = rememberArtworkColor(track)
    val animatedColor by animateColorAsState(color, tween(600), label = "playerColor")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CyberBlack,
        dragHandle = null,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(animatedColor, animatedColor.copy(alpha = 0.55f), CyberBlack))),
        ) {
            if (track == null) {
                EmptyHint("Nichts wird abgespielt", "Wähle einen Titel in der Mediathek.")
                return@Box
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, "Schließen", tint = Color.White, modifier = Modifier.size(32.dp)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WIRD GESPIELT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), letterSpacing = 1.5.sp)
                        Text(track.album.ifEmpty { "ILLEGAL FREEDOM MUSIC" }, style = MaterialTheme.typography.labelLarge, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { nav.actions.open(track) }) { Icon(Icons.Default.MoreVert, "Optionen", tint = Color.White) }
                }
                Spacer(Modifier.weight(1f))

                // Großes Cover: wischen wechselt den Titel, bei Pause etwas kleiner
                var artDrag by remember { mutableFloatStateOf(0f) }
                val artScale by animateFloatAsState(if (state.isPlaying) 1f else 0.88f, spring(dampingRatio = 0.6f), label = "artScale")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer { scaleX = artScale; scaleY = artScale; translationX = artDrag * 0.4f }
                        .shadow(28.dp, RoundedCornerShape(28.dp))
                        .pointerInput(track.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (artDrag < -160f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.player.next() }
                                    else if (artDrag > 160f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.player.previous() }
                                    artDrag = 0f
                                },
                                onDragCancel = { artDrag = 0f },
                            ) { _, d -> artDrag += d }
                        },
                ) {
                    BoxWithConstraints { Artwork(track, maxWidth, 28.dp) }
                }
                Spacer(Modifier.weight(1f))

                // Titel + Herz
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
                        Text(track.artist.ifEmpty { "Unbekannter Interpret" }, style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = track.artist.isNotBlank()) { onDismiss(); nav.push(LibRoute.Artist(track.artist)) })
                    }
                    val favorite = track.id in lib.favorites
                    val heartScale by animateFloatAsState(if (favorite) 1.15f else 1f, spring(dampingRatio = 0.35f, stiffness = 400f), label = "heart")
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.library.toggleFavorite(track) },
                        modifier = Modifier.testTag("favButton")) {
                        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (favorite) "Aus Lieblingssongs entfernen" else "Zu Lieblingssongs",
                            tint = if (favorite) Accent else Color.White, modifier = Modifier.size(28.dp).scale(heartScale))
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Fortschritt
                val duration = state.durationMs.coerceAtLeast(1)
                Slider(
                    value = if (scrubbing) scrubValue else state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { scrubbing = true; scrubValue = it },
                    onValueChangeFinished = { app.player.seekTo(scrubValue.toLong()); scrubbing = false },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                )
                Row(Modifier.fillMaxWidth()) {
                    val pos = if (scrubbing) scrubValue.toLong() else state.positionMs
                    Text(formatTime(pos), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    Text("-" + formatTime((state.durationMs - pos).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.height(8.dp))

                // Steuerung
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = app.player::toggleShuffle) {
                        Icon(Icons.Default.Shuffle, if (state.shuffle) "Zufallswiedergabe an" else "Zufallswiedergabe aus",
                            tint = if (state.shuffle) Accent else Color.White.copy(alpha = 0.8f))
                    }
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.player.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.SkipPrevious, "Vorheriger Titel", Modifier.size(40.dp), tint = Color.White)
                    }
                    RoundPlayButton(playing = state.isPlaying, size = 80.dp, modifier = Modifier.testTag("bigPlayPause"), onClick = app.player::togglePlayPause)
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); app.player.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.SkipNext, "Nächster Titel", Modifier.size(40.dp), tint = Color.White)
                    }
                    IconButton(onClick = app.player::cycleRepeat) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Wiederholen",
                            tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.8f) else Accent,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Extras: Songtext, Schlafmodus, Klang, Wiedergabeliste
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showLyrics = true }, modifier = Modifier.testTag("lyricsButton")) {
                        Icon(Icons.Default.Lyrics, "Songtext", tint = Color.White)
                    }
                    CompositionLocalProvider(LocalContentColor provides Color.White) { SleepTimerButton() }
                    IconButton(onClick = { nav.showSound = true }, modifier = Modifier.testTag("soundButton")) {
                        Icon(Icons.Default.Equalizer, "Klang / Equalizer", tint = Color.White)
                    }
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Wiedergabeliste", tint = Color.White) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showLyrics && track != null) LyricsSheet(track, onDismiss = { showLyrics = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val state by app.player.state.collectAsState()
    val lib by app.library.state.collectAsState()
    val byId = lib.tracks.associateBy { it.id }
    val current = state.currentIndex
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Wiedergabeliste", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        Text(if (state.shuffle) "Zufallswiedergabe an" else "In dieser Reihenfolge", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().testTag("queueList")) {
            itemsIndexed(state.queueIds) { index, id ->
                val track = byId[id] ?: return@itemsIndexed
                when (index) {
                    current -> QueueHeader("Läuft gerade")
                    current + 1 -> QueueHeader("Als Nächstes")
                }
                if (index < current) return@itemsIndexed // bereits gespielt → ausblenden
                Row(
                    Modifier.fillMaxWidth().clickable { app.player.playQueueIndex(index) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        .semantics { contentDescription = "Wiedergabeliste ${index - current}: ${track.title}" }
                        .testTag("queue-${index - current}-${track.title}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track, 40.dp, 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = if (index == current) Accent else Color.Unspecified, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (track.artist.isNotEmpty()) Text(track.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (index > current) {
                        IconButton(onClick = { app.player.moveInQueue(index, index - 1) }, enabled = index > current + 1) {
                            Icon(Icons.Default.KeyboardArrowUp, "Nach oben")
                        }
                        IconButton(onClick = { app.player.moveInQueue(index, index + 1) }, enabled = index < state.queueIds.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, "Nach unten")
                        }
                        IconButton(onClick = { app.player.removeFromQueue(index) }) { Icon(Icons.Default.Close, "Entfernen") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}
