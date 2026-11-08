package com.backewatsch.mp3player

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
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
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun reset() {
        instrumentation.runOnMainSync { instrumentation.targetContext.app.library.resetForTests() }
        rule.waitForIdle()
    }

    /** Zurück-Taste (schließt auch offene Blätter/Dialoge) */
    private fun back() {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun t1_tabsPlaylistAndSettings() {
        rule.waitUntilAtLeastOneExists(hasText("Noch keine Musik"), 10_000)
        rule.onNodeWithText("Heruntergeladen").assertExists()
        screenshot("a01-Mediathek-leer")

        // Neue Playlist direkt in der Mediathek
        rule.onNodeWithTag("newPlaylistButton").performClick()
        rule.onNodeWithTag("dialogInput").performTextInput("Sommerhits")
        rule.onNodeWithTag("dialogConfirm").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Sommerhits"), 5_000)
        screenshot("a02-Neue-Playlist")
        back()
        rule.waitUntilAtLeastOneExists(hasText("Sommerhits"), 5_000)

        // Info & Updates
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("currentVersion"), 5_000)
        rule.onNodeWithTag("convertAllButton").assertExists()
        Thread.sleep(3000)
        screenshot("a13-Info-Updates")
        back()

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
        rule.onNodeWithTag("downloadLinkField").performTextInput("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3")
        rule.onNodeWithTag("downloadStartButton").performClick()
        Thread.sleep(1500)
        screenshot("a05-Download-laeuft")
        rule.waitUntilAtLeastOneExists(hasTestTag("downloadFinished"), 180_000)
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

        rule.onNodeWithTag("miniPlayer").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("bigPlayPause"), 5_000)
        Thread.sleep(800)
        screenshot("a08-Player")
        back()
        Thread.sleep(800)

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

    @Test fun t3_youTubeHint() {
        rule.onNodeWithTag("tab-downloader").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("addressField"), 5_000)
        rule.onNodeWithTag("addressField").performTextInput("https://m.youtube.com")
        rule.onNodeWithTag("addressField").performImeAction()
        rule.waitUntilAtLeastOneExists(hasTestTag("youtubeHint"), 30_000)
        screenshot("a12-YouTube-Hinweis")
    }
}
