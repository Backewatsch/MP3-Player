package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.Genres
import com.backewatsch.mp3player.data.Id3Lyrics
import com.backewatsch.mp3player.data.Id3Writer
import com.backewatsch.mp3player.data.Search
import com.backewatsch.mp3player.model.LibraryState
import com.backewatsch.mp3player.model.Playlist
import com.backewatsch.mp3player.model.Track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Suche, Genres und das Schreiben von MP3-Tags – ohne Gerät */
class SearchTagsTest {
    @Test fun normalizesGenres() {
        assertEquals("Rock", Genres.normalize("(17)"))
        assertEquals("Hip-Hop", Genres.normalize("7"))
        assertEquals("Hip-Hop", Genres.normalize("hip hop"))
        assertEquals("Pop", Genres.normalize("pop; dance"))
        assertEquals("R&B", Genres.normalize("RnB"))
        assertEquals("Deutschrap", Genres.normalize("deutschrap"))
        assertEquals("Indie Rock", Genres.normalize("indie rock"))
        assertEquals("", Genres.normalize("  "))
        assertEquals("", Genres.normalize(null))
    }

    @Test fun searchesByCategory() {
        val a = Track(id = "a", fileName = "a.mp3", title = "Bella Ciao", artist = "Marco Rossi", album = "Sommer", genre = "Pop")
        val b = Track(id = "b", fileName = "b.mp3", title = "Sommernacht", artist = "Bella", album = "Nachts", genre = "Hip-Hop")
        val c = Track(id = "c", fileName = "c.mp3", title = "Regen", artist = "Andere", album = "", genre = "Rock")
        val state = LibraryState(tracks = listOf(a, b, c), playlists = listOf(Playlist(id = "p", name = "Sommer Hits")))
        val r = Search.run(state, "bella")
        assertEquals(listOf("a", "b"), r.tracks.map { it.id }) // Titel vor Interpret
        assertEquals(listOf("Bella"), r.artists)
        val s = Search.run(state, "sommer")
        assertEquals(listOf("Sommer"), s.albums)
        assertEquals(listOf("Sommer Hits"), s.playlists.map { it.name })
        assertEquals(listOf("Hip-Hop"), Search.run(state, "hip").genres)
        assertTrue(Search.run(state, "xyz").isEmpty)
        assertTrue(Search.run(state, " ").isEmpty)
    }

    @Test fun remembersRecentSearches() {
        var recent = emptyList<String>()
        recent = Search.remember(recent, "Rock")
        recent = Search.remember(recent, "Pop")
        recent = Search.remember(recent, "rock")
        assertEquals(listOf("rock", "Pop"), recent)
        assertEquals(recent, Search.remember(recent, "a")) // zu kurz
        var many = emptyList<String>()
        for (i in 1..12) many = Search.remember(many, "Suche $i")
        assertEquals(8, many.size)
        assertEquals("Suche 12", many.first())
    }

    /** Minimale „MP3“: ID3-Tag mit TIT2 + ein paar Audio-Bytes */
    private fun sampleMp3(): File {
        val title = byteArrayOf(0) + "Alt".toByteArray()
        val frame = "TIT2".toByteArray() + byteArrayOf(0, 0, 0, title.size.toByte(), 0, 0) + title
        val other = byteArrayOf(0) + "2024".toByteArray()
        val frame2 = "TYER".toByteArray() + byteArrayOf(0, 0, 0, other.size.toByte(), 0, 0) + other
        val body = frame + frame2
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, body.size.toByte()) + body
        val audio = ByteArray(5000) { (it % 251).toByte() }
        return File.createTempFile("tag", ".mp3").apply { writeBytes(tag + audio) }
    }

    private fun audioOf(f: File): ByteArray {
        val b = f.readBytes()
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        return b.copyOfRange(10 + size, b.size)
    }

    @Test fun writesTagsAndKeepsAudio() {
        val f = sampleMp3()
        val audioBefore = audioOf(f)
        val cover = ByteArray(300) { 7 }
        Id3Writer.write(f, Id3Writer.Update(title = "Neuer Titel", artist = "Künstlerin", album = "Album", genre = "Pop",
            cover = cover, lyrics = "Zeile 1\nZeile 2"))
        assertArrayEquals("Audio bleibt unverändert", audioBefore, audioOf(f))
        assertEquals("Zeile 1\nZeile 2", Id3Lyrics.parse(f.readBytes()))
        val bytes = f.readBytes()
        fun has(text: String): Boolean {
            val p = text.toByteArray(Charsets.UTF_16LE)
            return (0..bytes.size - p.size).any { i -> p.indices.all { bytes[i + it] == p[it] } }
        }
        assertTrue("Titel und Interpret als UTF-16 im Tag", has("Neuer Titel") && has("Künstlerin") && has("Pop"))
        assertTrue("TYER bleibt erhalten", String(f.readBytes(), Charsets.ISO_8859_1).contains("TYER"))
        assertTrue("alter Titel ersetzt", !String(f.readBytes(), Charsets.ISO_8859_1).contains("\u0000Alt"))

        // Nochmal schreiben: Cover entfernen, Rest bleibt
        Id3Writer.write(f, Id3Writer.Update(cover = ByteArray(0)))
        assertTrue(!String(f.readBytes(), Charsets.ISO_8859_1).contains("APIC"))
        assertEquals("Zeile 1\nZeile 2", Id3Lyrics.parse(f.readBytes()))
        assertArrayEquals(audioBefore, audioOf(f))
        f.delete()
    }

    @Test fun writesTagIntoFileWithoutTag() {
        val f = File.createTempFile("notag", ".mp3").apply { writeBytes(ByteArray(3000) { 1 }) }
        Id3Writer.write(f, Id3Writer.Update(title = "Ohne Tag", lyrics = "Hallo"))
        assertEquals("Hallo", Id3Lyrics.parse(f.readBytes()))
        assertArrayEquals(ByteArray(3000) { 1 }, audioOf(f))
        f.delete()
    }
}
