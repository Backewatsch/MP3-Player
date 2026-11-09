package com.backewatsch.mp3player

import android.Manifest
import android.graphics.Bitmap
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Bedient die App wie ein Mensch und speichert Screenshots (werden im Workflow abgeholt). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    /** Benachrichtigungs-Erlaubnis vorab erteilen, sonst verdeckt der Systemdialog die App */
    @get:Rule(order = 0) val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun reset() {
        instrumentation.runOnMainSync { instrumentation.targetContext.app.library.resetForTests() }
        rule.waitForIdle()
    }

    /** Prüft, dass der Browserbereich dunkel ist (Bildausschnitt [xFrom]..[xTo] der Breite, 45–65 % der Höhe) */
    private fun assertDark(what: String, xFrom: Double, xTo: Double) {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
        var sum = 0L
        var n = 0
        val x0 = (shot.width * xFrom).toInt()
        val x1 = (shot.width * xTo).toInt()
        for (y in (shot.height * 0.45).toInt() until (shot.height * 0.65).toInt() step 4) {
            for (x in x0 until x1) {
                val c = shot.getPixel(x, y)
                sum += (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3
                n++
            }
        }
        val brightness = sum / maxOf(n, 1)
        org.junit.Assert.assertTrue("$what ist zu hell (Helligkeit $brightness)", brightness < 70)
    }

    /** Das echte App-Symbol (wie auf dem Homescreen) als Bild speichern */
    private fun saveAppIcon() {
        val ctx = instrumentation.targetContext
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        val bitmap = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(30, 30, 34))
        icon.setBounds(0, 0, 360, 360)
        icon.draw(canvas)
        val dir = File(ctx.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "a00-App-Symbol.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    /** Zurück-Taste (schließt auch offene Blätter/Dialoge) */
    private fun back() {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        Thread.sleep(700) // Blatt-/Tastatur-Animation abwarten
    }

    /** Zurück drücken, bis ein Element mit [tag] sichtbar ist (Tastatur/Blätter schließen zuerst) */
    private fun backUntil(tag: String) {
        repeat(4) {
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            back()
        }
        rule.waitUntilAtLeastOneExists(hasTestTag(tag), 5_000)
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val full = instrumentation.uiAutomation.takeScreenshot() ?: return
        // Verkleinert als JPEG speichern (wird im Workflow-Log als Vorschau ausgegeben)
        val bitmap = Bitmap.createScaledBitmap(full, 360, full.height * 360 / full.width, true)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 60, it) }
    }

    @Test fun t1_tabsPlaylistAndSettings() {
        // Start-Tab ist die erste Seite
        rule.waitUntilAtLeastOneExists(hasTestTag("startScreen"), 10_000)
        screenshot("a00-Start-leer")
        saveAppIcon()
        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Noch keine Musik", ignoreCase = true), 10_000)
        rule.onNodeWithTag("libraryList").performScrollToNode(hasText("Heruntergeladen"))
        rule.onNodeWithText("Heruntergeladen").assertExists()
        screenshot("a01-Mediathek-leer")

        // Neue Playlist direkt in der Mediathek
        rule.onNodeWithTag("newPlaylistButton").performClick()
        rule.onNodeWithTag("dialogInput").performTextInput("Sommerhits")
        rule.onNodeWithTag("dialogConfirm").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Sommerhits", ignoreCase = true), 5_000)
        screenshot("a02-Neue-Playlist")
        rule.onNodeWithContentDescription("Zurück").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("libraryList"), 5_000)
        rule.onNodeWithTag("libraryList").performScrollToNode(hasText("Sommerhits", ignoreCase = true))
        rule.waitUntilAtLeastOneExists(hasText("Sommerhits", ignoreCase = true), 5_000)

        // Info & Updates
        rule.onNodeWithTag("libraryList").performScrollToNode(hasTestTag("settingsButton"))
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("currentVersion"), 5_000)
        rule.onNodeWithTag("convertAllButton").assertExists()
        Thread.sleep(3000)
        screenshot("a13-Info-Updates")
        back() // Info-Blatt schließen
        backUntil("tab-downloader")

        // Browser-Tab
        rule.onNodeWithTag("tab-downloader").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("addressField"), 5_000)
        // Startseite gezielt laden (der Browser behält Seiten aus anderen Tests)
        instrumentation.runOnMainSync { instrumentation.targetContext.app.browser.loadStartPage() }
        Thread.sleep(2500)
        screenshot("a03-Browser-Startseite")
        assertDark("Browser-Startseite", 0.005, 0.03)

        // Fremde, helle Webseite muss ebenfalls dunkel dargestellt werden
        instrumentation.runOnMainSync { instrumentation.targetContext.app.browser.submit("https://example.org/") }
        Thread.sleep(6000)
        screenshot("a03b-Fremde-Seite-dunkel")
        assertDark("Fremde Webseite", 0.3, 0.7)
        instrumentation.runOnMainSync { instrumentation.targetContext.app.browser.loadStartPage() }
        rule.onNodeWithTag("downloadsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("downloadLinkField"), 5_000)
        screenshot("a04-Downloads-leer")
        rule.onNodeWithTag("closeDownloads").performClick()
    }

    @Test fun t2_downloadByLinkPlayAndMenu() {
        rule.onNodeWithTag("tab-downloader").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("downloadsButton"), 5_000)
        rule.onNodeWithTag("downloadsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("downloadLinkField"), 5_000)
        // Lokaler Testserver des Workflows (der Emulator erreicht den Rechner über 10.0.2.2)
        rule.onNodeWithTag("downloadLinkField").performTextInput("http://10.0.2.2:8765/tagged.mp3")
        rule.onNodeWithTag("downloadStartButton").performClick()
        Thread.sleep(1500)
        screenshot("a05-Download-laeuft")
        rule.waitUntilAtLeastOneExists(hasTestTag("downloadFinished"), 120_000)
        screenshot("a06-Download-fertig")
        rule.onNodeWithTag("closeDownloads").performClick()

        // Download liegt pausiert in der Wiedergabeliste → Mini-Player sichtbar
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayer"), 10_000)

        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasText("1 Titel"), 10_000)
        rule.onNodeWithTag("libraryList").performScrollToNode(hasText("Heruntergeladen"))
        rule.onNodeWithText("Heruntergeladen").performClick()
        rule.onNodeWithTag("playAllButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayPause"), 5_000)
        Thread.sleep(3000)
        screenshot("a07-Playlist-spielt")

        // Start-Tab: Titel steht im Verlauf
        rule.onNodeWithTag("tab-start").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("startScreen"), 5_000)
        rule.waitUntil(10_000) { instrumentation.targetContext.app.library.history().isNotEmpty() }
        // Der Verlauf steht weiter unten auf der Startseite → hinscrollen
        rule.onNodeWithTag("startScreen").performScrollToNode(hasTestTag("history-Testlied"))
        screenshot("a09-Start-Verlauf")
        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayer"), 5_000)

        rule.onNodeWithTag("miniPlayer").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("bigPlayPause"), 5_000)
        Thread.sleep(800)
        screenshot("a08-Player")

        // Schlafmodus: Minuten eingeben → Timer läuft, Restzeit sichtbar → wieder beenden
        val player = instrumentation.targetContext.app.player
        rule.onNodeWithTag("sleepButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sleepMinutes"), 5_000)
        rule.onNodeWithTag("sleepMinutes").performTextInput("30")
        Thread.sleep(500)
        screenshot("a18-Schlafmodus")
        rule.onNodeWithTag("sleepStart").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sleepRemaining"), 5_000)
        check(player.sleepRemainingMs()!! in 29 * 60_000L..30 * 60_000L) { "Schlafmodus nicht auf 30 Minuten gestellt" }
        Thread.sleep(800)
        screenshot("a19-Schlafmodus-laeuft")
        rule.onNodeWithTag("sleepButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sleepCancel"), 5_000)
        rule.onNodeWithTag("sleepCancel").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("sleepRemaining").fetchSemanticsNodes().isEmpty() }
        check(player.sleepRemainingMs() == null) { "Schlafmodus nicht beendet" }

        backUntil("trackMore")

        // Optionsmenü
        rule.onAllNodesWithTag("trackMore").onFirst().performClick()
        listOf("Als Nächstes abspielen", "Zu Playlist hinzufügen", "Teilen", "Mix starten", "In die Wiedergabeliste",
            "Mitwirkende an Song anzeigen", "An Kurzwahl anpinnen").forEach {
            rule.waitUntilAtLeastOneExists(hasTestTag("action-$it"), 5_000)
        }
        screenshot("a14-Optionen")
        rule.onNodeWithTag("action-Mitwirkende an Song anzeigen").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("info-Format"), 10_000)
        screenshot("a15-Mitwirkende")
    }

    /** Ohne Internet: Musik spielt, Browser zeigt die Offline-Seite */
    @Test fun t4_worksOffline() {
        val app = instrumentation.targetContext.app
        // Einen Titel in die Bibliothek legen (wie ein früherer Download)
        val dir = File(instrumentation.targetContext.cacheDir, "offline-test").apply { mkdirs() }
        val file = File(dir, "Offline.mp3")
        instrumentation.context.assets.open("tagged.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        kotlinx.coroutines.runBlocking { app.library.import(file, move = true) }

        val shell = instrumentation.uiAutomation
        fun run(cmd: String) = shell.executeShellCommand(cmd).close()
        run("cmd connectivity airplane-mode enable"); run("svc wifi disable"); run("svc data disable")
        try {
            // Warten, bis das Netz wirklich weg ist
            val cm = instrumentation.targetContext.getSystemService(android.net.ConnectivityManager::class.java)
            repeat(30) { if (cm.activeNetwork != null) Thread.sleep(500) }
            Thread.sleep(1000)
            rule.onNodeWithTag("tab-library").performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("category-Titel"), 5_000)
            rule.onNodeWithTag("category-Titel").performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("playAllButton"), 5_000)
            rule.onNodeWithTag("playAllButton").performClick()
            rule.waitUntilAtLeastOneExists(hasContentDescription("Pause"), 10_000)
            screenshot("a16-Offline-spielt")

            rule.onNodeWithTag("tab-downloader").performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("addressField"), 5_000)
            // Adresse, die sicher nicht im Zwischenspeicher liegt
            instrumentation.runOnMainSync { app.browser.submit("https://offline-test-${System.currentTimeMillis()}.example.org/") }
            rule.waitUntilAtLeastOneExists(hasTestTag("offlineHint"), 30_000)
            Thread.sleep(1000)
            screenshot("a17-Offline-Browser")
        } finally {
            run("cmd connectivity airplane-mode disable"); run("svc wifi enable"); run("svc data enable")
            Thread.sleep(3000)
        }
    }

    @Test fun t3_youTubeHint() {
        rule.onNodeWithTag("tab-downloader").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("addressField"), 5_000)
        instrumentation.runOnMainSync { instrumentation.targetContext.app.browser.submit("https://m.youtube.com") }
        rule.waitUntilAtLeastOneExists(hasTestTag("youtubeHint"), 30_000)
        screenshot("a12-YouTube-Hinweis")
    }

    /** Sortieren, Mehrfachauswahl, Lieblingssongs, Statistik, Klang */
    @Test fun t5_newFeatures() {
        val app = instrumentation.targetContext.app
        val dir = File(instrumentation.targetContext.cacheDir, "ui-features").apply { mkdirs() }
        val tracks = listOf("Alpha", "Bravo", "Charlie").map { name ->
            val f = File(dir, "$name.mp3")
            instrumentation.context.assets.open("untagged.mp3").use { i -> f.outputStream().use { i.copyTo(it) } }
            kotlinx.coroutines.runBlocking { app.library.import(f, move = true) }
        }
        tracks.forEach { app.library.recordPlay(it.id) }
        app.library.recordPlay(tracks[1].id)
        app.library.addListened(47 * 60_000L)

        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("category-Titel"), 5_000)
        rule.onNodeWithTag("category-Titel").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sortButton"), 5_000)

        // Sortieren
        rule.onNodeWithTag("sortButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sort-MOST_PLAYED"), 5_000)
        screenshot("a20-Sortieren")
        rule.onNodeWithTag("sort-MOST_PLAYED").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(com.backewatsch.mp3player.data.TrackSort.MOST_PLAYED, app.trackSort.value)

        // Mehrfachauswahl: lange drücken, zweites antippen, Herz für beide
        rule.onNodeWithTag("track-Alpha").performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("selectionCount"), 5_000)
        rule.onNodeWithTag("track-Charlie").performClick()
        rule.waitUntilAtLeastOneExists(hasText("2 ausgewählt"), 5_000)
        screenshot("a21-Mehrfachauswahl")
        rule.onNodeWithTag("sel-fav").performClick()
        rule.waitUntil(5_000) { app.library.state.value.favorites.size == 2 }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("selectionCount").fetchSemanticsNodes().isEmpty() }
        app.setTrackSort(com.backewatsch.mp3player.data.TrackSort.TITLE)

        // Lieblingssongs
        rule.onNodeWithContentDescription("Zurück").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("libraryList"), 5_000)
        rule.onNodeWithTag("libraryList").performScrollToNode(hasTestTag("category-Lieblingssongs"))
        rule.onNodeWithTag("category-Lieblingssongs").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("track-Charlie"), 5_000)
        screenshot("a22-Lieblingssongs")
        rule.onNodeWithContentDescription("Zurück").performClick()

        // Statistik
        rule.waitUntilAtLeastOneExists(hasTestTag("libraryList"), 5_000)
        rule.onNodeWithTag("libraryList").performScrollToNode(hasTestTag("category-Statistik"))
        rule.onNodeWithTag("category-Statistik").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("stat-month"), 5_000)
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("47 Min.", substring = true).fetchSemanticsNodes().isNotEmpty())
        screenshot("a23-Statistik")
        rule.onNodeWithContentDescription("Zurück").performClick()

        // Startseite zeigt Lieblingssongs und Statistik
        rule.onNodeWithTag("tab-start").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("startStats"), 5_000)
        screenshot("a24-Start-Favoriten")

        // Player: Herz und Klang
        instrumentation.runOnMainSync { app.player.play(tracks, 1) }
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayer"), 10_000)
        rule.onNodeWithTag("miniPlayer").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("favButton"), 5_000)
        rule.onNodeWithTag("favButton").performClick()
        rule.waitUntil(5_000) { app.library.isFavorite(tracks[1]) }
        rule.onNodeWithTag("soundButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("crossfadeSlider"), 5_000)
        Thread.sleep(800)
        screenshot("a25-Klang")
        backUntil("tab-start")
    }

    /** Neues Design: Startseite mit Mixen, Player mit Songtext, Mix-Ansicht und Doppelte */
    @Test fun t6_streamingDesignAndLyrics() {
        val app = instrumentation.targetContext.app
        val dir = File(instrumentation.targetContext.cacheDir, "ui-design").apply { mkdirs() }
        val tracks = listOf("Sonnenaufgang", "Nachtfahrt", "Nachtfahrt (2)").map { name ->
            val f = File(dir, "$name.mp3")
            instrumentation.context.assets.open("untagged.mp3").use { i -> f.outputStream().use { i.copyTo(it) } }
            kotlinx.coroutines.runBlocking { app.library.import(f, move = true) }
        }
        // Doppelte erzeugen: gleicher Titel
        app.library.rename(tracks[2], "Nachtfahrt", "")
        app.library.toggleFavorite(tracks[0])
        app.library.recordStart(tracks[0].id)

        rule.onNodeWithTag("tab-start").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("mix-RECENTLY_ADDED"), 10_000)
        screenshot("a26-Start-Design")
        rule.onNodeWithTag("mix-RECENTLY_ADDED").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("playAllButton"), 5_000)
        screenshot("a27-Mix")
        rule.onNodeWithTag("playAllButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayer"), 10_000)
        Thread.sleep(800)
        screenshot("a28-Mini-Player")

        // Player + Songtext eintragen
        rule.onNodeWithTag("miniPlayer").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("lyricsButton"), 5_000)
        Thread.sleep(800)
        screenshot("a29-Player-Design")
        rule.onNodeWithTag("lyricsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("lyricsEmpty"), 5_000)
        rule.onNodeWithTag("lyricsEdit").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("lyricsField"), 5_000)
        rule.onNodeWithTag("lyricsField").performTextInput("Wir fahren durch die Nacht\nund singen laut")
        rule.onNodeWithTag("lyricsSave").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("lyricsText"), 5_000)
        Thread.sleep(500)
        screenshot("a30-Songtext")
        val current = app.player.state.value.currentId!!
        org.junit.Assert.assertEquals("Wir fahren durch die Nacht\nund singen laut", app.library.state.value.lyrics[current])
        backUntil("tab-library")

        // Doppelte über die Einstellungen
        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("settingsButton"), 5_000)
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("openDuplicates"), 5_000)
        rule.onNodeWithTag("openDuplicates").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("duplicatesSummary"), 5_000)
        screenshot("a31-Doppelte")
        rule.onNodeWithTag("mergeAll").performClick()
        rule.waitUntil(5_000) { app.library.duplicateGroups().isEmpty() }
        org.junit.Assert.assertEquals(2, app.library.tracks.size)
    }

    /** Suche nach Kategorien, letzte Suchen und Genres */
    @Test fun t7_searchAndGenres() {
        val app = instrumentation.targetContext.app
        val dir = File(instrumentation.targetContext.cacheDir, "ui-search").apply { mkdirs() }
        listOf("Bella Ciao" to "Pop", "Sommernacht" to "Hip-Hop", "Regenlied" to "Rock").forEach { (name, genre) ->
            val f = File(dir, "$name.mp3")
            instrumentation.context.assets.open("untagged.mp3").use { i -> f.outputStream().use { i.copyTo(it) } }
            val t = kotlinx.coroutines.runBlocking { app.library.import(f, move = true) }
            app.library.editInfo(t, name, if (name == "Sommernacht") "Bella" else "Band", "Album $genre", genre)
        }
        app.clearSearches()

        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("searchField"), 5_000)
        rule.onNodeWithTag("searchField").performTextInput("bella")
        rule.waitUntilAtLeastOneExists(hasTestTag("result-artist-Bella"), 5_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("track-Bella Ciao"), 5_000)
        screenshot("a32-Suche")
        rule.onNodeWithTag("track-Bella Ciao").performClick()
        rule.waitUntil(5_000) { app.recentSearches.value == listOf("bella") }

        // Letzte Suchen erscheinen beim leeren Suchfeld
        rule.onNodeWithTag("searchClear").performClick()
        rule.onNodeWithTag("searchField").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("recent-bella"), 5_000)
        screenshot("a33-Letzte-Suchen")

        // Genres
        rule.onNodeWithTag("libraryList").performScrollToNode(hasTestTag("category-Genres"))
        rule.onNodeWithTag("category-Genres").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("genre-Hip-Hop"), 5_000)
        screenshot("a34-Genres")
        rule.onNodeWithTag("genre-Hip-Hop").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("track-Sommernacht"), 5_000)
        screenshot("a35-Genre-HipHop")
    }
}
