package com.backewatsch.mp3player

import com.backewatsch.mp3player.data.PlaylistFiles
import com.backewatsch.mp3player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFilesTest {
    private val m3u = """
        #EXTM3U
        #EXTINF:215,Die Band - Sommerlied
        /storage/emulated/0/Music/Sommerlied.mp3
        #EXTINF:180,Zweites Lied
        ../Download/Zweites Lied.m4a

        C:\Musik\Drittes.mp3
        /storage/emulated/0/Music/Fehlt.mp3
    """.trimIndent()

    private val tracks = listOf(
        Track(id = "a", fileName = "Sommerlied.mp3", title = "Sommerlied", artist = "Die Band",
            source = "/storage/emulated/0/Music/Sommerlied.mp3"),
        Track(id = "b", fileName = "Zweites Lied (2).mp3", title = "Zweites Lied", source = "/storage/emulated/0/Download/Zweites Lied.m4a"),
        Track(id = "c", fileName = "Drittes.mp3", title = "Drittes"),
        Track(id = "d", fileName = "Anders.mp3", title = "Nur Titel", artist = "X"),
    )

    @Test fun parsesEntriesAndTitles() {
        val entries = PlaylistFiles.parse(m3u)
        assertEquals(4, entries.size)
        assertEquals("/storage/emulated/0/Music/Sommerlied.mp3", entries[0].path)
        assertEquals("Die Band - Sommerlied", entries[0].title)
        assertEquals("C:/Musik/Drittes.mp3", entries[2].path)
        assertNull(entries[2].title)
    }

    @Test fun matchesByPathFileNameAndTitle() {
        val entries = PlaylistFiles.parse(m3u)
        assertEquals("a", PlaylistFiles.match(entries[0], tracks)?.id)  // Originalpfad
        assertEquals("b", PlaylistFiles.match(entries[1], tracks)?.id)  // Dateiname (andere Endung/Ordner)
        assertEquals("c", PlaylistFiles.match(entries[2], tracks)?.id)  // Dateiname in der App
        assertNull(PlaylistFiles.match(entries[3], tracks))               // nicht vorhanden
        assertEquals("d", PlaylistFiles.match(PlaylistFiles.Entry("weg.mp3", "X - Nur Titel"), tracks)?.id) // #EXTINF-Titel
    }

    @Test fun helpers() {
        assertTrue(PlaylistFiles.isPlaylistFile("Party.M3U"))
        assertTrue(PlaylistFiles.isPlaylistFile("x.m3u8"))
        assertEquals("Party", PlaylistFiles.nameFromFile("Party.m3u"))
        assertEquals("lied", PlaylistFiles.key("/a/b/Lied (3).mp3"))
    }
}
