package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.Recap
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.PlayEvent
import com.backewatsch.mp3player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecapTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun t(id: String, title: String, artist: String, genre: String = "", album: String = "", added: Long = at(2025, 6, 1)) =
        Track(id = id, fileName = "$id.mp3", title = title, artist = artist, genre = genre, album = album, durationMs = 180_000, addedAt = added)

    private val a = t("a", "Nachtfahrt", "Luna", "Pop", "Neon")
    private val b = t("b", "Sonnenweg", "Luna", "Pop", "Neon", added = at(2026, 2, 1))
    private val c = t("c", "Bassline", "Kopfnicker", "Hip-Hop")

    private fun state(plays: List<PlayEvent>, listened: Map<String, Long> = emptyMap()) =
        LibraryState(tracks = listOf(a, b, c), plays = plays.sortedByDescending { it.at }, listenedByMonth = listened)

    @Test fun buildsTheYear() {
        val plays = listOf(
            PlayEvent("a", at(2026, 3, 1, 23)), PlayEvent("a", at(2026, 3, 2, 23)), PlayEvent("a", at(2026, 3, 3, 1)),
            PlayEvent("a", at(2026, 3, 3, 2)), PlayEvent("a", at(2026, 3, 3, 3)),
            PlayEvent("b", at(2026, 7, 10, 8)), PlayEvent("c", at(2026, 7, 11, 18)),
            PlayEvent("c", at(2025, 12, 31, 18)), // Vorjahr zählt nicht
            PlayEvent("gelöscht", at(2026, 5, 5)), // Lied gibt es nicht mehr
        )
        val r = Recap.build(state(plays, mapOf("2026-03" to 30 * 60_000L, "2026-07" to 10 * 60_000L, "2025-12" to 99 * 60_000L)), 2026, zone)
        assertEquals(7, r.plays)
        assertEquals(40 * 60_000L, r.listenedMs)
        assertEquals(listOf("Nachtfahrt", "Bassline", "Sonnenweg"), r.topTracks.map { it.first.title })
        assertEquals(5, r.topTracks.first().second)
        assertEquals("Luna" to 6, r.topArtists.first())
        assertEquals("Pop", r.topGenres.first().first)
        assertEquals("Neon" to 6, r.topAlbum)
        assertEquals(3, r.topMonth)
        assertEquals(Recap.Daypart.NIGHT, r.listenerType)
        assertEquals(3, r.longestStreak) // 1.–3. März
        assertEquals(5, r.daysListened)
        assertEquals("Nachtfahrt", r.firstTrack?.title)
        assertEquals(Recap.Obsession(a, 3, LocalDate.of(2026, 3, 3)), r.obsession)
        assertEquals(1, r.newTracks)
        assertEquals(3, r.distinctTracks)
        assertEquals(2, r.distinctArtists)
        assertEquals(DayOfWeek.TUESDAY, r.topWeekday) // 3. März 2026 ist ein Dienstag (3 Mal)
        assertTrue(r.hasData)
    }

    @Test fun estimatesListeningWithoutTimeData() {
        val r = Recap.build(state(listOf(PlayEvent("a", at(2026, 1, 5)), PlayEvent("b", at(2026, 1, 6)))), 2026, zone)
        assertEquals(2 * 180_000L, r.listenedMs)
        assertFalse(r.hasData)
        assertNull(r.obsession)
    }

    @Test fun picksTheRightYear() {
        val plays = (1..5).map { PlayEvent("a", at(2025, 11, it)) } + PlayEvent("b", at(2026, 1, 2))
        val s = state(plays)
        assertEquals(listOf(2025), Recap.availableYears(s, zone))
        // Im Januar noch das Vorjahr zeigen
        assertEquals(2025, Recap.defaultYear(s, at(2026, 1, 10), zone))
        val later = state(plays + (1..5).map { PlayEvent("c", at(2026, 4, it)) })
        assertEquals(2026, Recap.defaultYear(later, at(2026, 11, 9), zone))
        assertNull(Recap.defaultYear(state(emptyList()), at(2026, 11, 9), zone))
    }

    @Test fun formatsNumbers() {
        assertEquals("12.345", Recap.number(12_345))
        assertEquals(Recap.Daypart.MORNING, Recap.Daypart.of(7))
        assertEquals(Recap.Daypart.NIGHT, Recap.Daypart.of(3))
    }
}
