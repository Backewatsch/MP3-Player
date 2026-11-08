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
}
