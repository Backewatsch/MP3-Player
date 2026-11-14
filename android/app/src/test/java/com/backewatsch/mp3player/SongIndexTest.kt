package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.SongIndex
import com.backewatsch.mp3player.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** „Ist dieses Lied schon da?“ beim Importieren von einem anderen Handy – läuft ohne Gerät */
class SongIndexTest {
    private fun track(id: String, title: String, artist: String, ms: Long, source: String = "", fileName: String = "$title.mp3") =
        Track(id = id, fileName = fileName, title = title, artist = artist, durationMs = ms, source = source)

    @Test fun recognisesTheSameSong() {
        val index = SongIndex(listOf(track("a", "Sommernacht", "Bella", 180_000, source = "/storage/emulated/0/Music/sommer.mp3")))
        // gleiche Kennung
        assertTrue(index.contains(track("a", "Ganz anders", "Wer anders", 5_000)))
        assertTrue(index.hasId("a"))
        // gleiche Herkunft
        assertTrue(index.contains(track("x", "Anders", "Anders", 1_000, source = "/storage/emulated/0/Music/sommer.mp3")))
        // gleicher Titel und Interpret (Groß-/Kleinschreibung egal), fast gleiche Länge
        assertTrue(index.contains(track("y", "SOMMERNACHT", "bella", 181_500)))
    }

    @Test fun keepsDifferentSongsApart() {
        val index = SongIndex(listOf(track("a", "Sommernacht", "Bella", 180_000)))
        // andere Fassung: deutlich andere Länge
        assertFalse(index.contains(track("b", "Sommernacht", "Bella", 240_000)))
        // anderer Interpret
        assertFalse(index.contains(track("c", "Sommernacht", "Band", 180_000)))
        // Der Dateiname allein zählt nicht
        assertFalse(index.contains(track("d", "Intro", "Wer", 60_000, fileName = "Sommernacht.mp3")))
        assertFalse(index.hasId("b"))
    }

    @Test fun learnsSongsAddedLater() {
        val index = SongIndex()
        val song = track("a", "Regen", "Band", 200_000)
        assertFalse(index.contains(song))
        index.add(song)
        assertTrue(index.contains(track("z", "regen", "BAND", 200_900)))
    }
}
