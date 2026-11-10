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

    /** Ausblenden: Ton wird neu kodiert, am Ende leiser, Tags und Cover bleiben */
    @Test fun fadesOutAndKeepsTags() = runBlocking {
        val track = importTagged()
        val before = library.exactDurationMs(track)
        val result = library.trim(track, 0, before, asCopy = false, fadeInMs = 0, fadeOutMs = 1_000)
        val file = library.fileOf(result)
        val info = MetadataReader.read(file)
        assertEquals("Testlied", info.title)
        assertTrue("Cover bleibt", info.artwork != null)
        assertTrue("Länge bleibt etwa gleich: ${info.durationMs} statt $before", abs(info.durationMs - before) < 250)
        assertTrue(abs(result.durationMs - before) < 250)
        // Lautstärke: letzte halbe Sekunde deutlich leiser als die erste Sekunde
        val (start, end) = loudness(file, before)
        assertTrue("Ende leiser als Anfang (Anfang $start, Ende $end)", end < start * 0.6)
    }

    /** Mittlere Lautstärke der ersten Sekunde und der letzten 400 ms */
    private fun loudness(file: File, totalMs: Long): Pair<Double, Double> {
        val extractor = android.media.MediaExtractor().apply { setDataSource(file.absolutePath); selectTrack(0) }
        val format = extractor.getTrackFormat(0)
        val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0); codec.start()
        val info = android.media.MediaCodec.BufferInfo()
        var sumStart = 0.0; var nStart = 0L; var sumEnd = 0.0; var nEnd = 0L
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val ms = info.presentationTimeUs / 1000
                val sb = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                while (sb.hasRemaining()) {
                    val v = abs(sb.get().toInt()).toDouble()
                    if (ms in 100..1000) { sumStart += v; nStart++ } else if (ms >= totalMs - 400) { sumEnd += v; nEnd++ }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); extractor.release()
        return (sumStart / maxOf(1, nStart)) to (sumEnd / maxOf(1, nEnd))
    }
}
