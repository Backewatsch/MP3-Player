package com.backewatsch.mp3player

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.backewatsch.mp3player.data.DeviceMusicImporter
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.Storage
import com.backewatsch.mp3player.data.exactDurationMs
import com.backewatsch.mp3player.data.trim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Übertragung „wie aus Samsung Music“: Lied liegt im Android-Medienspeicher, Playlist als .m3u */
@RunWith(AndroidJUnit4::class)
class TransferTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File
    private lateinit var library: Library
    private lateinit var importer: DeviceMusicImporter
    private val inserted = mutableListOf<Uri>()
    private val songName = "Samsung-Test-${UUID.randomUUID().toString().take(6)}.mp3"

    @Before fun setUp() {
        root = File(context.cacheDir, "transfer-" + UUID.randomUUID()).apply { mkdirs() }
        library = Library(Storage(context, root), CoroutineScope(SupervisorJob() + Dispatchers.Default), autoConvert = false)
        importer = DeviceMusicImporter(context, library)

        // Lied so ablegen, wie es Samsung Music sieht (Ordner Music, im Medienspeicher)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, songName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MP3PlayerTest")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        resolver.openOutputStream(uri)!!.use { out -> testContext.assets.open("tagged.mp3").use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    }

    @After fun tearDown() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        root.deleteRecursively()
    }

    @Test fun transfersSongsWithoutDuplicates() = runBlocking {
        var songs = importer.scanSongs()
        repeat(10) { if (songs.none { it.displayName == songName }) { Thread.sleep(500); songs = importer.scanSongs() } }
        val song = songs.firstOrNull { it.displayName == songName }
        assertNotNull("Lied wird im Medienspeicher gefunden", song)

        val first = importer.importSongs(listOf(song!!))
        assertEquals(1, first.imported)
        val track = library.trackFromSource(song.sourceKey)!!
        assertEquals("Testlied", track.title)
        assertTrue(track.fileName.endsWith(".mp3"))
        // Verknüpft, nicht kopiert: die App spielt die Originaldatei, ihr eigener Ordner bleibt leer
        assertTrue("verknüpft", track.isLinked)
        assertEquals(song.path, library.fileOf(track).absolutePath)
        assertTrue(root.resolve("Music").listFiles().orEmpty().none { it.name.endsWith(".mp3") })

        val second = importer.importSongs(listOf(song))
        assertEquals("schon übertragen → übersprungen", 1, second.skipped)
        assertEquals(1, library.tracks.size)
    }

    @Test fun importsPlaylistFileAndFetchesMissingSongs() = runBlocking {
        var songs = importer.scanSongs()
        repeat(10) { if (songs.none { it.displayName == songName }) { Thread.sleep(500); songs = importer.scanSongs() } }
        val song = songs.first { it.displayName == songName }

        // Playlist wie von Samsung Music exportiert – Lied ist noch NICHT übertragen
        val m3u = File(root, "Lieblingslieder.m3u").apply {
            writeText("#EXTM3U\n#EXTINF:3,Testband - Testlied\n${song.path}\n/storage/emulated/0/Music/GibtEsNicht.mp3\n")
        }
        val result = importer.importPlaylistFiles(listOf(Uri.fromFile(m3u)))
        assertEquals(1, result.playlists)
        assertEquals(1, result.tracks)
        assertEquals(1, result.missing)
        val playlist = library.playlists.first { it.name == "Lieblingslieder" }
        val tracks = library.tracksIn(playlist)
        assertEquals(listOf("Testlied"), tracks.map { it.title })

        // Erneuter Import: keine doppelten Playlists/Titel
        importer.importPlaylistFiles(listOf(Uri.fromFile(m3u)))
        assertEquals(1, library.playlists.count { it.name == "Lieblingslieder" })
        assertEquals(1, library.playlists.first { it.name == "Lieblingslieder" }.trackIds.size)
    }

    /** Verknüpfte Lieder: Zuschneiden kürzt das Original, Löschen löscht es überall; verschwindet es, verschwindet das Lied */
    @Test fun linkedSongsChangeTheOriginal() = runBlocking {
        var songs = importer.scanSongs()
        repeat(10) { if (songs.none { it.displayName == songName }) { Thread.sleep(500); songs = importer.scanSongs() } }
        val song = songs.first { it.displayName == songName }
        importer.importSongs(listOf(song))
        val track = library.trackFromSource(song.sourceKey)!!
        val original = File(song.path)

        // Neu einlesen behält das verknüpfte Lied
        library.rescan()
        assertEquals(1, library.tracks.size)

        // Das Testlied hat die App selbst angelegt → sie darf es ändern (sonst: „Zugriff auf alle Dateien“)
        assertTrue(library.canModifyFile(track))
        val before = library.exactDurationMs(track)
        val sizeBefore = original.length()
        library.trim(track, 0, before - 1_000, asCopy = false)
        assertTrue("Original gekürzt", original.length() < sizeBefore)
        assertTrue(kotlin.math.abs(library.exactDurationMs(library.track(track.id)!!) - (before - 1_000)) < 100)
        assertTrue("Original weiterhin verknüpft", library.track(track.id)!!.isLinked)
        // Keine Zwischendateien im Ordner des Originals
        assertTrue(original.parentFile!!.listFiles().orEmpty().none { it.name.startsWith(".") })

        // Löschen in der App löscht auch das Original
        library.delete(library.track(track.id)!!)
        assertEquals(0, library.tracks.size)
        assertTrue("Original gelöscht", !original.exists())
        inserted.clear()
    }

    /** Verschwindet ein verknüpftes Original (z. B. in Samsung Music gelöscht), verschwindet das Lied beim Einlesen */
    @Test fun linkedSongDisappearsWithItsOriginal() = runBlocking {
        var songs = importer.scanSongs()
        repeat(10) { if (songs.none { it.displayName == songName }) { Thread.sleep(500); songs = importer.scanSongs() } }
        val song = songs.first { it.displayName == songName }
        importer.importSongs(listOf(song))
        assertEquals(1, library.tracks.size)
        inserted.forEach { context.contentResolver.delete(it, null, null) }
        inserted.clear()
        library.rescan()
        assertEquals(0, library.tracks.size)
    }

    /** Ältere Kopien lassen sich durch Verknüpfungen ersetzen und geben Speicher frei */
    @Test fun replacesOldCopiesWithLinks() = runBlocking {
        var songs = importer.scanSongs()
        repeat(10) { if (songs.none { it.displayName == songName }) { Thread.sleep(500); songs = importer.scanSongs() } }
        val song = songs.first { it.displayName == songName }
        // So hat die App früher übernommen: eigene Kopie mit Herkunft
        val copySource = File(root, "copy.mp3").apply { File(song.path).copyTo(this) }
        val copy = library.import(copySource, preferredName = songName, move = true, sourceKey = song.sourceKey)
        assertTrue(!copy.isLinked)
        assertEquals(listOf(copy.id), library.linkableCopies().map { it.id })

        val (count, freed) = library.replaceCopiesWithLinks()
        assertEquals(1, count)
        assertTrue(freed > 0)
        val linked = library.track(copy.id)!!
        assertTrue(linked.isLinked)
        assertEquals(song.path, library.fileOf(linked).absolutePath)
        assertTrue(library.linkableCopies().isEmpty())
    }
}
