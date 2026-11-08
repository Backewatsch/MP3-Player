package com.backewatsch.mp3player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.backewatsch.mp3player.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** „Als Nächstes abspielen“ mit dem echten Player-Dienst: Titel kommt direkt nach dem laufenden – auch bei Zufall. */
@RunWith(AndroidJUnit4::class)
class QueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.app
    private lateinit var a: Track
    private lateinit var b: Track
    private lateinit var c: Track
    private lateinit var d: Track
    private lateinit var x: Track
    private lateinit var y: Track

    @Before fun setUp() {
        instrumentation.runOnMainSync { app.player.stop(); app.library.resetForTests() }
        fun add(name: String): Track {
            val dir = File(instrumentation.targetContext.cacheDir, "queue-test").apply { mkdirs() }
            val file = File(dir, "$name.mp3")
            instrumentation.context.assets.open("untagged.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
            return runBlocking { app.library.import(file, move = true) }
        }
        a = add("A"); b = add("B"); c = add("C"); d = add("D"); x = add("X"); y = add("Y")
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { app.player.stop() }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun queue() = app.player.state.value.queueIds
    private fun currentId() = app.player.state.value.currentId

    private fun waitFor(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            main { ok = condition() }
            if (ok) return
            Thread.sleep(100)
        }
        var ok = false
        main { ok = condition() }
        assertTrue("$message – Wiedergabeliste: ${queue().map(::name)}, läuft: ${currentId()?.let(::name)}", ok)
    }

    private fun name(id: String) = listOf(a, b, c, d, x, y).firstOrNull { it.id == id }?.fileName?.substringBefore('.') ?: id

    @Test fun playNextComesRightAfterCurrentInChosenOrder() {
        main { app.player.play(listOf(a, b, c), 0) }
        waitFor("Wiedergabe startet") { queue().size == 3 && currentId() == a.id }

        main { app.player.playNext(x); app.player.playNext(y) }
        waitFor("X und Y direkt nach A, in gewählter Reihenfolge") {
            queue() == listOf(a.id, x.id, y.id, b.id, c.id)
        }

        main { app.player.next() }
        waitFor("als Nächstes kommt X") { currentId() == x.id }
        main { app.player.next() }
        waitFor("danach Y") { currentId() == y.id }
        main { app.player.next() }
        waitFor("dann geht es mit B weiter") { currentId() == b.id }

        // Neues „Als Nächstes“ während B läuft → direkt nach B
        main { app.player.playNext(d) }
        main { app.player.next() }
        waitFor("D kommt direkt nach B") { currentId() == d.id }
    }

    @Test fun playNextAlsoWorksWithShuffle() {
        main { app.player.play(listOf(a, b, c, d), 0, shuffle = true) }
        waitFor("Zufallswiedergabe startet mit A") { queue().size == 4 && currentId() == a.id && app.player.state.value.shuffle }

        main { app.player.playNext(x) }
        main { app.player.next() }
        waitFor("trotz Zufall kommt X als Nächstes") { currentId() == x.id }
    }

    @Test fun shuffleOffRestoresOrderAndKeepsPlaying() {
        main { app.player.play(listOf(a, b, c, d), 0) }
        waitFor("Start") { queue() == listOf(a.id, b.id, c.id, d.id) }
        main { app.player.toggleShuffle() }
        waitFor("Zufall an, A läuft weiter") { app.player.state.value.shuffle && currentId() == a.id && queue().first() == a.id }
        main { app.player.toggleShuffle() }
        waitFor("Zufall aus → ursprüngliche Reihenfolge") { !app.player.state.value.shuffle && queue() == listOf(a.id, b.id, c.id, d.id) }
        assertEquals(a.id, currentId())
    }

    @Test fun addToQueueGoesToTheEnd() {
        main { app.player.play(listOf(a, b), 0) }
        waitFor("Start") { queue().size == 2 }
        main { app.player.addToQueue(x); app.player.playNext(y) }
        waitFor("Y als Nächstes, X ans Ende") { queue() == listOf(a.id, y.id, b.id, x.id) }
    }
}
