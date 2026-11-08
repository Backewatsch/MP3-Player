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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        rule.onNodeWithText("Heruntergeladen").assertExists()
        screenshot("a01-Mediathek-leer")

        // Neue Playlist direkt in der Mediathek
        rule.onNodeWithTag("newPlaylistButton").performClick()
        rule.onNodeWithTag("dialogInput").performTextInput("Sommerhits")
        rule.onNodeWithTag("dialogConfirm").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Sommerhits", ignoreCase = true), 5_000)
        screenshot("a02-Neue-Playlist")
        rule.onNodeWithContentDescription("Zurück").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("settingsButton"), 5_000)
        rule.waitUntilAtLeastOneExists(hasText("Sommerhits", ignoreCase = true), 5_000)

        // Info & Updates
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
        Thread.sleep(2000)
        screenshot("a03-Browser-Startseite")
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
        rule.onNodeWithText("Heruntergeladen").performClick()
        rule.onNodeWithTag("playAllButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayPause"), 5_000)
        Thread.sleep(3000)
        screenshot("a07-Playlist-spielt")

        // Start-Tab: Titel steht im Verlauf
        rule.onNodeWithTag("tab-start").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("history-Testlied"), 10_000)
        screenshot("a09-Start-Verlauf")
        rule.onNodeWithTag("tab-library").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("miniPlayer"), 5_000)

        rule.onNodeWithTag("miniPlayer").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("bigPlayPause"), 5_000)
        Thread.sleep(800)
        screenshot("a08-Player")
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
            Thread.sleep(3000)
            rule.onNodeWithTag("tab-library").performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("category-Titel"), 5_000)
            rule.onNodeWithTag("category-Titel").performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("playAllButton"), 5_000)
            rule.onNodeWithTag("playAllButton").performClick()
            rule.waitUntilAtLeastOneExists(hasContentDescription("Pause"), 10_000)
            screenshot("a16-Offline-spielt")

            rule.onNodeWithTag("tab-downloader").performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("addressField"), 5_000)
            instrumentation.runOnMainSync { app.browser.submit("https://archive.org") }
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
}
