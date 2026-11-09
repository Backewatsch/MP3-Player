package com.backewatsch.mp3player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backewatsch.mp3player.data.Library
import com.backewatsch.mp3player.data.MetadataReader
import com.backewatsch.mp3player.data.Storage
import com.backewatsch.mp3player.data.exactDurationMs
import com.backewatsch.mp3player.data.trim
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
import kotlin.math.abs

/** Zuschneiden auf dem Gerät: Android liest danach Länge und Titel richtig */
@RunWith(AndroidJUnit4::class)
class TrimTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File
    private lateinit var library: Library

    @Before fun setUp() {
        root = File(context.cacheDir, "trim-" + UUID.randomUUID()).apply { mkdirs() }
        library = Library(Storage(context, root), CoroutineScope(SupervisorJob() + Dispatchers.Default), autoConvert = false)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun importTagged() = runBlocking {
        val f = File(root, "Lied.mp3")
        testContext.assets.open("tagged.mp3").use { i -> f.outputStream().use { i.copyTo(it) } }
        library.import(f, move = true)
    }

    @Test fun replacesOriginal() = runBlocking {
        val track = importTagged()
        val before = library.exactDurationMs(track)
        val result = library.trim(track, 0, before - 1_000, asCopy = false)
        assertEquals(track.id, result.id)
        assertEquals(1, library.tracks.size)
        val info = MetadataReader.read(library.fileOf(result))
        assertTrue("Android liest ${info.durationMs} ms, erwartet ≈ ${before - 1000}", abs(info.durationMs - (before - 1_000)) < 150)
        assertTrue(abs(result.durationMs - (before - 1_000)) < 60)
        assertEquals("Testlied", info.title)
        assertTrue("Cover bleibt in der Datei", info.artwork != null)
    }

    @Test fun savesCopy() = runBlocking {
        val track = importTagged()
        val before = library.exactDurationMs(track)
        val copy = library.trim(track, 500, before, asCopy = true)
        assertEquals(2, library.tracks.size)
        assertEquals("Testlied (gekürzt)", copy.title)
        assertTrue(abs(copy.durationMs - (before - 500)) < 150)
        // Original unverändert
        assertEquals(before, library.exactDurationMs(library.track(track.id)!!))
    }
}
