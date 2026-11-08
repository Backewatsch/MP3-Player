package com.backewatsch.mp3player

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schlafmodus mit dem echten Player-Dienst: nach Ablauf ist die Musik aus. */
@RunWith(AndroidJUnit4::class)
class SleepTimerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.app

    @Before fun setUp() {
        instrumentation.runOnMainSync { app.player.stop(); app.library.resetForTests() }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { app.player.stop() }
    }

    private fun waitFor(message: String, timeoutMs: Long, condition: () -> Boolean) {
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

    @Test fun musicStopsWhenTimerEnds() {
        val dir = File(instrumentation.targetContext.cacheDir, "sleep-test").apply { mkdirs() }
        val file = File(dir, "Schlaf.mp3")
        instrumentation.context.assets.open("untagged.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = runBlocking { app.library.import(file, move = true) }

        instrumentation.runOnMainSync { app.player.play(listOf(track), 0) }
        // Endlos wiederholen, damit die Musik bis zum Timer weiterläuft
        waitFor("Wiedergabe startet", 10_000) {
            if (app.player.state.value.repeatMode == Player.REPEAT_MODE_OFF) app.player.cycleRepeat()
            app.player.state.value.isPlaying && app.player.state.value.repeatMode != Player.REPEAT_MODE_OFF
        }

        instrumentation.runOnMainSync { app.player.startSleepTimerMs(3_000) }
        Thread.sleep(1_500)
        var stillPlaying = false
        instrumentation.runOnMainSync { stillPlaying = app.player.state.value.isPlaying && app.player.sleepRemainingMs() != null }
        assertTrue("vor Ablauf läuft die Musik weiter", stillPlaying)

        waitFor("nach Ablauf ist die Musik aus und der Timer beendet", 8_000) {
            !app.player.state.value.isPlaying && app.player.sleepRemainingMs() == null
        }
    }

    @Test fun cancelKeepsMusicPlaying() {
        val dir = File(instrumentation.targetContext.cacheDir, "sleep-test2").apply { mkdirs() }
        val file = File(dir, "Wach.mp3")
        instrumentation.context.assets.open("untagged.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = runBlocking { app.library.import(file, move = true) }

        instrumentation.runOnMainSync { app.player.play(listOf(track), 0) }
        waitFor("Wiedergabe startet", 10_000) {
            if (app.player.state.value.repeatMode == Player.REPEAT_MODE_OFF) app.player.cycleRepeat()
            app.player.state.value.isPlaying && app.player.state.value.repeatMode != Player.REPEAT_MODE_OFF
        }
        instrumentation.runOnMainSync { app.player.startSleepTimerMs(2_000); app.player.cancelSleepTimer() }
        Thread.sleep(3_500)
        var playing = false
        instrumentation.runOnMainSync { playing = app.player.state.value.isPlaying }
        assertTrue("abgebrochener Timer schaltet nichts aus", playing)
    }
}
