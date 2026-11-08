package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.Track
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Hör-Statistik (ohne Android-Abhängigkeiten, getestet). */
object Stats {
    const val MAX_PLAYS = 5000

    fun monthKey(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        YearMonth.from(Instant.ofEpochMilli(at).atZone(zone)).toString()

    data class Summary(
        val listenedTotalMs: Long,
        val listenedThisMonthMs: Long,
        val playsThisMonth: Int,
        val playsTotal: Int,
        val topThisMonth: List<Pair<Track, Int>>,
        val topAllTime: List<Pair<Track, Int>>,
        val topArtists: List<Pair<String, Int>>,
        val trackCount: Int,
        val libraryDurationMs: Long,
        val favoriteCount: Int,
    )

    fun summary(state: LibraryState, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Summary {
        val month = monthKey(now, zone)
        val byId = state.tracks.associateBy { it.id }
        val plays = state.plays.filter { it.trackId in byId }
        val monthPlays = plays.filter { monthKey(it.at, zone) == month }
        fun top(events: List<com.backewatsch.mp3player.model.PlayEvent>) = events.groupingBy { it.trackId }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { byId[it.key]?.title?.lowercase() })
            .take(10).map { byId.getValue(it.key) to it.value }
        val artists = plays.mapNotNull { byId[it.trackId]?.artist?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(5).map { it.key to it.value }
        return Summary(
            listenedTotalMs = state.listenedByMonth.values.sum(),
            listenedThisMonthMs = state.listenedByMonth[month] ?: 0,
            playsThisMonth = monthPlays.size,
            playsTotal = plays.size,
            topThisMonth = top(monthPlays),
            topAllTime = top(plays),
            topArtists = artists,
            trackCount = state.tracks.size,
            libraryDurationMs = state.tracks.sumOf { it.durationMs },
            favoriteCount = state.favorites.count { it in byId },
        )
    }

    /** „12 Std. 5 Min.“ bzw. „7 Min.“ */
    fun formatListening(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h Std. $m Min." else "$m Min."
    }
}
