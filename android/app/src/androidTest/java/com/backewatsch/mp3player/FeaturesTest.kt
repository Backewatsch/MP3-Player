package com.backewatsch.mp3player

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backewatsch.mp3player.model.Track
import com.backewatsch.mp3player.widget.PlayerWidget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Lieblingssongs, Mehrfachaktionen, Statistik, Sicherung, Widget und Equalizer mit echten Diensten */
@RunWith(AndroidJUnit4::class)
class FeaturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.app

    @Before fun setUp() {
        instrumentation.runOnMainSync { app.player.stop(); app.library.resetForTests() }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { app.player.stop() }
    }

    private fun add(name: String, asset: String = "untagged.mp3"): Track {
        val dir = File(context.cacheDir, "features-test").apply { mkdirs() }
        val file = File(dir, "$name.mp3")
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return runBlocking { app.library.import(file, move = true) }
    }

    private fun waitFor(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(100)
        }
        var ok = false
        instrumentation.runOnMainSync { ok = condition() }
        assertTrue(message, ok)
    }

    @Test fun favoritesAreKeptInOrderAndCleanedUp() {
        val a = add("A"); val b = add("B"); val c = add("C")
        app.library.toggleFavorite(a)
        app.library.toggleFavorite(c)
        assertEquals(listOf(c.id, a.id), app.library.favoriteTracks().map { it.id }) // zuletzt markiert zuerst
        assertTrue(app.library.isFavorite(a))
        app.library.toggleFavorite(a)
        assertFalse(app.library.isFavorite(a))
        app.library.addFavorites(listOf(a, b, c))
        assertEquals(setOf(a.id, b.id, c.id), app.library.favoriteTracks().map { it.id }.toSet())
        app.library.delete(b)
        assertFalse(app.library.state.value.favorites.contains(b.id))
    }

    @Test fun multiSelectActions() {
        val a = add("A"); val b = add("B"); val c = add("C")
        val playlist = app.library.createPlaylist("Mix")
        app.library.addAll(listOf(a, b), playlist)
        app.library.addAll(listOf(b, c), playlist) // keine Doppelten
        assertEquals(listOf(a.id, b.id, c.id), app.library.playlist(playlist.id)!!.trackIds)
        app.library.deleteAll(listOf(a, c))
        assertEquals(listOf(b.id), app.library.tracks.map { it.id })
        assertEquals(listOf(b.id), app.library.playlist(playlist.id)!!.trackIds)
        assertFalse(app.library.fileOf(a).exists())
    }

    @Test fun statisticsCountPlaysAndTime() {
        val a = add("A"); val b = add("B")
        app.library.recordPlay(a.id); app.library.recordPlay(a.id); app.library.recordPlay(b.id)
        app.library.addListened(90_000)
        val s = com.backewatsch.mp3player.data.Stats.summary(app.library.state.value)
        assertEquals(3, s.playsThisMonth)
        assertEquals(a.id, s.topThisMonth.first().first.id)
        assertEquals(90_000L, s.listenedThisMonthMs)
        app.library.clearStats()
        assertEquals(0, com.backewatsch.mp3player.data.Stats.summary(app.library.state.value).playsTotal)
    }

    @Test fun backupWithMusicRestoresEverything() {
        val a = add("A"); val b = add("B", "tagged.mp3")
        val playlist = app.library.createPlaylist("Party")
        app.library.addAll(listOf(b, a), playlist)
        app.library.toggleFavorite(b)
        app.library.togglePin(a)
        app.library.recordPlay(b.id)
        app.library.addListened(120_000)

        val bytes = ByteArrayOutputStream().also { runBlocking { app.backup.export(it, includeMusic = true) } }.toByteArray()
        // Inhalt der ZIP-Datei prüfen
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { names += (z.nextEntry ?: break).name } }
        assertTrue(names.toString(), "library.json" in names && "music/${a.fileName}" in names && "music/${b.fileName}" in names)

        // Neues Handy simulieren: alles weg
        instrumentation.runOnMainSync { app.library.resetForTests() }
        assertEquals(0, app.library.tracks.size)

        val result = runBlocking { app.backup.restore(ByteArrayInputStream(bytes)) }
        assertEquals(2, result.tracksAdded)
        assertEquals(setOf("A", "B"), app.library.tracks.map { it.fileName.substringBefore('.') }.toSet())
        assertTrue(app.library.tracks.all { app.library.fileOf(it).exists() })
        val restored = app.library.playlists.first { it.name == "Party" }
        assertEquals(listOf(b.id, a.id), restored.trackIds)
        assertEquals(listOf(b.id), app.library.state.value.favorites)
        assertEquals(listOf(a.id), app.library.state.value.pinned)
        assertEquals(1, app.library.state.value.playCounts[b.id])
        assertEquals(120_000L, app.library.state.value.listenedByMonth.values.sum())

        // Nochmal einspielen: nichts doppelt
        val again = runBlocking { app.backup.restore(ByteArrayInputStream(bytes)) }
        assertEquals(0, again.tracksAdded)
        assertEquals(2, again.tracksMatched)
        assertEquals(2, app.library.tracks.size)
        assertEquals(1, app.library.playlists.count { it.name == "Party" })
        assertEquals(2, app.library.playlists.first { it.name == "Party" }.trackIds.size)
    }

    @Test fun backupWithoutMusicRestoresPlaylistsAndHearts() {
        val a = add("A"); val b = add("B")
        val playlist = app.library.createPlaylist("Chill")
        app.library.addAll(listOf(a, b), playlist)
        app.library.toggleFavorite(a)
        val bytes = ByteArrayOutputStream().also { runBlocking { app.backup.export(it, includeMusic = false) } }.toByteArray()
        assertTrue("ohne Musik klein", bytes.size < 20_000)

        app.library.deletePlaylist(app.library.playlist(playlist.id)!!)
        app.library.toggleFavorite(a)
        val result = runBlocking { app.backup.restore(ByteArrayInputStream(bytes)) }
        assertEquals(0, result.tracksAdded)
        assertEquals(2, result.tracksMatched)
        assertEquals(listOf(a.id, b.id), app.library.playlists.first { it.name == "Chill" }.trackIds)
        assertTrue(app.library.isFavorite(a))
    }

    @Test fun invalidBackupGivesClearError() {
        val zip = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("irgendwas.txt")); it.write(1) }
        }.toByteArray()
        val error = runCatching { runBlocking { app.backup.restore(ByteArrayInputStream(zip)) } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("keine Sicherung"))
    }

    @Test fun widgetControlsThePlayer() {
        val a = add("Widgetlied")
        // Ansicht lässt sich bauen
        PlayerWidget.views(context, PlayerWidget.Info("Titel", "Band", isPlaying = true, favorite = true))

        fun send(action: String) = context.sendBroadcast(Intent(context, PlayerWidget::class.java).setAction(action))
        // Leere Wiedergabeliste → Widget startet alle Lieder
        send(PlayerWidget.ACTION_TOGGLE)
        waitFor("Widget startet die Wiedergabe") { app.player.state.value.isPlaying && app.player.state.value.currentId == a.id }
        send(PlayerWidget.ACTION_FAV)
        waitFor("Herz im Widget markiert das laufende Lied") { app.library.isFavorite(a) }
        send(PlayerWidget.ACTION_TOGGLE)
        waitFor("Widget pausiert") { !app.player.state.value.isPlaying }
    }

    @Test fun equalizerAppliesPresets() {
        val a = add("EQ")
        instrumentation.runOnMainSync { app.player.play(listOf(a), 0) }
        waitFor("Wiedergabe startet") { app.player.state.value.currentId == a.id }
        // Manche Emulatoren haben keinen Equalizer – dann überspringen
        val end = System.currentTimeMillis() + 5_000
        while (app.sound.info.value == null && System.currentTimeMillis() < end) Thread.sleep(100)
        assumeTrue("Kein Equalizer auf diesem Gerät", app.sound.info.value != null)
        try {
            instrumentation.runOnMainSync { app.sound.setEnabled(true); app.sound.setPreset("Bass-Boost") }
            assertTrue(app.sound.isEqualizerActive())
            // Geräte runden die Pegel (z. B. auf 1 dB) – kleine Abweichungen sind normal
            val wanted = app.sound.currentLevels()
            val applied = app.sound.appliedLevels()!!
            assertEquals(wanted.size, applied.size)
            wanted.zip(applied).forEach { (w, a) -> assertTrue("Pegel $applied statt $wanted", kotlin.math.abs(w - a) <= 100) }
            assertTrue("Bass angehoben", app.sound.currentLevels().first() > 0)
            instrumentation.runOnMainSync { app.sound.setBand(1, 0) }
            assertEquals(com.backewatsch.mp3player.player.SoundPresets.CUSTOM, app.sound.settings.value.preset)
            assertTrue(kotlin.math.abs(app.sound.appliedLevels()!![1]) <= 100)
        } finally {
            instrumentation.runOnMainSync { app.sound.setPreset("Normal"); app.sound.setEnabled(false) }
        }
    }
}

/** Update-Download: echte APK wird angenommen, Fehlerseiten und Nicht-APKs werden erkannt */
@RunWith(AndroidJUnit4::class)
class UpdateDownloadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val updates get() = context.app.updates
    private val target = File(context.cacheDir, "update-test.apk")

    @Test fun acceptsRealApk() {
        updates.download("http://10.0.2.2:8765/test-update.apk", target)
        assertTrue(com.backewatsch.mp3player.update.UpdateChecker.isApk(target))
        assertTrue(target.length() > 1_000_000)
    }

    @Test fun rejectsNonApk() {
        val error = runCatching { updates.download("http://10.0.2.2:8765/tagged.mp3", target) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("keine gültige Android-App"))
    }

    @Test fun rejectsErrorPage() {
        val error = runCatching { updates.download("http://10.0.2.2:8765/gibt-es-nicht.apk", target) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("404"))
    }

    @Test fun explainsInstallErrors() {
        val blocked = com.backewatsch.mp3player.update.UpdateChecker.installErrorText(android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED, null)
        assertTrue(blocked.contains("Auto Blocker"))
        val aborted = com.backewatsch.mp3player.update.UpdateChecker.installErrorText(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals("Installation abgebrochen.", aborted)
    }
}

/** Doppelte, Songtexte, eigenes Cover, Weiterhören, Lautheit und die Mediathek für Android Auto */
@RunWith(AndroidJUnit4::class)
class MoreFeaturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.app

    @Before fun setUp() {
        instrumentation.runOnMainSync { app.player.stop(); app.library.resetForTests() }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { app.player.stop() }
    }

    private fun add(name: String, asset: String = "untagged.mp3"): Track {
        val dir = File(context.cacheDir, "more-test").apply { mkdirs() }
        val file = File(dir, "$name.mp3")
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        return runBlocking { app.library.import(file, move = true) }
    }

    private fun waitFor(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(100)
        }
        var ok = false
        instrumentation.runOnMainSync { ok = condition() }
        assertTrue(message, ok)
    }

    @Test fun mergesDuplicatesKeepingPlaylistsAndHearts() {
        val a = add("Lied")
        val b = add("Lied (2)") // gleiche Datei, gleicher Titel aus dem Dateinamen? → Titel angleichen
        app.library.rename(b, a.title, a.artist)
        val playlist = app.library.createPlaylist("Mix")
        app.library.addAll(listOf(b), playlist)
        app.library.toggleFavorite(b)
        app.library.recordPlay(b.id)
        val groups = app.library.duplicateGroups()
        assertEquals(1, groups.size)
        val keep = app.library.mergeDuplicates(groups[0])
        assertEquals(1, app.library.tracks.size)
        assertEquals(listOf(keep.id), app.library.playlist(playlist.id)!!.trackIds)
        assertTrue(app.library.isFavorite(keep))
        assertEquals(1, app.library.playCount(keep))
        assertTrue(app.library.duplicateGroups().isEmpty())
    }

    @Test fun lyricsAndCustomCover() {
        val a = add("Text")
        assertEquals(null, app.library.lyricsOf(a))
        app.library.setLyrics(a, "  Erste Zeile\nZweite Zeile  ")
        assertEquals("Erste Zeile\nZweite Zeile", app.library.lyricsOf(a))
        app.library.setLyrics(a, "")
        assertEquals(null, app.library.lyricsOf(a))

        val jpeg = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(50, 50, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        app.library.setCustomCover(a, jpeg)
        assertTrue(app.library.hasCustomCover(a))
        assertTrue(app.library.artworkOf(a) != null)
        assertTrue(com.backewatsch.mp3player.ui.ArtworkLoader.bitmap(context, a) != null)
        app.library.removeCustomCover(a)
        assertFalse(app.library.hasCustomCover(a))
    }

    @Test fun remembersQueueForRestart() {
        val a = add("Eins"); val b = add("Zwei")
        instrumentation.runOnMainSync { app.player.play(listOf(a, b), 1) }
        waitFor("Wiedergabe startet") { app.player.state.value.currentId == b.id }
        val prefs = context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
        waitFor("Wiedergabeliste gespeichert") { prefs.getString("queue", null) == "${a.id},${b.id}" && prefs.getInt("index", -1) == 1 }
        instrumentation.runOnMainSync { app.player.stop() }
        waitFor("nach Schließen gelöscht") { prefs.getString("queue", null) == null }
    }

    @Test fun measuresLoudnessOfTracks() {
        val a = add("Laut", "tagged.mp3")
        val gain = com.backewatsch.mp3player.convert.LoudnessAnalyzer.gainDb(app.library.fileOf(a))
        assertTrue("Lautheit messbar", gain != null && gain in -12f..6f)
    }

    @Test fun androidAutoCanBrowseLibrary() {
        val a = add("Auto1"); add("Auto2")
        app.library.toggleFavorite(a)
        val token = androidx.media3.session.SessionToken(context, android.content.ComponentName(context, com.backewatsch.mp3player.player.PlaybackService::class.java))
        // Media3 verlangt Aufrufe auf dem Haupt-Thread – Ergebnisse hier abwarten
        fun <T> onMain(block: () -> com.google.common.util.concurrent.ListenableFuture<T>): T {
            var f: com.google.common.util.concurrent.ListenableFuture<T>? = null
            instrumentation.runOnMainSync { f = block() }
            return f!!.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        val browser = onMain { androidx.media3.session.MediaBrowser.Builder(context, token).buildAsync() }
        try {
            val root = onMain { browser.getLibraryRoot(null) }.value!!
            val folders = onMain { browser.getChildren(root.mediaId, 0, 50, null) }.value!!
            assertTrue(folders.map { it.mediaMetadata.title.toString() }.containsAll(listOf("Lieblingssongs", "Playlists", "Alle Titel")))
            val favs = onMain { browser.getChildren("favorites", 0, 50, null) }.value!!
            assertEquals(listOf(a.id), favs.map { it.mediaId })
            val all = onMain { browser.getChildren("all", 0, 50, null) }.value!!
            assertEquals(2, all.size)
            // Auswahl im Auto: Eintrag nur mit ID → Dienst findet die Datei selbst
            instrumentation.runOnMainSync {
                browser.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(a.id).build())
                browser.prepare(); browser.play()
            }
            waitFor("Auto-Auswahl spielt") { browser.isPlaying && browser.currentMediaItem?.mediaId == a.id }
        } finally {
            instrumentation.runOnMainSync { browser.stop(); browser.release() }
        }
    }
}
