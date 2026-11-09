package com.backewatsch.mp3player.data

import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.PlayEvent
import com.backewatsch.mp3player.model.Track
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Jahresrückblick „Dein Jahr in Musik“ – reine Auswertung der Hördaten (ohne Android, getestet). */
object Recap {
    /** Ab so vielen gehörten Liedern lohnt sich ein Rückblick */
    const val MIN_PLAYS = 3

    enum class Daypart(val label: String, val type: String, val text: String) {
        MORNING("Morgens", "Frühaufsteher", "Du startest am liebsten mit Musik in den Tag."),
        DAY("Tagsüber", "Tagträumer", "Deine Musik begleitet dich durch den Tag."),
        EVENING("Abends", "Feierabend-Hörer", "Wenn der Tag vorbei ist, geht bei dir die Musik an."),
        NIGHT("Nachts", "Nachteule", "Wenn andere schlafen, drehst du auf.");

        companion object {
            fun of(hour: Int) = when (hour) {
                in 5..10 -> MORNING
                in 11..16 -> DAY
                in 17..21 -> EVENING
                else -> NIGHT
            }
        }
    }

    data class Obsession(val track: Track, val count: Int, val day: LocalDate)

    data class Result(
        val year: Int,
        val listenedMs: Long,
        val plays: Int,
        val distinctTracks: Int,
        val distinctArtists: Int,
        val topTracks: List<Pair<Track, Int>>,
        val topArtists: List<Pair<String, Int>>,
        val topGenres: List<Pair<String, Int>>,
        val topAlbum: Pair<String, Int>?,
        /** Hörzeit je Monat (Index 0 = Januar) */
        val minutesByMonth: List<Long>,
        val playsByMonth: List<Int>,
        /** Stärkster Monat (1–12) */
        val topMonth: Int?,
        val dayparts: Map<Daypart, Int>,
        val listenerType: Daypart?,
        val topWeekday: DayOfWeek?,
        val daysListened: Int,
        val longestStreak: Int,
        val firstTrack: Track?,
        val firstAt: Long?,
        val obsession: Obsession?,
        val newTracks: Int,
    ) {
        val hasData get() = plays >= MIN_PLAYS
    }

    private fun yearOf(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).year

    /** Jahre mit Hördaten, neuestes zuerst */
    fun availableYears(state: LibraryState, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
        val known = state.tracks.map { it.id }.toSet()
        return state.plays.filter { it.trackId in known }.groupingBy { yearOf(it.at, zone) }.eachCount()
            .filter { it.value >= MIN_PLAYS }.keys.sortedDescending()
    }

    /** Welches Jahr zeigen? Im Januar noch das Vorjahr, solange das neue Jahr kaum Daten hat */
    fun defaultYear(state: LibraryState, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int? {
        val years = availableYears(state, zone)
        val date = Instant.ofEpochMilli(now).atZone(zone)
        val current = date.year
        if (date.monthValue == 1 && (current - 1) in years) {
            val thisYear = state.plays.count { yearOf(it.at, zone) == current }
            if (thisYear < 50) return current - 1
        }
        return years.firstOrNull { it == current } ?: years.firstOrNull()
    }

    fun build(state: LibraryState, year: Int, zone: ZoneId = ZoneId.systemDefault()): Result {
        val byId = state.tracks.associateBy { it.id }
        val plays: List<Pair<PlayEvent, ZonedDateTime>> = state.plays
            .filter { it.trackId in byId }
            .map { it to Instant.ofEpochMilli(it.at).atZone(zone) }
            .filter { it.second.year == year }
            .sortedBy { it.first.at }
        val tracks = plays.map { byId.getValue(it.first.trackId) }

        fun <K> ranked(keys: List<K>, limit: Int, name: (K) -> String): List<Pair<K, Int>> =
            keys.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<K, Int>> { it.value }.thenBy { name(it.key).lowercase() })
                .take(limit).map { it.key to it.value }

        val topTracks = ranked(tracks.map { it.id }, 5) { byId[it]?.title.orEmpty() }.map { byId.getValue(it.first) to it.second }
        val topArtists = ranked(tracks.mapNotNull { it.artist.trim().takeIf(String::isNotEmpty) }, 5) { it }
        val topGenres = ranked(tracks.mapNotNull { it.genre.trim().takeIf(String::isNotEmpty) }, 3) { it }
        val topAlbum = ranked(tracks.mapNotNull { it.album.trim().takeIf(String::isNotEmpty) }, 1) { it }.firstOrNull()

        val minutesByMonth = (1..12).map { m -> (state.listenedByMonth["%04d-%02d".format(year, m)] ?: 0L) / 60_000 }
        val playsByMonth = (1..12).map { m -> plays.count { it.second.monthValue == m } }
        var listened = state.listenedByMonth.filterKeys { it.startsWith("$year-") }.values.sum()
        // Ältere Daten ohne Hörzeit: aus den Liedlängen schätzen
        if (listened == 0L) listened = tracks.sumOf { it.durationMs }
        val topMonth = when {
            minutesByMonth.any { it > 0 } -> minutesByMonth.indices.maxBy { minutesByMonth[it] } + 1
            playsByMonth.any { it > 0 } -> playsByMonth.indices.maxBy { playsByMonth[it] } + 1
            else -> null
        }

        val dayparts = plays.groupingBy { Daypart.of(it.second.hour) }.eachCount()
        val listenerType = dayparts.maxByOrNull { it.value }?.key
        val topWeekday = plays.groupingBy { it.second.dayOfWeek }.eachCount().maxByOrNull { it.value }?.key

        val days = plays.map { it.second.toLocalDate() }.distinct().sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (d in days) {
            run = if (previous != null && previous.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            previous = d
        }

        // Ein Lied an einem einzigen Tag in Dauerschleife
        val obsession = plays.groupingBy { it.first.trackId to it.second.toLocalDate() }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Pair<String, LocalDate>, Int>> { it.value }.thenBy { it.key.second })
            ?.takeIf { it.value >= 3 }
            ?.let { Obsession(byId.getValue(it.key.first), it.value, it.key.second) }

        val newTracks = state.tracks.count { yearOf(it.addedAt, zone) == year }

        return Result(
            year = year,
            listenedMs = listened,
            plays = plays.size,
            distinctTracks = tracks.map { it.id }.distinct().size,
            distinctArtists = tracks.mapNotNull { it.artist.trim().lowercase().takeIf(String::isNotEmpty) }.distinct().size,
            topTracks = topTracks,
            topArtists = topArtists,
            topGenres = topGenres,
            topAlbum = topAlbum,
            minutesByMonth = minutesByMonth,
            playsByMonth = playsByMonth,
            topMonth = topMonth,
            dayparts = dayparts,
            listenerType = listenerType,
            topWeekday = topWeekday,
            daysListened = days.size,
            longestStreak = longest,
            firstTrack = plays.firstOrNull()?.let { byId.getValue(it.first.trackId) },
            firstAt = plays.firstOrNull()?.first?.at,
            obsession = obsession,
            newTracks = newTracks,
        )
    }

    val MONTHS = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    fun weekdayName(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "Montag"; DayOfWeek.TUESDAY -> "Dienstag"; DayOfWeek.WEDNESDAY -> "Mittwoch"
        DayOfWeek.THURSDAY -> "Donnerstag"; DayOfWeek.FRIDAY -> "Freitag"; DayOfWeek.SATURDAY -> "Samstag"
        DayOfWeek.SUNDAY -> "Sonntag"
    }

    /** 12345 → „12.345“ */
    fun number(n: Long): String = "%,d".format(java.util.Locale.GERMANY, n)
}
