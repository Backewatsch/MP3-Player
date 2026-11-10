package com.backewatsch.mp3player.ui

import com.backewatsch.mp3player.data.*

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.backewatsch.mp3player.data.Stats
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.model.formatTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Hör-Statistik: Hörzeit, Top 10 diesen Monat, Top-Interpreten, Mediathek */
@Composable
fun StatsScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val lib by app.library.state.collectAsState()
    val stats = remember(lib) { Stats.summary(lib) }
    val recapYears = remember(lib) { com.backewatsch.mp3player.data.Recap.availableYears(lib) }
    val month = LocalDate.now().month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN)
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Statistik", onBack = { nav.pop() })
        LazyColumn(Modifier.fillMaxSize().testTag("statsList")) {
            items(recapYears, key = { "recap-$it" }) { year -> RecapBanner(year, Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp)) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Hörzeit im $month", Stats.formatListening(stats.listenedThisMonthMs), Modifier.weight(1f), "stat-month")
                    StatTile("Hörzeit gesamt", Stats.formatListening(stats.listenedTotalMs), Modifier.weight(1f), "stat-total")
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Gehört im $month", "${stats.playsThisMonth}×", Modifier.weight(1f), "stat-plays")
                    StatTile("Lieblingssongs", "${stats.favoriteCount}", Modifier.weight(1f), "stat-favs")
                }
            }

            item { SectionHeader("Top 10 im $month") }
            if (stats.topThisMonth.isEmpty()) {
                item { Hint("Noch nichts gehört in diesem Monat. Ein Lied zählt, wenn du es mindestens 30 Sekunden (oder zur Hälfte) hörst.") }
            } else {
                val max = stats.topThisMonth.first().second
                itemsIndexed(stats.topThisMonth, key = { _, p -> "tm-" + p.first.id }) { index, (track, count) ->
                    TopRow(index, track, count, max) { app.player.play(stats.topThisMonth.map { it.first }, index) }
                }
            }

            if (stats.topArtists.isNotEmpty()) {
                item { SectionHeader("Top-Interpreten") }
                val max = stats.topArtists.first().second
                itemsIndexed(stats.topArtists, key = { _, p -> "ta-" + p.first }) { index, (artist, count) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(LibRoute.Artist(artist)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(index + 1), color = NeonYellow, fontWeight = FontWeight.Bold, fontFamily = CyberMono,
                            modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Bar(count.toFloat() / max)
                        }
                        Text("${count}×", color = NeonYellow, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (stats.topAllTime.isNotEmpty()) {
                item { SectionHeader("Top 10 aller Zeiten") }
                val max = stats.topAllTime.first().second
                itemsIndexed(stats.topAllTime, key = { _, p -> "ta-" + p.first.id }) { index, (track, count) ->
                    TopRow(index, track, count, max) { app.player.play(stats.topAllTime.map { it.first }, index) }
                }
            }

            item { SectionHeader("Mediathek") }
            item {
                Card {
                    InfoLine("Lieder", "${stats.trackCount}")
                    InfoLine("Gesamtdauer", formatTime(stats.libraryDurationMs))
                    InfoLine("Playlists", "${lib.playlists.size}")
                    InfoLine("Gehörte Lieder insgesamt", "${stats.playsTotal}")
                }
            }
            item {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(12.dp)) {
                    Text("Statistik zurücksetzen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Statistik zurücksetzen?") },
            text = { Text("Hörzeit und Top-Listen werden gelöscht. Deine Lieder, Playlists und Herzen bleiben.") },
            confirmButton = { TextButton(onClick = { app.library.clearStats(); confirmClear = false }) { Text("Zurücksetzen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, tag: String) {
    Column(
        modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(CyberPanelHigh, CyberPanel)),
            androidx.compose.foundation.shape.CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).padding(14.dp).testTag(tag),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Accent, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopRow(index: Int, track: Track, count: Int, max: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(index + 1), color = NeonYellow, fontWeight = FontWeight.Bold, fontFamily = CyberMono, modifier = Modifier.width(32.dp))
        Artwork(track, 40.dp, 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Bar(count.toFloat() / max)
        }
        Text("${count}×", color = NeonYellow, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).background(CyberLine)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).fillMaxHeight().background(
            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Accent, NeonCyan))))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp))
}
