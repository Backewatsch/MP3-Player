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
        instrumentation.runOnMainSync { app.player.setRepeatMode(Player.REPEAT_MODE_ONE) }
        waitFor("Wiedergabe startet", 10_000) {
            app.player.state.value.isPlaying && app.player.state.value.repeatMode == Player.REPEAT_MODE_ONE
        }

        instrumentation.runOnMainSync { app.player.startSleepTimerMs(3_000) }
        // Vor Ablauf ist der Timer noch aktiv (ob das 2-s-Testlied gerade lädt, ist im Emulator zufällig)
        Thread.sleep(800)
        var active = false
        instrumentation.runOnMainSync { active = app.player.sleepRemainingMs() != null }
        assertTrue("Timer läuft noch", active)

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
        instrumentation.runOnMainSync { app.player.setRepeatMode(Player.REPEAT_MODE_ONE) }
        waitFor("Wiedergabe startet", 10_000) {
            app.player.state.value.isPlaying && app.player.state.value.repeatMode == Player.REPEAT_MODE_ONE
        }
        instrumentation.runOnMainSync { app.player.startSleepTimerMs(2_000); app.player.cancelSleepTimer() }
        Thread.sleep(3_000)
        waitFor("abgebrochener Timer schaltet nichts aus", 1_500) { app.player.state.value.isPlaying }
    }
}
