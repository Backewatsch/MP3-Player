package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.Stats
import com.backewatsch.mp3player.data.TrackSort
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.PlayEvent
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.player.SoundPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Sortieren, Statistik, Equalizer-Kurven und Ein-/Ausblenden – ohne Gerät */
class FeatureLogicTest {
    private val a = Track(id = "a", fileName = "a.mp3", title = "beta", artist = "Zed", durationMs = 200_000, addedAt = 1)
    private val b = Track(id = "b", fileName = "b.mp3", title = "Alpha", artist = "", durationMs = 300_000, addedAt = 3)
    private val c = Track(id = "c", fileName = "c.mp3", title = "gamma", artist = "adam", durationMs = 100_000, addedAt = 2)
    private val all = listOf(a, b, c)

    @Test fun sortsTracks() {
        assertEquals(listOf("b", "a", "c"), TrackSort.TITLE.apply(all).map { it.id })
        assertEquals(listOf("c", "a", "b"), TrackSort.ARTIST.apply(all).map { it.id }) // ohne Interpret am Ende
        assertEquals(listOf("b", "c", "a"), TrackSort.NEWEST.apply(all).map { it.id })
        assertEquals(listOf("c", "a", "b"), TrackSort.MOST_PLAYED.apply(all, mapOf("c" to 5, "a" to 2)).map { it.id })
        assertEquals(listOf("b", "a", "c"), TrackSort.LONGEST.apply(all).map { it.id })
        assertEquals(TrackSort.TITLE, TrackSort.fromName("Quatsch"))
        assertEquals(TrackSort.NEWEST, TrackSort.fromName("NEWEST"))
    }

    @Test fun computesStats() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val lastMonth = ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val state = LibraryState(
            tracks = all,
            favorites = listOf("a", "gelöscht"),
            plays = listOf(PlayEvent("a", now - 1000), PlayEvent("c", now - 2000), PlayEvent("a", now - 3000),
                PlayEvent("c", lastMonth), PlayEvent("c", lastMonth + 1), PlayEvent("gelöscht", now)),
            listenedByMonth = mapOf("2026-11" to 3_600_000L + 5 * 60_000L, "2026-10" to 60_000L),
        )
        val s = Stats.summary(state, now, zone)
        assertEquals(3, s.playsThisMonth)
        assertEquals(5, s.playsTotal)
        assertEquals(listOf("a" to 2, "c" to 1), s.topThisMonth.map { it.first.id to it.second })
        assertEquals(listOf("c" to 3, "a" to 2), s.topAllTime.map { it.first.id to it.second })
        assertEquals(listOf("adam" to 3, "Zed" to 2), s.topArtists)
        assertEquals(3_900_000L, s.listenedThisMonthMs)
        assertEquals(3_960_000L, s.listenedTotalMs)
        assertEquals(1, s.favoriteCount)
        assertEquals(600_000L, s.libraryDurationMs)
        assertEquals("1 Std. 5 Min.", Stats.formatListening(3_900_000L))
        assertEquals("0 Min.", Stats.formatListening(0))
        assertEquals("2026-11", Stats.monthKey(now, zone))
    }

    @Test fun equalizerCurves() {
        val bass = SoundPresets.byName("Bass-Boost")!!
        assertEquals(6f, SoundPresets.gainAt(bass, 30), 0.001f)        // unterhalb des ersten Punkts
        assertEquals(6f, SoundPresets.gainAt(bass, 60), 0.001f)
        assertEquals(0f, SoundPresets.gainAt(bass, 5_000), 0.001f)
        val mid = SoundPresets.gainAt(bass, 95)                           // zwischen 60 und 150 Hz
        assertTrue(mid in 4.5f..6f)
        // Typische 5-Band-Mitten eines Handys, Bereich ±15 dB
        val levels = SoundPresets.levelsFor(bass, listOf(60, 230, 910, 3600, 14000), -1500, 1500)
        assertEquals(600, levels[0])
        assertEquals(0, levels[3])
        // Begrenzung auf den Bereich des Geräts
        assertEquals(listOf(300, 300), SoundPresets.levelsFor(bass, listOf(60, 60), -300, 300))
        assertTrue(SoundPresets.all.map { it.name }.containsAll(listOf("Normal", "Pop", "Rock", "Hip-Hop", "Klassik")))
    }

    @Test fun fadesInAndOut() {
        // Aus: immer volle Lautstärke
        assertEquals(1f, SoundPresets.volume(0, 200_000, 0, null), 0f)
        // 4 s Ein-/Ausblenden
        assertEquals(0f, SoundPresets.volume(0, 200_000, 4_000, null), 0.001f)
        assertEquals(0.5f, SoundPresets.volume(2_000, 200_000, 4_000, null), 0.001f)
        assertEquals(1f, SoundPresets.volume(100_000, 200_000, 4_000, null), 0f)
        assertEquals(0.25f, SoundPresets.volume(199_000, 200_000, 4_000, null), 0.001f)
        // Zu kurzes Lied: kein Ausblenden
        assertEquals(1f, SoundPresets.volume(0, 10_000, 4_000, null), 0f)
        // Schlafmodus wirkt zusätzlich
        assertEquals(0.5f, SoundPresets.volume(100_000, 200_000, 4_000, 5_000), 0.001f)
    }
}
