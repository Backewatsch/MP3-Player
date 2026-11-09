package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.Duplicates
import com.backewatsch.mp3player.data.Id3Lyrics
import com.backewatsch.mp3player.data.LoudnessMeter
import com.backewatsch.mp3player.data.SmartPlaylist
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.PlayEvent
import com.backewatsch.mp3player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Doppelte, Mixe, Songtexte und Lautheit – ohne Gerät */
class MoreLogicTest {
    private fun t(id: String, title: String, artist: String, dur: Long, file: String = "$id.mp3", added: Long = 0) =
        Track(id = id, fileName = file, title = title, artist = artist, durationMs = dur, addedAt = added)

    @Test fun findsDuplicates() {
        val a = t("a", "Blinding Lights", "The Weeknd", 200_000)
        val b = t("b", "Blinding Lights (Official Video)", "the weeknd", 201_500, file = "b.m4a")
        val c = t("c", "Blinding Lights", "The Weeknd", 320_000) // Live-Version: deutlich länger
        val d = t("d", "Anderes Lied", "The Weeknd", 200_000)
        val groups = Duplicates.groups(listOf(a, b, c, d))
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups[0].map { it.id }.toSet())
        // MP3 bleibt vor M4A, auch wenn die andere öfter gespielt wurde
        assertEquals("a", Duplicates.keeper(groups[0], mapOf("b" to 5), { 0 }, emptySet()).id)
        assertEquals("cafe", Duplicates.normalize("Café"))
    }

    @Test fun smartPlaylists() {
        val day = 24L * 3600 * 1000
        val now = 100 * day
        val fresh = t("fresh", "Neu", "", 1, added = now - 2 * day)
        val old = t("old", "Alt", "", 1, added = now - 90 * day)
        val loved = t("loved", "Oft", "", 1, added = now - 90 * day)
        val state = LibraryState(
            tracks = listOf(fresh, old, loved),
            playCounts = mapOf("old" to 3, "loved" to 4),
            history = listOf(PlayEvent("old", now - 60 * day)),
            plays = listOf(PlayEvent("loved", now - day), PlayEvent("loved", now - 2 * day), PlayEvent("old", now - 60 * day)),
        )
        assertEquals(listOf("fresh"), SmartPlaylist.RECENTLY_ADDED.tracks(state, now).map { it.id })
        assertEquals(listOf("loved"), SmartPlaylist.ON_REPEAT.tracks(state, now).map { it.id })
        assertEquals(listOf("old"), SmartPlaylist.REDISCOVER.tracks(state, now).map { it.id })
        assertEquals(listOf("fresh"), SmartPlaylist.NEVER_PLAYED.tracks(state, now).map { it.id })
    }

    /** ID3v2.3-Tag mit USLT-Frame bauen */
    private fun id3(version: Int, encoding: Int, text: String): ByteArray {
        val textBytes = when (encoding) {
            1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            3 -> text.toByteArray(Charsets.UTF_8)
            else -> text.toByteArray(Charsets.ISO_8859_1)
        }
        val desc = if (encoding == 1) byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) else byteArrayOf(0)
        val body = byteArrayOf(encoding.toByte()) + "deu".toByteArray() + desc + textBytes
        fun size(n: Int, synch: Boolean) = if (synch) byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
            else byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        val title = byteArrayOf(0) + "Titel".toByteArray()
        val frames = ByteArrayOutputStream().apply {
            write("TIT2".toByteArray()); write(size(title.size, version == 4)); write(byteArrayOf(0, 0)); write(title)
            write("USLT".toByteArray()); write(size(body.size, version == 4)); write(byteArrayOf(0, 0)); write(body)
        }.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + size(frames.size, true) + frames
    }

    @Test fun readsLyrics() {
        assertEquals("Zeile eins\nZeile zwei", Id3Lyrics.parse(id3(3, 0, "Zeile eins\r\nZeile zwei")))
        assertEquals("Grüße aus München ♥", Id3Lyrics.parse(id3(4, 3, "Grüße aus München ♥")))
        assertEquals("Ünïcödé", Id3Lyrics.parse(id3(3, 1, "Ünïcödé")))
        assertNull(Id3Lyrics.parse("kein tag".toByteArray()))
    }

    @Test fun measuresLoudness() {
        fun sine(amplitude: Double, seconds: Int = 2, rate: Int = 44_100) =
            ShortArray(rate * seconds) { (amplitude * 32767 * sin(2 * PI * 440 * it / rate)).toInt().toShort() }
        val loud = LoudnessMeter(44_100, 1).apply { add(sine(0.9)) }.loudnessDb()!!
        val quiet = LoudnessMeter(44_100, 1).apply { add(sine(0.1)) }.loudnessDb()!!
        assertEquals(-3.9, loud, 0.3)    // Sinus: RMS = Amplitude/√2
        assertEquals(-23.0, quiet, 0.3)
        assertTrue(LoudnessMeter.gainFor(loud) < 0)   // lautes Lied wird leiser
        assertTrue(LoudnessMeter.gainFor(quiet) > 0)  // leises lauter (max. +6 dB)
        assertEquals(6f, LoudnessMeter.gainFor(quiet), 0.001f)
        assertEquals(1f, LoudnessMeter.attenuation(3f), 0f)
        assertEquals(0.5f, LoudnessMeter.attenuation(-6.02f), 0.01f)
        // Stille zählt nicht
        assertNull(LoudnessMeter(44_100, 1).apply { add(ShortArray(44_100)) }.loudnessDb())
    }
}
