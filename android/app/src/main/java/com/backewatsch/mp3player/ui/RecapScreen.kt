package com.backewatsch.mp3player.ui

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.backewatsch.mp3player.data.Recap
import com.backewatsch.mp3player.data.Stats
import kotlinx.coroutines.launch
import java.io.File

private const val SLIDE_MS = 7_000

private class Slide(val name: String, val colors: List<Color>, val content: @Composable () -> Unit)

/** „Dein Jahr in Musik“: Rückblick als Folge von Bildschirmen zum Durchtippen */
@Composable
fun RecapStory(year: Int, onClose: () -> Unit) {
    val app = LocalApp.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val lib by app.library.state.collectAsState()
        // Bildebene der Zusammenfassung – daraus wird das Bild zum Teilen
        val shareLayer = rememberGraphicsLayer()
        val recap = remember(year) { Recap.build(lib, year) }
        val slides = remember(recap) { slidesFor(recap, shareLayer) }
        var index by remember { mutableIntStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        var shownIndex by remember { mutableIntStateOf(-1) }
        val progress = remember { Animatable(0f) }

        LaunchedEffect(index, paused) {
            if (shownIndex != index) { progress.snapTo(0f); shownIndex = index }
            if (paused || index == slides.lastIndex) return@LaunchedEffect
            progress.animateTo(1f, tween(((1f - progress.value) * SLIDE_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
            if (index < slides.lastIndex) index++
        }

        val slide = slides[index]
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(slide.colors))
                .pointerInput(slides.size) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { offset ->
                            if (offset.x < size.width / 3f) { if (index > 0) index-- }
                            else if (index < slides.lastIndex) index++
                        },
                    )
                }
                .testTag("recapStory"),
        ) {
            // Inhalt mit kurzem Einblenden
            key(index) {
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(650)) }
                Box(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp, 64.dp, 24.dp, 24.dp)
                        .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 80f }
                        .testTag("recap-${slide.name}"),
                    contentAlignment = Alignment.Center,
                ) { slide.content() }
            }
            // Fortschritt oben
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp, 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slides.indices.forEach { i ->
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                            val f = when { i < index -> 1f; i == index -> if (index == slides.lastIndex) 1f else progress.value; else -> 0f }
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DEIN JAHR $year", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 8.dp))
                    IconButton(onClick = onClose, modifier = Modifier.testTag("recapClose")) { Icon(Icons.Default.Close, "Schließen", tint = Color.White) }
                }
            }
            if (index == slides.lastIndex) {
                SummaryActions(recap, shareLayer, onReplay = { index = 0 }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

// region Folien

private val Palette = listOf(
    listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
    listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
    listOf(Color(0xFF134E5E), Color(0xFF71B280)),
    listOf(Color(0xFF1F1C2C), Color(0xFF928DAB)),
    listOf(Color(0xFFB24592), Color(0xFFF15F79)),
    listOf(Color(0xFF0B486B), Color(0xFFF56217)),
    listOf(Color(0xFF16222A), Color(0xFF3A6073)),
    listOf(Color(0xFF41295A), Color(0xFF2F0743)),
    listOf(Color(0xFF1D976C), Color(0xFF0B3D2E)),
    listOf(Color(0xFF283C86), Color(0xFF45A247)),
    listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)),
)

private fun slidesFor(r: Recap.Result, shareLayer: GraphicsLayer): List<Slide> {
    var c = 0
    fun colors() = Palette[c++ % Palette.size]
    val minutes = r.listenedMs / 60_000
    return buildList {
        add(Slide("intro", colors()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Big("${r.year}")
                Spacer(Modifier.height(8.dp))
                Headline("Dein Jahr in Musik")
                Spacer(Modifier.height(16.dp))
                Body("Tippe dich durch – rechts geht's weiter, links zurück. Halten pausiert.")
            }
        })
        add(Slide("minutes", colors()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Body("Du hast dieses Jahr")
                Big(Recap.number(minutes))
                Headline("Minuten Musik gehört")
                Spacer(Modifier.height(16.dp))
                val hours = minutes / 60
                Body(when {
                    minutes >= 1440 -> "Das sind ${"%.1f".format(java.util.Locale.GERMANY, minutes / 1440.0)} Tage am Stück."
                    hours >= 2 -> "Das sind $hours Stunden."
                    else -> "Das ist erst der Anfang!"
                } + "\n${Recap.number(r.plays.toLong())} Lieder an ${r.daysListened} Tagen.")
            }
        })
        r.firstTrack?.let { first ->
            add(Slide("first", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Body("Damit ging's los")
                    Spacer(Modifier.height(16.dp))
                    Artwork(first, 180.dp, 12.dp)
                    Spacer(Modifier.height(16.dp))
                    Headline(first.title)
                    Body(first.artist.ifBlank { "Unbekannter Interpret" })
                    r.firstAt?.let { Spacer(Modifier.height(8.dp)); Body("Dein erstes Lied am ${dateText(it)}") }
                }
            })
        }
        r.topTracks.firstOrNull()?.let { (track, count) ->
            add(Slide("topSong", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Body("Dein Song des Jahres")
                    Spacer(Modifier.height(16.dp))
                    Artwork(track, 230.dp, 14.dp)
                    Spacer(Modifier.height(18.dp))
                    Headline(track.title)
                    Body(track.artist.ifBlank { "Unbekannter Interpret" })
                    Spacer(Modifier.height(10.dp))
                    Body("$count Mal gehört", bold = true)
                }
            })
        }
        if (r.topTracks.size > 1) add(Slide("topSongs", colors()) {
            Column(Modifier.fillMaxWidth()) {
                Headline("Deine Top-Songs", center = false)
                Spacer(Modifier.height(18.dp))
                r.topTracks.forEachIndexed { i, (track, count) ->
                    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(36.dp))
                        Artwork(track, 54.dp, 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist.ifBlank { "Unbekannt" } + " · $count×", color = Color.White.copy(alpha = 0.75f),
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        })
        r.topArtists.firstOrNull()?.let { (artist, count) ->
            add(Slide("topArtist", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Body("Dein Interpret des Jahres")
                    Spacer(Modifier.height(12.dp))
                    Big(artist, size = 44)
                    Body("$count Lieder gehört", bold = true)
                    if (r.topArtists.size > 1) {
                        Spacer(Modifier.height(24.dp))
                        r.topArtists.drop(1).forEachIndexed { i, (name, n) -> Body("${i + 2}. $name · $n×") }
                    }
                }
            })
        }
        r.topGenres.firstOrNull()?.let { (genre, _) ->
            add(Slide("genre", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Body("Dein Sound")
                    Big(genre, size = 48)
                    if (r.topGenres.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Body("gefolgt von " + r.topGenres.drop(1).joinToString(" und ") { it.first })
                    }
                }
            })
        }
        r.obsession?.let { o ->
            add(Slide("loop", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Headline("Dauerschleife!")
                    Spacer(Modifier.height(16.dp))
                    Artwork(o.track, 150.dp, 12.dp)
                    Spacer(Modifier.height(16.dp))
                    Body("Am ${o.day.dayOfMonth}. ${Recap.MONTHS[o.day.monthValue - 1]} lief „${o.track.title}“")
                    Big("${o.count}×", size = 56)
                    Body("an einem einzigen Tag.")
                }
            })
        }
        r.listenerType?.let { type ->
            add(Slide("type", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Body("Du bist " + if (type == Recap.Daypart.NIGHT) "eine" else "ein")
                    Big(type.type, size = 42)
                    Body(type.text)
                    Spacer(Modifier.height(24.dp))
                    val max = (r.dayparts.values.maxOrNull() ?: 1).coerceAtLeast(1)
                    Recap.Daypart.entries.forEach { part ->
                        val n = r.dayparts[part] ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(part.label, color = Color.White, modifier = Modifier.width(90.dp))
                            Box(Modifier.weight(1f).height(14.dp)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((n.toFloat() / max).coerceAtLeast(0.02f))
                                    .clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = if (part == type) 1f else 0.45f)))
                            }
                        }
                    }
                }
            })
        }
        r.topMonth?.let { month ->
            add(Slide("months", colors()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Body("Dein stärkster Monat")
                    Big(Recap.MONTHS[month - 1], size = 44)
                    Spacer(Modifier.height(24.dp))
                    val useMinutes = r.minutesByMonth.any { it > 0 }
                    val values = if (useMinutes) r.minutesByMonth.map { it.toFloat() } else r.playsByMonth.map { it.toFloat() }
                    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
                    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        values.forEachIndexed { i, v ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height((140 * (v / max)).coerceAtLeast(2f).dp).clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = if (i == month - 1) 1f else 0.45f)))
                                Text(Recap.MONTHS[i].take(1), color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            })
        }
        add(Slide("facts", colors()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Headline("Noch mehr von dir")
                Fact(Recap.number(r.distinctTracks.toLong()), "verschiedene Lieder von ${r.distinctArtists} Interpreten")
                if (r.longestStreak > 1) Fact("${r.longestStreak} Tage", "am Stück Musik gehört – deine längste Serie")
                if (r.newTracks > 0) Fact("${r.newTracks}", "neue Lieder in deiner Mediathek")
                r.topWeekday?.let { Fact(Recap.weekdayName(it), "ist dein Musik-Tag") }
                r.topAlbum?.let { Fact(it.first, "ist dein Album des Jahres") }
            }
        })
        add(Slide("summary", listOf(Color(0xFF06080B), Color(0xFF1B3A4B))) {
            SummaryCard(r, shareLayer, Modifier.padding(bottom = 96.dp))
        })
    }
}

@Composable private fun Big(text: String, size: Int = 72) =
    Text(text, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
        lineHeight = (size * 1.05).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)

@Composable private fun Headline(text: String, center: Boolean = true) =
    Text(text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp,
        textAlign = if (center) TextAlign.Center else TextAlign.Start, maxLines = 3, overflow = TextOverflow.Ellipsis)

@Composable private fun Body(text: String, bold: Boolean = false) =
    Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 17.sp, textAlign = TextAlign.Center,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable private fun Fact(big: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Body(text)
    }
}

private fun dateText(at: Long): String {
    val d = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    return "${d.dayOfMonth}. ${Recap.MONTHS[d.monthValue - 1]}"
}

// endregion

// region Zusammenfassung (teilbar)

/** Zum Teilen wird die Karte gleichzeitig in [layer] gezeichnet */
@Composable
private fun SummaryCard(r: Recap.Result, layer: GraphicsLayer, modifier: Modifier = Modifier) {
    Column(
        modifier
            .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2C5364), Color(0xFF3A1C71), Color(0xFFD76D77))))
            .padding(22.dp)
            .fillMaxWidth()
            .testTag("recapSummaryCard"),
    ) {
        Text("MEIN JAHR ${r.year} IN MUSIK", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontSize = 15.sp)
        r.topTracks.firstOrNull()?.let { (t, _) ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(t, 84.dp, 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Song des Jahres", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    Text(t.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("Top-Songs", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                r.topTracks.forEachIndexed { i, (t, _) ->
                    Text("${i + 1}. ${t.title}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Top-Interpreten", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                r.topArtists.take(5).forEachIndexed { i, (a, _) ->
                    Text("${i + 1}. $a", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("Hörzeit", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                Text(Recap.number(r.listenedMs / 60_000) + " Min.", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(if (r.topGenres.isNotEmpty()) "Sound" else "Hörer-Typ", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                Text(r.topGenres.firstOrNull()?.first ?: r.listenerType?.type ?: "–", color = Color.White,
                    fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("ILLEGAL FREEDOM MUSIC", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun SummaryActions(r: Recap.Result, layer: GraphicsLayer, onReplay: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onReplay, modifier = Modifier.weight(1f).testTag("recapReplay")) {
            Icon(Icons.Default.Replay, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Nochmal", color = Color.White)
        }
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        val image = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                        val dir = File(context.cacheDir, "recap").apply { mkdirs() }
                        val file = File(dir, "Mein-Jahr-${r.year}.png")
                        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_TEXT, "Mein Jahr ${r.year} in Musik 🎧")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Rückblick teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Toast.makeText(context, "Teilen fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.weight(1f).testTag("recapShare"),
        ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Teilen") }
    }
}

// endregion

/** Auffällige Karte „Dein Jahr in Musik“ (Startseite, Statistik) */
@Composable
fun RecapBanner(year: Int, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B))))
            .clickable { nav.recapYear = year }
            .padding(16.dp)
            .testTag("recapBanner-$year"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("DEIN JAHR $year", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 13.sp)
            Text("Dein Rückblick in Musik ist da", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Top-Songs, Hörzeit, dein Hörer-Typ und mehr", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, "Ansehen", tint = Color.Black)
        }
    }
}

