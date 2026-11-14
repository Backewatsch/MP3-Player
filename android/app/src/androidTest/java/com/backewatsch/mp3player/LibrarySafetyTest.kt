package com.backewatsch.mp3player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Schutz der Mediathek: Lieder, Playlists und Herzen dürfen nicht durch einen kurzen Speicher-Aussetzer verloren gehen. */
@RunWith(AndroidJUnit4::class)
class LibrarySafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File

    @Before fun setUp() {
        root = File(context.cacheDir, "safety-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After fun tearDown() { root.deleteRecursively() }

    /** Neue Mediathek auf demselben Speicher – wie ein Neustart der App */
    private fun library() = Library(Storage(context, root), CoroutineScope(SupervisorJob() + Dispatchers.Default), autoConvert = false)

    private fun asset(name: String, as_: String): File {
        val dir = File(root, "src-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(dir, as_)
        testContext.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** Ist die Mediathek-Datei einmal nicht lesbar, lädt die App die letzte lesbare Fassung statt leer zu starten */
    @Test fun unreadableLibraryFileFallsBackToTheLastGoodOne() = runBlocking<Unit> {
        val first = library()
        val track = first.import(asset("untagged.mp3", "Bleibt.mp3"), move = true)
        val playlist = first.createPlaylist("Wichtig")
        first.add(track, playlist)
        first.flush()
        // Gebündeltes Speichern im Hintergrund abwarten, damit es die Datei nicht später noch einmal schreibt
        Thread.sleep(1_500)

        // Nächster Start: Datei lesbar → eine Reserve wird angelegt
        val second = library()
        assertEquals(listOf(track.id), second.tracks.map { it.id })
        val index = second.storage.indexFile
        assertTrue("Reserve angelegt", File(index.path + ".bak").exists())

        // Datei beschädigt (z. B. Speicherfehler): Der nächste Start lädt die Reserve
        index.writeText("{ kaputt")
        val third = library()
        assertEquals(listOf(track.id), third.tracks.map { it.id })
        assertEquals(listOf(track.id), third.playlists.first { it.name == "Wichtig" }.trackIds)

        // … und sichert sie gleich wieder als Hauptdatei
        third.flush()
        assertTrue(index.readText().contains(track.id))
        assertEquals(listOf(track.id), library().tracks.map { it.id })
    }

    /** Ist der Musikordner gerade nicht lesbar, streicht der Abgleich nichts aus der Mediathek */
    @Test fun rescanKeepsEverythingWhenTheMusicFolderIsUnreadable() = runBlocking<Unit> {
        val library = library()
        val track = library.import(asset("untagged.mp3", "Bleibt.mp3"), move = true)
        val playlist = library.createPlaylist("Wichtig")
        library.add(track, playlist)
        library.toggleFavorite(track)

        // Ordner weg (so sieht es aus, wenn der Speicher noch nicht bereit ist)
        assertTrue(library.storage.musicDir.deleteRecursively())
        library.rescan()
        assertEquals(listOf(track.id), library.tracks.map { it.id })
        assertEquals(listOf(track.id), library.playlist(playlist.id)!!.trackIds)
        assertTrue(library.isFavorite(track))

        // Ordner wieder da, nur die eine Datei fehlt wirklich: Das Lied wird wie bisher entfernt
        assertTrue(library.storage.musicDir.mkdirs())
        library.rescan()
        assertTrue(library.tracks.isEmpty())
        assertTrue(library.playlist(playlist.id)!!.trackIds.isEmpty())
    }
}
