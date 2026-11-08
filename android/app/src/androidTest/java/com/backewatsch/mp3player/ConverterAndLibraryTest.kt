package com.backewatsch.mp3player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backewatsch.mp3player.convert.Mp3Converter
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.MetadataReader
import com.backewatsch.mp3player.data.Storage
import com.backewatsch.mp3player.model.LibraryState.Companion.DOWNLOADS_PLAYLIST_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Läuft auf dem Gerät/Emulator: echte MP3-Umwandlung mit LAME und die Bibliothek. */
@RunWith(AndroidJUnit4::class)
class ConverterAndLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File
    private lateinit var library: Library

    @Before fun setUp() {
        root = File(context.cacheDir, "test-" + UUID.randomUUID()).apply { mkdirs() }
        library = Library(Storage(context, root), CoroutineScope(SupervisorJob() + Dispatchers.Default), autoConvert = false)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun asset(name: String, as_: String = name): File {
        val dir = File(root, "src-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(dir, as_)
        testContext.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** Echte MP3: ID3v2-Kopf, danach ein MPEG-Audio-Frame */
    private fun assertRealMp3(file: File) {
        val data = file.readBytes()
        assertTrue("Datei zu klein", data.size > 1000)
        assertEquals("ID3", String(data, 0, 3, Charsets.US_ASCII))
        val size = ((data[6].toInt() and 0x7F) shl 21) or ((data[7].toInt() and 0x7F) shl 14) or
            ((data[8].toInt() and 0x7F) shl 7) or (data[9].toInt() and 0x7F)
        val frame = 10 + size
        assertEquals("kein MP3-Frame", 0xFF, data[frame].toInt() and 0xFF)
        assertEquals("kein MP3-Frame", 0xE0, data[frame + 1].toInt() and 0xE0)
    }

    @Test fun videoBecomesMp3WithTags() {
        val out = File(root, "video.mp3")
        Mp3Converter.convert(asset("clip.mp4"), out)
        assertRealMp3(out)
        val info = MetadataReader.read(out)
        assertEquals("Videolied", info.title)
        assertEquals("Videoband", info.artist)
        assertTrue("Dauer ${info.durationMs}", abs(info.durationMs - 2000) < 400)
    }

    @Test fun m4aBecomesMp3WithUmlauts() {
        val out = File(root, "umlaut.mp3")
        Mp3Converter.convert(asset("short.m4a"), out)
        assertRealMp3(out)
        val info = MetadataReader.read(out)
        assertEquals("Kurzes Stück", info.title)
        assertEquals("Ärzte-Band", info.artist)
        assertEquals("Übung", info.album)
    }

    @Test fun videoWithoutSoundFails() {
        try {
            Mp3Converter.convert(asset("silent.mp4"), File(root, "x.mp3"))
            fail("Video ohne Ton darf nicht umgewandelt werden")
        } catch (e: Mp3Converter.ConversionException) {
            assertTrue(e.message!!.contains("keine Tonspur"))
        }
    }

    @Test fun importConvertsToMp3AndGoesToDownloads() = runBlocking {
        var added: Pair<String, String?>? = null
        library.onTrackAdded = { t, p -> added = t.id to p }
        val track = library.import(asset("clip.mp4", "Mein Video.mp4"), move = true, addToDownloads = true)
        assertTrue(track.fileName.endsWith(".mp3"))
        assertRealMp3(library.fileOf(track))
        assertEquals("Videolied", track.title)
        val downloads = library.playlist(DOWNLOADS_PLAYLIST_ID)!!
        assertEquals(listOf(track.id), downloads.trackIds)
        assertEquals(track.id to DOWNLOADS_PLAYLIST_ID, added)
    }

    @Test fun mp3ImportKeepsFileAndReadsTags() = runBlocking {
        val track = library.import(asset("tagged.mp3"), move = false)
        assertEquals("Testlied", track.title)
        assertEquals("Testband", track.artist)
        assertEquals("Testalbum", track.album)
        assertNotNull("Cover gespeichert", library.artworkOf(track))
        val again = library.import(asset("tagged.mp3"), move = false)
        assertTrue("eindeutiger Dateiname", again.fileName.contains("(2)"))
    }

    @Test fun rejectsNonMedia() = runBlocking {
        val txt = File(root, "notiz.txt").apply { writeText("hallo") }
        try {
            library.import(txt, move = false)
            fail("Textdatei darf nicht importiert werden")
        } catch (e: Library.LibraryException) {
            assertTrue(e.message!!.contains("keine unterstützte"))
        }
    }

    @Test fun playlistsPinAndPersistence() = runBlocking {
        val a = library.import(asset("untagged.mp3", "a.mp3"), move = true)
        val b = library.import(asset("untagged.mp3", "b.mp3"), move = true)
        val c = library.import(asset("tagged.mp3", "c.mp3"), move = true)
        var playlist = library.createPlaylist("  Party ")
        assertEquals("Party", playlist.name)
        library.add(a, playlist); library.add(b, playlist); library.add(c, playlist); library.add(a, playlist)
        playlist = library.playlist(playlist.id)!!
        assertEquals(listOf(a.id, b.id, c.id), library.tracksIn(playlist).map { it.id })
        library.move(playlist, 2, 0)
        assertEquals(listOf(c.id, a.id, b.id), library.tracksIn(library.playlist(playlist.id)!!).map { it.id })
        library.togglePin(c)
        assertTrue(library.isPinned(c))
        library.delete(a)
        assertEquals(listOf(c.id, b.id), library.tracksIn(library.playlist(playlist.id)!!).map { it.id })
        assertTrue(library.tracksInAlbum("testalbum").any { it.id == c.id })

        // Neu laden → alles gespeichert
        val reloaded = Library(Storage(context, root), CoroutineScope(Dispatchers.Default), autoConvert = false)
        assertEquals(listOf(c.id, b.id), reloaded.tracksIn(reloaded.playlist(playlist.id)!!).map { it.id })
        assertTrue(reloaded.isPinned(c))
        library.deletePlaylist(library.playlist(DOWNLOADS_PLAYLIST_ID)!!)
        assertNotNull("Download-Playlist bleibt", library.playlist(DOWNLOADS_PLAYLIST_ID))
    }

    @Test fun rescanAndConvertExistingFile() = runBlocking {
        val storage = library.storage
        testContext.assets.open("short.m4a").use { input -> File(storage.musicDir, "kopiert.m4a").outputStream().use { input.copyTo(it) } }
        library.rescan()
        library.rescan()
        val track = library.tracks.single { it.fileName == "kopiert.m4a" }
        val playlist = library.createPlaylist("Konvertiert")
        library.add(track, playlist)
        library.togglePin(track)

        val converted = library.convertToMp3(track)
        assertEquals(track.id, converted.id)
        assertTrue(converted.fileName.endsWith(".mp3"))
        assertRealMp3(library.fileOf(converted))
        assertFalse(File(storage.musicDir, "kopiert.m4a").exists())
        assertEquals(listOf(track.id), library.playlist(playlist.id)!!.trackIds)
        assertTrue(library.isPinned(converted))
        library.rescan()
        assertEquals(1, library.tracks.count { it.id == track.id })

        // Datei extern gelöscht → verschwindet
        library.fileOf(converted).delete()
        library.rescan()
        assertFalse(library.tracks.any { it.id == track.id })
    }
}
